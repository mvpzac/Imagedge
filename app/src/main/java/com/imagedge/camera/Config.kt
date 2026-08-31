package com.imagedge.camera

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 全局配置常量（规范 3.4：常量 CONSTANT_CASE）
 *     version: 1.0
 * </pre>
 */
object Config {

    /** 应用名 */
    const val APP_NAME = "Imagedge"

    /** 相机保存目录（系统相册可见） */
    const val MEDIA_SUB_DIR = "Imagedge"

    /** PTP/IP 协议端口（ISO 15740） */
    const val PTP_PORT = 15740

    /** UPnP 服务端口（"发送到智能手机"模式） */
    const val UPnP_PORT = 64321

    /** 网络超时（毫秒） */
    const val NETWORK_TIMEOUT_MS = 10_000L
}
