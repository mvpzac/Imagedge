package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 分析节流门验收（T2）：停用零分析、5Hz 上限、重新打开立刻放行
 *     version: 1.0
 * </pre>
 */
class FrameGateTest {

    private var clock = 0L
    private fun gate(interval: Long = FrameGate.DEFAULT_INTERVAL_MS) =
        FrameGate(minIntervalMs = interval, nowMs = { clock })

    @Test
    fun `a disabled gate never analyses a frame`() {
        val g = gate()

        repeat(20) {
            clock += 1_000
            assertFalse("停用时要零分析，不是少分析", g.admit())
        }
    }

    @Test
    fun `the first frame after enabling is admitted at once`() {
        val g = gate()
        clock = 5_000
        g.enabled = true

        assertTrue("用户刚打开辅助，不该再等一个周期", g.admit())
    }

    @Test
    fun `a 20fps burst admits at most five frames per second`() {
        val g = gate()
        g.enabled = true
        var admitted = 0

        // 一秒的 20fps 帧
        repeat(20) {
            clock += 50
            if (g.admit()) admitted++
        }

        assertEquals("5Hz 上限：一秒最多 5 帧被分析", 5, admitted)
    }

    @Test
    fun `frames inside the window are dropped rather than queued`() {
        val g = gate(interval = 200)
        g.enabled = true

        assertTrue(g.admit())     // t=0
        clock = 50
        assertFalse(g.admit())    // 窗口内：丢掉，不排队
        clock = 199
        assertFalse(g.admit())
        clock = 200
        assertTrue("到点的那一帧立刻被分析——它是此刻最新的画面", g.admit())
    }

    @Test
    fun `disabling mid-stream stops everything`() {
        val g = gate()
        g.enabled = true
        assertTrue(g.admit())

        g.enabled = false
        clock += 10_000

        assertFalse(g.admit())
    }
}
