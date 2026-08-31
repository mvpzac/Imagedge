package com.imagedge.camera.upnp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : UPnP/SOAP 配置（"发送到智能手机"模式，端口 64321）
 *     version: 1.0
 * </pre>
 */

/**
 * UPnP 服务配置常量
 */
object UpnpConfig {

    /** UPnP 服务端口 */
    const val PORT = 64321

    /** 服务描述文档 */
    const val DEVICE_DESC = "DmsDescPush.xml"

    /** SOAP 控制端点 */
    const val CONTROL_PATH = "upnp/control"

    /** 内容目录服务 */
    const val SERVICE_CONTENT_DIRECTORY = "ContentDirectory"

    /** 推送列表服务（传输开始/结束） */
    const val SERVICE_X_PUSH_LIST = "XPushList"
}
