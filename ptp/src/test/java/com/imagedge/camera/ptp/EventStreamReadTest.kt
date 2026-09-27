package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 事件流读取结局的判定。分不清「安静」和「错位」，事件通道会静默坏死到重连为止。
 * </pre>
 */
class EventStreamReadTest {

    @Test
    fun `a timeout that consumed no bytes is idle, not broken`() {
        // 相机 3s 没有推送任何东西：这是正常情况，流仍然对齐，继续等
        assertEquals(
            EventReadOutcome.IDLE,
            classifyEventRead(timedOut = true, bytesConsumed = 0L, malformed = false)
        )
    }

    @Test
    fun `a timeout after part of a packet means the stream is desynced`() {
        // 核心缺陷：读到一半超时，socket 已经吃掉了半个包，
        // 下一次读从中途开始。之前这里返回 null，与「没有事件」完全无法区分，
        // 于是事件通道静默坏死，直到用户重连。
        assertEquals(
            EventReadOutcome.DESYNCED,
            classifyEventRead(timedOut = true, bytesConsumed = 5L, malformed = false)
        )
    }

    @Test
    fun `a malformed packet means the stream is desynced`() {
        assertEquals(
            EventReadOutcome.DESYNCED,
            classifyEventRead(timedOut = false, bytesConsumed = 0L, malformed = true)
        )
    }

    @Test
    fun `a cleanly read event is an event`() {
        assertEquals(
            EventReadOutcome.EVENT,
            classifyEventRead(timedOut = false, bytesConsumed = 24L, malformed = false)
        )
    }

    @Test
    fun `a control packet that needs no delivery is merely handled`() {
        // ProbeRequest 之类：读到了完整包并已回应，只是不必上抛给调用方
        assertEquals(
            EventReadOutcome.HANDLED,
            classifyEventRead(timedOut = false, bytesConsumed = 12L, malformed = false, isEvent = false)
        )
    }

    @Test
    fun `a desynced read must be distinguishable from idle by the caller`() {
        // 这两条同时成立就说明旧实现（两者都返回 null）必然混淆
        val idle = classifyEventRead(timedOut = true, bytesConsumed = 0L, malformed = false)
        val broken = classifyEventRead(timedOut = true, bytesConsumed = 5L, malformed = false)

        assertTrue(idle != broken)
        assertEquals(EventReadOutcome.IDLE, idle)
        assertEquals(EventReadOutcome.DESYNCED, broken)
    }

    @Test
    fun `only a desynced stream requires a reconnect`() {
        assertEquals(false, EventReadOutcome.IDLE.requiresReconnect)
        assertEquals(false, EventReadOutcome.EVENT.requiresReconnect)
        assertEquals(false, EventReadOutcome.HANDLED.requiresReconnect)
        assertEquals(true, EventReadOutcome.DESYNCED.requiresReconnect)
    }
}
