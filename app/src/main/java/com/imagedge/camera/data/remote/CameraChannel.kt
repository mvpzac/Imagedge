package com.imagedge.camera.data.remote

import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.CapabilityState
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
 *     version: 1.2 —— 通道自我声明身份与遥控拍摄能力（能力模型 T0）
 * </pre>
 */

/** 通道类型 */
enum class ChannelType { PTP_IP, UPNP }

/** 通道类型 → 能力模型里的传输方式 */
fun ChannelType.toTransport(): CameraTransport = when (this) {
    ChannelType.PTP_IP -> CameraTransport.PTP_IP
    ChannelType.UPNP -> CameraTransport.UPNP
}

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

    /**
     * 相机固件版本（PTP GetDeviceInfo 的 DeviceVersion）。
     *
     * 能力必须按「型号 + 固件」归档：同型号不同固件的菜单与属性表可能不同。
     * 通道拿不到时为空串（此时快照的固件维度记为未知）。
     */
    val deviceFirmware: String
        get() = ""

    /**
     * 当前通道 + 当前功能模式下，遥控拍摄处于哪一态。
     *
     * 能力差异不靠「抛异常再由调用方捕获」表达，而是由通道显式声明，
     * 让能力模型能在下发命令之前就判定可用性。
     *
     * **为什么不是 Boolean**：`false` 同时装得下两件不同的事——「这条通道结构性没有」
     * 与「这个模式我们没实测过」。前者可以明确说不支持，后者只能说未知；
     * 把未知说成不支持，用户的下一步就是重连，而重连会让相机端句柄全部失效
     * （docs/HANDOFF.md 已知坑 14）。所以这里返回三态。
     */
    val captureSupport: CapabilityState
        get() = CapabilityState.UNSUPPORTED

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

    /**
     * 增量浏览媒体：分批回调，边枚举边返回。
     *
     * 整卡模式下一次性枚举上千个对象会长时间占用 PTP 通道、且 UI 全程空白，
     * 故提供分批回调能力，让上层边收到边渲染。
     *
     * @param onBatch 每枚举完一批调用一次（批次大小由实现决定，约 20）
     * @return 返回的总条数
     */
    suspend fun listMediaIncremental(onBatch: suspend (List<MediaItem>) -> Unit): Int {
        val items = listMedia()
        if (items.isNotEmpty()) onBatch(items)
        return items.size
    }

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
