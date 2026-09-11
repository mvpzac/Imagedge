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

/**
 * 连接 / IO 错误。
 *
 * 继承 [java.io.IOException] 而非普通 Exception：本类是「socket 已不可用」的语义，
 * 上层据此判定可自愈（`PtpChannel.listMediaIncremental` 的 recoverable 集合是
 * `IOException || PtpResponseException`）。若只是普通 Exception，超时自愈
 * （forceClose 后 socket 字段为 null）与保活判死后的重连都会被跳过，
 * 用户只能手动重连。
 */
class PtpIoException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
