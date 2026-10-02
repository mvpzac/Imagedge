package com.imagedge.camera.data.remote

import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.remote.wifi.CameraWifiManager
import com.imagedge.camera.liveview.LiveViewClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : LiveView 裸流仓库（v2 瘦身，前身 SonyApiRepository）。
 *              历史上这个类还承载索尼 Web API（JSON-RPC 拍照/参数），但本工程
 *              目标机型根本没有该服务（ISO/光圈/快门早已改走 PTP DeviceProp），
 *              2026-08-29 连同 webapi 模块的 SonyApiClient/SsdpDiscovery 一并清除，
 *              只保留遥控页在用的裸 LiveView 流。
 *              地址优先取相机经 PTP 下发的那个，取不到才按已知约定自建——
 *              端口与路径写在代码里只是没有地方问相机，不是相机的约定。
 *     version: 3.0
 * </pre>
 */
@Singleton
class LiveViewRepository @Inject constructor(
    private val wifiManager: CameraWifiManager,
    private val ptpChannel: PtpChannel
) {

    private val liveViewClient = LiveViewClient()

    /**
     * 实时取景 JPEG 帧流（cold flow：collect 时连接，取消时断开）。
     */
    fun liveViewFrames(): Flow<ByteArray> = flow {
        val url = resolveLiveViewUrl()
        AppLog.i(TAG, "LiveView 连接：$url")
        liveViewClient.stream(url).collect { emit(it) }
    }

    /**
     * 取景地址：先问相机，失败再自建。
     *
     * 问相机那一步要发一次「读全部设备属性」，代价不小，而相机给出的地址
     * 在一次连接内不会变，所以只问一次并缓存到断开为止。
     */
    private suspend fun resolveLiveViewUrl(): String {
        ptpChannel.readLiveViewUrl()?.let { cameraSupplied ->
            AppLog.i(TAG, "取景地址取自相机上报：$cameraSupplied")
            return cameraSupplied
        }
        val gateway = wifiManager.getCurrentGatewayIp()
            ?: throw IllegalStateException("未找到相机网关，请先连接相机热点")
        // ！！注意：路径后的查询串是索尼必需的格式协商参数（分辨率/媒体类型声明），
        // 不是冗余乱码——缺了它相机直接返回错误码（表现为连接即断，FileNotFoundException）。
        // 编码原文：?!1234!*:*:image/jpeg:*!!!!!
        return "http://$gateway:$FALLBACK_PORT/liveviewstream" +
            "?%211234%21%2a%3a%2a%3aimage%2fjpeg%3a%2a%21%21%21%21%21"
    }

    companion object {
        private const val TAG = "liveview"

        /** 相机不上报取景地址时的兜底端口 */
        private const val FALLBACK_PORT = 60152
    }
}