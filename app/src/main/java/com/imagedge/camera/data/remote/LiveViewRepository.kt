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
 *              历史上这个类还承载索尼 Web API（JSON-RPC 拍照/参数），但 ZV-E10
 *              根本没有该服务（ISO/光圈/快门早已改走 PTP DeviceProp），
 *              2026-08-29 连同 webapi 模块的 SonyApiClient/SsdpDiscovery 一并清除，
 *              只保留遥控页在用的 60152 裸 LiveView 流。
 *              URL 不再依赖 SSDP 发现：两种连接模式下相机都开放 60152 固定端口，
 *              直接用网关 IP 构造即可。
 *     version: 2.1
 * </pre>
 */
@Singleton
class LiveViewRepository @Inject constructor(
    private val wifiManager: CameraWifiManager
) {

    private val liveViewClient = LiveViewClient()

    /**
     * 实时取景 JPEG 帧流（cold flow：collect 时连接，取消时断开）。
     * 「电脑遥控」/「智能手机连接」两种模式下相机都开放 60152 裸 LiveView 流。
     */
    fun liveViewFrames(): Flow<ByteArray> = flow {
        val gateway = wifiManager.getCurrentGatewayIp()
            ?: throw IllegalStateException("未找到相机网关，请先连接相机热点")
        // ！！注意：路径后的查询串是索尼必需的格式协商参数（分辨率/媒体类型声明），
        // 不是冗余乱码——缺了它相机直接返回错误码（表现为连接即断，FileNotFoundException）。
        // 编码原文：?!1234!*:*:image/jpeg:*!!!!!
        val url = "http://$gateway:60152/liveviewstream" +
            "?%211234%21%2a%3a%2a%3aimage%2fjpeg%3a%2a%21%21%21%21%21"
        AppLog.i(TAG, "LiveView 连接：$url（网关=$gateway）")
        liveViewClient.stream(url).collect { emit(it) }
    }

    companion object {
        private const val TAG = "liveview"
    }
}
