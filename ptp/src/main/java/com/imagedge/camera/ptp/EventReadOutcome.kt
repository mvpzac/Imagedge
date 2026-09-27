package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 事件流一次读取的结局——把「安静」和「错位」分开
 * </pre>
 */

/** 一次事件流读取的结果。[requiresReconnect] 为真时，事件通道已经不可信，必须重开。 */
enum class EventReadOutcome(val requiresReconnect: Boolean) {
    /** 零字节超时：相机这段时间没有推送，流仍对齐，继续等 */
    IDLE(false),

    /** 完整读到一个相机事件 */
    EVENT(false),

    /** 完整读到控制包（ProbeRequest 等）并已处理，不必上抛 */
    HANDLED(false),

    /** 读到一半超时，或包体畸形——socket 已从中途开始，必须重连 */
    DESYNCED(true)
}

/**
 * 判定一次事件流读取的结局。
 *
 * 关键在 [bytesConsumed]：它必须是**这一次尝试**内从流上读走的字节数。
 * 超时时它为 0，说明一个包都没开始读，流还干净；大于 0 说明包头或包体已经被
 * 读掉一半，下一次读会从中间开始。
 *
 * 此前 `readEvent()` 对这两种情况一律返回 null，事件通道会静默坏死——
 * 相册不再刷新、按键不再触发，而界面和日志都看不出任何异常。
 */
/** 一次读取的结局 + 有效载荷。[last] 仅在 [EventReadOutcome.EVENT] 时非空。 */
data class EventReadResult(val outcome: EventReadOutcome, val last: Event?)

internal fun classifyEventRead(
    timedOut: Boolean,
    bytesConsumed: Long,
    malformed: Boolean,
    isEvent: Boolean = true,
): EventReadOutcome = when {
    malformed -> EventReadOutcome.DESYNCED
    timedOut && bytesConsumed > 0L -> EventReadOutcome.DESYNCED
    timedOut -> EventReadOutcome.IDLE
    isEvent -> EventReadOutcome.EVENT
    else -> EventReadOutcome.HANDLED
}

/**
 * 透传读操作并累计字节数。
 *
 * 只需要计数这一个关注点，所以刻意只重写 [read]——[read] 已经是 InputStream
 * 所有读取路径的收口，[readBytes]/[skip]/缓冲填充最终都会走到它。
 */
internal class CountingInputStream(
    private val delegate: java.io.InputStream,
    private val onBytes: (Int) -> Unit,
) : java.io.FilterInputStream(delegate) {
    override fun read(): Int = delegate.read().also { if (it >= 0) onBytes(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) onBytes(it) }
}
