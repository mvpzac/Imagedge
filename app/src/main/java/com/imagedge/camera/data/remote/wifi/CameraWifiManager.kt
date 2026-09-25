package com.imagedge.camera.data.remote.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import com.imagedge.camera.core.common.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相机 WiFi 引导——热点连接、网关 IP 自动发现、进程级网络绑定（蜂窝数据共存）
 *     version: 1.0
 * </pre>
 */

@Singleton
class CameraWifiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** 保持 NetworkCallback 引用，避免被 GC */
    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 每次新建或释放 requestNetwork 都递增。系统可能在 unregister 后继续投递已排队的
     * onAvailable/onUnavailable；回调只有在代数仍匹配时才能改写当前网络状态。
     */
    private val requestGeneration = AtomicLong(0L)

    /**
     * 配网建立的相机网络。关键约束：WifiNetworkSpecifier 的连接只在
     * requestNetwork 请求存活期间有效——释放请求即断开热点连接。
     * 因此成功后必须保持请求（回调）存活，直到用户主动断开。
     */
    @Volatile
    private var cameraNetwork: Network? = null

    /** 上次释放配网请求的时间戳（用于规避「刚断开就重连」的系统竞态） */
    private var lastReleaseAt = 0L

    /** 配网请求超时（P2-9）：超时后系统回调 onUnavailable，UI 不再无限转圈 */
    private val HOTSPOT_REQUEST_TIMEOUT_MS = 15_000

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 获取当前 WiFi 网络的网关 IP（索尼相机"发送到智能手机"模式下，网关即相机 IP）
     */
    fun getCurrentGatewayIp(): String? {
        // 优先：遍历所有网络，找 WiFi，读 LinkProperties 的网关（Android 10+ 可靠）
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue

            val gateway = getGateway(network)
            if (gateway != null) return gateway
        }

        // 兜底：DHCP 信息（部分旧设备）
        val dhcp = wifiManager.dhcpInfo
        if (dhcp != null && dhcp.gateway != 0) {
            return intToIp(dhcp.gateway)
        }
        return null
    }

    /** 从网络的 LinkProperties 提取默认网关 IP */
    private fun getGateway(network: Network): String? {
        val linkProperties: LinkProperties =
            connectivityManager.getLinkProperties(network) ?: return null
        // 只取 IPv4 默认路由（相机 PTP/HTTP 只支持 IPv4；fe80:: 链路本地 IPv6 不可用）
        val gateway = linkProperties.routes
            .firstOrNull {
                it.isDefaultRoute && it.gateway?.hostAddress?.contains(":") == false
            }
            ?.gateway
        return gateway?.hostAddress
    }

    /**
     * 将进程绑定到 WiFi 网络（蜂窝数据开启时，PTP/IP Socket 仍走相机 WiFi）
     */
    fun bindProcessToWifi(): Boolean {
        // 配网建立的相机网络优先（双 WLAN 机型上 allNetworks 可能同时有家 WiFi，
        // findWifiNetwork 顺序不定，绑错网络会导致 PTP 连到家里路由器）
        cameraNetwork?.let { return connectivityManager.bindProcessToNetwork(it) }
        val network = findWifiNetwork() ?: return false
        return connectivityManager.bindProcessToNetwork(network)
    }

    /**
     * 连接相机热点（Android 10+，WifiNetworkSpecifier）
     *
     * 凭据按 [auth] 分支设置——**不要无条件设成 WPA2**：开放热点（T:nopass）
     * 传空密码走 WPA2 会被系统直接判为不可用，WPA3 热点则需要 setWpa3Passphrase。
     *
     * @param ssid 热点名（如 DIRECT-xxxx-ZV-E10）
     * @param password 热点密码；[WifiAuth.OPEN] 时可为 null（不设置任何凭据）
     * @param auth 认证方式（来自二维码 T 字段 / 索尼 W01 默认 WPA2）
     * @param onResult 连接结果回调
     */
    fun connectToCameraHotspot(
        ssid: String?,
        password: String?,
        bssid: String? = null,
        auth: WifiAuth = WifiAuth.WPA2,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        // 记录本次请求距上次释放的间隔：刚断开就重连同一热点，系统仍在拆除旧网络，
        // 立刻 requestNetwork 会被直接判为 onUnavailable（用户"断开后再扫码连不上"的元凶之一）
        val sinceRelease = android.os.SystemClock.elapsedRealtime() - lastReleaseAt
        releaseNetworkRequest()
        val generation = requestGeneration.incrementAndGet()

        // 至少要有 SSID 或 BSSID 之一；BSSID 匹配不受设备名影响，优先使用
        check(ssid != null || bssid != null) { "SSID 与 BSSID 均为空" }
        val builder = WifiNetworkSpecifier.Builder()
        if (ssid != null) builder.setSsid(ssid)
        when (auth) {
            WifiAuth.OPEN -> AppLog.i(TAG, "开放热点（T:nopass），不设置凭据")
            WifiAuth.WPA3 -> builder.setWpa3Passphrase(password.orEmpty())
            WifiAuth.WEP -> {
                // WifiNetworkSpecifier 无 WEP 通道：明确失败，由调用方引导手动连接
                AppLog.w(TAG, "二维码使用 WEP 加密，WifiNetworkSpecifier 不支持自动配网")
                onResult(false, "该二维码使用 WEP 加密，Android 不支持自动配网，请在系统 Wi-Fi 设置中手动连接")
                return
            }
            WifiAuth.WPA2 -> builder.setWpa2Passphrase(password.orEmpty())
        }
        if (bssid != null) {
            runCatching { android.net.MacAddress.fromString(bssid) }.getOrNull()?.let {
                builder.setBssid(it)
            }
        }
        val specifier = builder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isCurrentRequest(this, generation)) return
                // P2-9：回调在系统 Binder 线程执行，且 network 到达时可能已被系统拆除
                //（快速切换热点场景），bindProcessToNetwork 会抛异常——不捕获会直接崩进程
                runCatching {
                    if (!isCurrentRequest(this, generation)) return@runCatching
                    cameraNetwork = network
                    connectivityManager.bindProcessToNetwork(network)
                    AppLog.i(TAG, "相机热点已连接：network=$network")
                    onResult(true, null)
                }.onFailure {
                    AppLog.e(TAG, "onAvailable 处理失败（网络可能已被回收）：${it.message}")
                    onResult(false, "相机热点连接异常，请重试")
                }
            }

            override fun onLost(network: Network) {
                if (!isCurrentRequest(this, generation) || cameraNetwork != network) return
                AppLog.w(TAG, "相机热点连接丢失：network=$network")
                cameraNetwork = null
            }

            override fun onUnavailable() {
                if (!isCurrentRequest(this, generation)) return
                AppLog.w(TAG, "相机热点不可用（requestNetwork → onUnavailable）")
                networkCallback = null
                cameraNetwork = null
                onResult(false, "连接相机热点失败，请检查 SSID 与密码")
            }
        }
        networkCallback = callback

        // 距上次释放不足 1.5s 则稍作等待，等系统把旧网络拆干净再请求
        val postDelay = if (sinceRelease < 1500L) 800L else 0L
        if (postDelay > 0L) {
            AppLog.i(TAG, "距上次释放仅 ${sinceRelease}ms，延迟 ${postDelay}ms 后重新配网")
            mainHandler.postDelayed({
                if (isCurrentRequest(callback, generation)) requestNetwork(request, callback)
            }, postDelay)
        } else {
            requestNetwork(request, callback)
        }
    }

    /**
     * P2-9：用带超时的 requestNetwork 重载（15s）。
     * 无超时版本在密码错误/相机不广播等场景会一直挂着，UI 永远停在「连接中」；
     * 超时后系统会回调 onUnavailable，用户至少能得到明确的失败提示并重试。
     */
    private fun requestNetwork(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback) {
        runCatching {
            connectivityManager.requestNetwork(request, callback, HOTSPOT_REQUEST_TIMEOUT_MS)
        }.onFailure {
            AppLog.e(TAG, "requestNetwork 异常", it)
            callback.onUnavailable()
        }
    }

    /**
     * 释放热点连接请求（会同时断开已建立的相机热点连接）。
     * 仅在发起新配网请求（替换旧请求）或用户主动断开时调用；
     * 配网成功后 UI 层销毁弹窗不得调用，否则刚建立的连接被系统拆除。
     */
    fun releaseNetworkRequest() {
        requestGeneration.incrementAndGet()
        // 移除待执行的延迟配网，避免释放后又冒出一个 request 把刚断的连接重新拉起
        mainHandler.removeCallbacksAndMessages(null)
        val callback = networkCallback
        networkCallback = null
        runCatching { callback?.let { connectivityManager.unregisterNetworkCallback(it) } }
        cameraNetwork = null
        lastReleaseAt = android.os.SystemClock.elapsedRealtime()
    }

    private fun isCurrentRequest(
        callback: ConnectivityManager.NetworkCallback,
        generation: Long,
    ): Boolean = networkCallback === callback && requestGeneration.get() == generation

    /** 解绑进程网络 */
    fun unbindProcessNetwork() {
        connectivityManager.bindProcessToNetwork(null)
    }

    /** 查找当前 WiFi 网络 */
    private fun findWifiNetwork(): Network? {
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network
        }
        return null
    }
    /** DHCP 网关整型转点分十进制 IP */
    private fun intToIp(value: Int): String {
        return "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"
    }

    companion object {
        private const val TAG = "wifi"
    }
}
