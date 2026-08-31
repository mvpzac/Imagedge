package com.imagedge.camera.data.remote

import com.imagedge.camera.data.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.OutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相机传输通道抽象（PTP/IP 与 UPnP 双通道统一接口）
 *     version: 1.1 —— 新增连接状态流与内容变化事件（带默认实现，弱通道可忽略）
 * </pre>
 */

/** 通道类型 */
enum class ChannelType { PTP_IP, UPNP }

/** 通道连接状态 */
enum class ChannelConnectionState { DISCONNECTED, CONNECTED }

/**
 * 相机传输通道统一接口
 *
 * 双通道能力差异：
 * - PTP/IP：RAW / 视频 / 全存储 / 缩略图
 * - UPnP：JPEG 目录下载（"发送到智能手机"模式）
 */
interface CameraChannel {

    val channelType: ChannelType

    /** 相机型号（连接后可用） */
    val deviceModel: String

    /** 连接状态流（PTP 通道由保活/事务自愈维护；默认实现恒为 DISCONNECTED） */
    val connectionState: StateFlow<ChannelConnectionState>
        get() = MutableStateFlow(ChannelConnectionState.DISCONNECTED)

    /**
     * 相机内容变化事件（选片推送 / 内容集重建等，收到后应立即刷新媒体列表）。
     * 默认实现无事件（弱通道无事件流能力）。
     */
    val contentEvents: Flow<Unit>
        get() = emptyFlow()

    /** 建立连接 */
    suspend fun connect(host: String)

    /** 断开连接 */
    suspend fun disconnect()

    /** 浏览全部媒体 */
    suspend fun listMedia(): List<MediaItem>

    /** 获取缩略图（无缩略图返回 null） */
    suspend fun getThumbnail(item: MediaItem): ByteArray?

    /** 流式下载媒体到输出流 */
    suspend fun download(item: MediaItem, output: OutputStream, onProgress: (Long, Long) -> Unit)

    /**
     * 分块下载媒体到输出流（断点续传用）：从 [offset] 字节起最多取 [maxBytes] 字节。
     * 默认实现不支持（弱通道如 UPnP 抛异常，调用方应回退整文件下载）。
     */
    suspend fun downloadRange(
        item: MediaItem,
        output: OutputStream,
        offset: Long,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): Unit = throw UnsupportedOperationException("当前通道不支持分块下载")

    /** 触发快门（遥控拍摄）；返回对象句柄，通道不支持时抛异常 */
    suspend fun takePicture(): Long = throw UnsupportedOperationException("当前通道不支持遥控拍摄")
}
