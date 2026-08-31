package com.imagedge.camera.feature.connection

import androidx.lifecycle.ViewModel
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.remote.wifi.CameraWifiManager
import com.imagedge.camera.data.remote.wifi.parseWifiQr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 扫码连接 ViewModel（QR 解析 → 配网）
 *     version: 1.0
 * </pre>
 */

/** 扫码连接 UI 状态 */
sealed class QrScanUiState {
    data object Idle : QrScanUiState()
    data class Connecting(val ssid: String) : QrScanUiState()
    data class Success(val ssid: String) : QrScanUiState()
    data class Error(val message: String) : QrScanUiState()
}

@HiltViewModel
class QrScanViewModel @Inject constructor(
    private val wifiManager: CameraWifiManager
) : ViewModel() {

    private val _state = MutableStateFlow<QrScanUiState>(QrScanUiState.Idle)
    val state: StateFlow<QrScanUiState> = _state.asStateFlow()

    /** Success 已上报宿主（一次性标记，避免旋转/重组重复触发自动连接） */
    private var reported = false

    /** 解析二维码内容并发起配网 */
    fun onQrContent(content: String) {
        // 正在配网(Connecting)或已成功(Success)时忽略重复帧：分析器在弹窗关闭前会持续
        // 解码同一二维码，若不忽略会反复触发配网——而 connectToCameraHotspot 每次都会先
        // release 旧请求（断开旧连接），导致热点反复重建、最终丢失。
        // Success 纳入忽略是安全的：弹窗关闭时 release() 已把状态回 Idle，不影响二次扫码。
        if (_state.value is QrScanUiState.Connecting || _state.value is QrScanUiState.Success) return

        val info = parseWifiQr(content)
        if (info == null) {
            // 诊断：打印原始内容，确认索尼二维码的实际格式
            AppLog.i(TAG, "QR 原始内容（非标准 WIFI 格式）：" + content.take(300))
            _state.value = QrScanUiState.Error("二维码不是 WiFi 连接格式")
            return
        }
        AppLog.i(TAG, "QR 解析成功：ssid=" + info.ssid + "，bssid=" + info.bssid)
        _state.value = QrScanUiState.Connecting(info.ssid)
        val passphrase = info.password ?: ""

        // 顺序实证（14:37 实测）：SSID 公式 5s 即有连接候选，BSSID-only 等了 33s 无果
        // （二维码 M 字段可能非热点 BSSID）。SSID 公式优先，BSSID 兜底。
        fun onFail(msg: String?) {
            if (info.bssid != null) {
                AppLog.w(TAG, "SSID 公式未连上，回退 BSSID 匹配：" + info.bssid)
                wifiManager.connectToCameraHotspot(null, passphrase, info.bssid) { ok2, msg2 ->
                    if (ok2) {
                        _state.value = QrScanUiState.Success(info.ssid)
                    } else {
                        _state.value = QrScanUiState.Error(msg2 ?: msg ?: "连接失败")
                    }
                }
            } else {
                _state.value = QrScanUiState.Error(msg ?: "连接失败")
            }
        }
        wifiManager.connectToCameraHotspot(info.ssid, passphrase, null) { ok, msg ->
            if (ok) {
                // 关键：成功后保持配网请求存活（释放即断开），UI 关闭弹窗不影响连接
                _state.value = QrScanUiState.Success(info.ssid)
            } else {
                onFail(msg)
            }
        }
    }

    /**
     * 消费「配网成功」事件（一次性）。宿主据此关闭弹窗并自动连接相机。
     * 重复调用返回 false，避免旋转/重组导致重复 connect。
     */
    fun consumeSuccess(): Boolean {
        if (_state.value !is QrScanUiState.Success) return false
        if (reported) return false
        reported = true
        return true
    }

    /** 重置（错误后重试）：释放配网请求并回到 Idle */
    fun reset() {
        wifiManager.releaseNetworkRequest()
        reported = false
        _state.value = QrScanUiState.Idle
    }

    /**
     * 弹窗销毁时清理。
     * 两条硬约束：
     * 1. 成功建立的配网请求必须保留——WifiNetworkSpecifier 的连接只在请求存活期间有效，
     *    释放即断开相机热点（原 bug：这里 return 时连状态都不清，残留 Success 导致二次扫码失效）。
     * 2. 但 UI 状态一律回到 Idle，保证下次打开弹窗是干净起点。
     */
    fun release() {
        val wasSuccess = _state.value is QrScanUiState.Success
        _state.value = QrScanUiState.Idle
        reported = false
        // 成功态保留配网请求；其余（Idle/Connecting/Error）回收残留的 pending 请求
        if (!wasSuccess) wifiManager.releaseNetworkRequest()
    }

    companion object {
        private const val TAG = "qrscan"
    }
}
