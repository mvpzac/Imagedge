package com.imagedge.camera.data.model

import com.imagedge.camera.data.remote.ChannelType

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相机连接状态
 *     version: 1.0
 * </pre>
 */

/** 连接阶段 */
enum class ConnectionPhase {
    DISCONNECTED,   // 未连接
    CONNECTING,     // 连接中
    CONNECTED,      // 已连接
    ERROR           // 连接失败
}

/** 连接状态（含通道类型与错误信息） */
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val channelType: ChannelType? = null,
    val cameraModel: String? = null,
    val errorMessage: String? = null
) {
    val isConnected: Boolean get() = phase == ConnectionPhase.CONNECTED
}
