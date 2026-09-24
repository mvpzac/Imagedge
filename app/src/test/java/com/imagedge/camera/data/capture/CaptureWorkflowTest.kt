package com.imagedge.camera.data.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 拍摄状态机与间隔调度验收（T3）：取消不拍照、间隔不重叠、按键必释放、重试有界
 * </pre>
 */
class CaptureWorkflowTest {

    private fun job(
        phase: CapturePhase = CapturePhase.TRIGGERING,
        held: Set<HeldKey> = emptySet(),
        countdownEndsAt: Long = 0L
    ) = CaptureJob(
        id = 1,
        route = CaptureRoute.BLE,
        phase = phase,
        startedAt = 0L,
        countdownEndsAt = countdownEndsAt,
        heldKeys = held
    )

    // ── 倒计时 ───────────────────────────────────────────────────────

    @Test
    fun `a countdown job has not sent anything yet`() {
        val started = CaptureMachine.beginWithCountdown(
            id = 1, route = CaptureRoute.BLE, now = 1000L, countdownMs = 3000L
        )

        requireNotNull(started)
        assertEquals(CapturePhase.COUNTING_DOWN, started.phase)
        assertFalse("倒计时阶段不能算已确认", started.confirmed)
        assertTrue("倒计时期间没有任何键被按住", started.heldKeys.isEmpty())
    }

    @Test
    fun `cancelling the countdown triggers no capture and releases nothing`() {
        // 验收「倒计时取消不拍照」：这条只有在取消发生在 TRIGGERING 之前才成立，
        // 所以状态机必须能区分「取消了一次拍摄」和「没拍」
        val started = requireNotNull(
            CaptureMachine.beginWithCountdown(1, CaptureRoute.BLE, now = 0L, countdownMs = 3000L)
        )
        val mid = CaptureMachine.tick(started, now = 2000L)
        val step = CaptureMachine.cancel(mid)

        assertEquals(CapturePhase.CANCELLED, step.job.phase)
        assertEquals(CaptureFailure.COUNTDOWN_CANCELLED, step.job.failure)
        assertTrue("没按过键就不该有释放命令", step.releases.isEmpty())
        assertFalse("倒计时取消绝不能被记成已拍摄", step.job.confirmed)
    }

    @Test
    fun `the countdown fires exactly once the time is up`() {
        val started = requireNotNull(
            CaptureMachine.beginWithCountdown(1, CaptureRoute.BLE, now = 0L, countdownMs = 3000L)
        )

        assertEquals(CapturePhase.COUNTING_DOWN, CaptureMachine.tick(started, 2999L).phase)
        assertEquals(CapturePhase.TRIGGERING, CaptureMachine.tick(started, 3000L).phase)
    }

    @Test
    fun `a zero countdown starts already triggered`() {
        val started = CaptureMachine.begin(id = 7, route = CaptureRoute.PTP, now = 500L)

        assertEquals(CapturePhase.TRIGGERING, started.phase)
        assertEquals(500L, started.countdownEndsAt)
    }

    // ── 按键释放 ─────────────────────────────────────────────────────

    @Test
    fun `shutter is released before the half press is lifted`() {
        // alpharemote 的顺序：全按回位(0x08) → 再抬半按(0x06)。写反会打断拍摄
        val held = job(held = setOf(HeldKey.HALF_PRESS, HeldKey.FULL_PRESS))

        assertEquals(listOf(HeldKey.FULL_PRESS, HeldKey.HALF_PRESS), held.outstandingReleases)
    }

    @Test
    fun `every termination path hands back the keys it was holding`() {
        val held = job(held = setOf(HeldKey.HALF_PRESS, HeldKey.FULL_PRESS))

        listOf(
            CaptureMachine.cancel(held),
            CaptureMachine.onDisconnected(held),
            CaptureMachine.onNotConfirmed(held),
            CaptureMachine.onNoFile(held),
            CaptureMachine.onRefused(held)
        ).forEach { step ->
            assertTrue(
                "${step.job.phase} 必须交出待释放的按键",
                step.releases.containsAll(listOf(HeldKey.FULL_PRESS, HeldKey.HALF_PRESS))
            )
        }
    }

    @Test
    fun `no terminal state can leave a key held`() {
        // 这是状态机的核心不变量：终态 + heldKeys 非空 = 相机被留在按下的状态
        val held = job(held = setOf(HeldKey.FULL_PRESS))

        listOf(
            CaptureMachine.cancel(held).job,
            CaptureMachine.onDisconnected(held).job,
            CaptureMachine.onNotConfirmed(held).job,
            CaptureMachine.onNoFile(held).job,
            CaptureMachine.onRefused(held).job,
            CaptureMachine.onFileSeen(held).job
        ).forEach { job ->
            assertTrue("${job.phase} 是终态，不该还按着键", job.phase.isTerminal)
            assertTrue("${job.phase} 的 heldKeys 必须为空", job.heldKeys.isEmpty())
        }
    }

    @Test
    fun `a job that never pressed a key terminates without releasing`() {
        val step = CaptureMachine.cancel(job(phase = CapturePhase.COUNTING_DOWN, countdownEndsAt = 99L))

        assertTrue(step.releases.isEmpty())
        assertEquals(CapturePhase.CANCELLED, step.job.phase)
    }

    // ── 「已发送」不等于「已拍摄」 ────────────────────────────────────

    @Test
    fun `sending the shutter command is not a confirmed capture`() {
        val triggered = CaptureMachine.onHalfPressSent(job())
        val pressed = CaptureMachine.onShutterPressSent(triggered)

        assertFalse("命令发出后仍不算已拍摄", pressed.confirmed)
        assertEquals(setOf(HeldKey.HALF_PRESS, HeldKey.FULL_PRESS), pressed.heldKeys)
    }

    @Test
    fun `only camera feedback confirms the shot`() {
        val pressed = CaptureMachine.onShutterPressSent(CaptureMachine.onHalfPressSent(job()))

        val confirmed = CaptureMachine.onCameraConfirmed(pressed)
        assertTrue(confirmed.confirmed)
        assertEquals(CapturePhase.CONFIRMED, confirmed.phase)
    }

    @Test
    fun `a timeout before confirmation is reported as unconfirmed`() {
        val pressed = CaptureMachine.onShutterPressSent(CaptureMachine.onHalfPressSent(job()))

        val step = CaptureMachine.onNotConfirmed(pressed)

        assertEquals(CapturePhase.FAILED, step.job.phase)
        assertEquals(CaptureFailure.NOT_CONFIRMED, step.job.failure)
        assertFalse("没确认就不能算拍成", step.job.confirmed)
        assertTrue("但按住的键仍要释放", step.releases.isNotEmpty())
    }

    @Test
    fun `release commands complete the wait-for-file phase`() {
        val confirmed = CaptureMachine.onCameraConfirmed(
            CaptureMachine.onShutterPressSent(job())
        )

        val waiting = CaptureMachine.onKeysReleased(confirmed)

        assertEquals(CapturePhase.AWAITING_FILE, waiting.phase)
        assertTrue(waiting.heldKeys.isEmpty())
        assertTrue("等文件阶段仍算活跃，不能被间隔调度当成可起拍", waiting.phase.isActive)
    }

    @Test
    fun `a confirmed shot without a file is not completed`() {
        val waiting = CaptureMachine.onKeysReleased(
            CaptureMachine.onCameraConfirmed(CaptureMachine.onShutterPressSent(job()))
        )

        val step = CaptureMachine.onNoFile(waiting)

        assertEquals(CaptureFailure.NO_FILE, step.job.failure)
        assertTrue("快门确实触发过，这一点要保留在 confirmed 上", step.job.confirmed)
        assertEquals(CapturePhase.FAILED, step.job.phase)
    }

    // ── 不重叠 ───────────────────────────────────────────────────────

    @Test
    fun `a busy slot blocks the next start no matter how much time passed`() {
        assertFalse(
            IntervalScheduler.shouldStart(now = 10_000_000L, lastFinishedAt = 0L, intervalMs = 1000L, busy = true)
        )
    }

    @Test
    fun `the first shot of a series needs no previous completion`() {
        assertTrue(IntervalScheduler.shouldStart(now = 0L, lastFinishedAt = null, intervalMs = 5000L, busy = false))
    }

    @Test
    fun `thirty interval shots never overlap even when writing is slower than the interval`() {
        // 验收「连续 30 次间隔无重叠」。故意让写卡耗时(2500ms)远大于间隔(1000ms)，
        // 并按 100ms 推进时间：任何按时钟排期、不看忙闲的实现都会在这里撞上还在忙的设备
        val interval = 1000L
        val writeLatency = 2500L
        val windows = simulateInterval(interval, writeLatency, shots = 30)

        assertEquals(30, windows.size)
        windows.zip(windows.drop(1)).forEachIndexed { index, (current, next) ->
            assertTrue(
                "第 ${index + 1} 张与第 ${index + 2} 张重叠：$current vs $next",
                next.first > current.last
            )
        }
        // 慢写卡时节奏由「上次完成 + 间隔」决定，而不是被间隔拖着走：
        // 写卡 2500ms + 间隔 1000ms ⇒ 实际空档应约等于 3500ms
        windows.zip(windows.drop(1)).forEach { (a, b) ->
            assertTrue(
                "间隔必须从上次完成起算，实测空档 ${(b.first - a.last)}ms",
                b.first - a.last >= interval
            )
        }
    }

    @Test
    fun `a clock-only scheduler would overlap, which is what the busy guard prevents`() {
        // 反证：同样的参数下，只按墙上时钟排期（不看 busy）确实会重叠。
        // 没有这条，上面的测试可能只是因为时间步进够慢而恰好通过
        val interval = 1000L
        val writeLatency = 2500L
        val naive = mutableListOf<LongRange>()
        var now = 0L
        var lastStarted: Long? = null
        while (naive.size < 30 && now < 200_000L) {
            if (lastStarted == null || now - lastStarted >= interval) {
                naive += now..(now + writeLatency)
                lastStarted = now
            }
            now += 100
        }

        assertTrue(
            "按时钟排期必然重叠——这正是「卡忙时不叠加快门」要防的情况",
            naive.zip(naive.drop(1)).any { (a, b) -> b.first <= a.last }
        )
    }

    /** 按 100ms 步进驱动一轮间隔拍摄，返回每张的占用时间窗 */
    private fun simulateInterval(interval: Long, writeLatency: Long, shots: Int): List<LongRange> {
        val windows = mutableListOf<LongRange>()
        var now = 0L
        var lastFinished: Long? = null
        var activeUntil = 0L
        var id = 0

        while (windows.size < shots && now < 500_000L) {
            val busy = now < activeUntil
            if (IntervalScheduler.shouldStart(now, lastFinished, interval, busy)) {
                id++
                val started = requireNotNull(
                    CaptureMachine.begin(id, CaptureRoute.BLE, now, attemptIndex = id)
                )
                assertTrue("起拍后任务必须处于活跃态", CaptureMachine.isBusy(started))
                val confirmed = CaptureMachine.onCameraConfirmed(
                    CaptureMachine.onShutterPressSent(CaptureMachine.onHalfPressSent(started))
                )
                val waiting = CaptureMachine.onKeysReleased(confirmed)
                val done = CaptureMachine.onFileSeen(waiting).job
                assertTrue(done.phase.isTerminal)

                val finished = now + writeLatency
                windows += now..finished
                activeUntil = finished
                lastFinished = finished
            }
            now += 100L
        }
        return windows
    }

    @Test
    fun `the wait before the next shot is measured from the previous completion`() {
        assertEquals(0L, IntervalScheduler.delayUntilNext(now = 5000L, lastFinishedAt = null, intervalMs = 2000L))
        assertEquals(1500L, IntervalScheduler.delayUntilNext(now = 2500L, lastFinishedAt = 1000L, intervalMs = 3000L))
        assertEquals(0L, IntervalScheduler.delayUntilNext(now = 9000L, lastFinishedAt = 1000L, intervalMs = 3000L))
    }

    @Test
    fun `countdown time is part of the job not of the interval`() {
        // 3 秒倒计时 + 2 秒间隔：两张之间的空档是「倒计时 + 间隔」，
        // 若把倒计时算进调度会让实际间隔短一档
        val started = requireNotNull(
            CaptureMachine.beginWithCountdown(1, CaptureRoute.BLE, now = 0L, countdownMs = 3000L)
        )

        assertTrue(CaptureMachine.isBusy(started))
        assertEquals(CapturePhase.COUNTING_DOWN, started.phase)
    }

    // ── 不自动重试 ───────────────────────────────────────────────────

    @Test
    fun `a failed capture is final and frees the slot without scheduling anything`() {
        // 「断线不无限重试」靠的是根本不存在自动重试：失败必须直接落终态并释放调度位，
        // 任何「失败后再来一次」都得是用户显式发起的
        val pressed = CaptureMachine.onShutterPressSent(CaptureMachine.onHalfPressSent(job()))

        val failed = CaptureMachine.onDisconnected(pressed).job

        assertTrue("失败必须直接落终态", failed.phase.isTerminal)
        assertFalse("终态不占调度位，间隔循环不会因此再起一张", CaptureMachine.isBusy(failed))
        assertTrue(failed.heldKeys.isEmpty())
    }

    @Test
    fun `retryable is not a hidden auto-retry switch`() {
        // retryable 只用于界面措辞（「请检查后重试」vs「已取消」），
        // 状态机里没有任何地方读它来自动重发命令
        assertTrue(CaptureFailure.DISCONNECTED.retryable)
        assertTrue(CaptureFailure.NOT_CONFIRMED.retryable)
        assertFalse("用户取消不该被提示成可重试", CaptureFailure.CANCELLED.retryable)
        assertFalse(CaptureFailure.COUNTDOWN_CANCELLED.retryable)
    }

    // ── 输入边界 ─────────────────────────────────────────────────────

    @Test
    fun `a negative countdown is rejected instead of silently clamped`() {
        // 负数倒计时会被算成「已经到点」，等于绕过倒计时直接拍——这种输入必须拒收
        assertNull(CaptureMachine.beginWithCountdown(1, CaptureRoute.BLE, now = 0L, countdownMs = -1L))
    }

    @Test
    fun `waiting for the file still counts as busy`() {
        // 间隔调度最容易漏掉的就是这一段：快门已回位、文件还没落，此时起下一张就是叠加
        val waiting = CaptureMachine.onKeysReleased(
            CaptureMachine.onCameraConfirmed(CaptureMachine.onShutterPressSent(job()))
        )

        assertTrue(CaptureMachine.isBusy(waiting))
        assertFalse(waiting.phase.isTerminal)
        assertTrue(
            "终态一律不忙",
            listOf(
                CaptureMachine.onFileSeen(waiting).job,
                CaptureMachine.cancel(waiting).job,
                CaptureMachine.onDisconnected(waiting).job
            ).none { CaptureMachine.isBusy(it) }
        )
    }
}
