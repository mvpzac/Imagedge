package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP/IP 协议异常体系
 *     version: 1.0
 * </pre>
 */

/** 协议解析错误（包格式非法 / 数据不足） */
class PtpMalformedPacketException(message: String) : Exception(message)

/** 相机返回错误响应码 */
class PtpResponseException(val responseCode: Int, message: String) : Exception(message)

/** 连接 / IO 错误 */
class PtpIoException(message: String, cause: Throwable? = null) : Exception(message, cause)
