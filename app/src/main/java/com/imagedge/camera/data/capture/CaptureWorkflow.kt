package com.imagedge.camera.data.capture

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 拍摄任务状态机与间隔调度（T3）——全部纯函数，不碰协程也不碰相机
 *     version: 1.0
 * </pre>
 */

/**
 * 拍摄任务的阶段。
 *
 * [COUNTING_DOWN] 单独存在是刻意的：倒计时期间**一个命令都没发出去**，
 * 所以取消倒计时的正确结果是「什么都没发生」，不是「取消一次拍摄」。
 * [TRIGGERING] 与 [CONFIRMED] 的区别是本文件最重要的一条：命令发出去了不等于拍下来了。
 */
enum class CapturePhase {
    IDLE,

    /** 本地倒计时中，尚未向相机发出任何命令 */
    COUNTING_DOWN,

    /** 命令已发出，但相机还没确认 */
    TRIGGERING,

    /** 相机已确认快门动作（BLE ff02 shutter=true 或 PTP CaptureComplete） */
    CONFIRMED,

    /** 等相机写卡 / 等内容事件 */
    AWAITING_FILE,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /** 是否还允许向相机发命令 */
    val isActive: Boolean get() = !isTerminal && this != IDLE
}

/** 两段式快门当前按住的键（BLE：0x07 半按 / 0x09 全按） */
enum class HeldKey { HALF_PRESS, FULL_PRESS }

/** 触发通道 */
enum class CaptureRoute { BLE, PTP }

/** 终止原因；[retryable] 决定自动重试预算是否消耗后仍可能再来 */
enum class CaptureFailure(val retryable: Boolean) {
    /** 相机没确认快门：命令发了但收不到反馈 */
    NOT_CONFIRMED(true),

    /** 通道断开 */
    DISCONNECTED(true),

    /** 用户取消 */
    CANCELLED(false),

    /** 倒计时期间被取消：根本没发过命令，与 CANCELLED 区分开，提示才不会说谎 */
    COUNTDOWN_CANCELLED(false),

    /** 相机确认了快门但没等到文件 */
    NO_FILE(true),

    /** 相机侧明确拒绝 */
    REFUSED(false)
}

/**
 * 一次拍摄任务。
 *
 * 不变量（由 [CaptureMachine] 保证、并被单测钉死）：
 * 1. **任何终态的 [heldKeys] 必须为空**——离开时还按着键，相机就停在一个说不清的状态；
 * 2. [confirmed] 只能由相机反馈置真，发送成功不算；
 * 3. 「同一时刻只有一个活跃任务」由调用方用 [CaptureMachine.isBusy] 把守：
 *    忙时一律不起拍，宁可少拍一张也不叠加快门。
 */
data class CaptureJob(
    val id: Int,
    val route: CaptureRoute,
    val phase: CapturePhase,
    val startedAt: Long,
    /** 倒计时结束时刻；等于 startedAt 表示无倒计时 */
    val countdownEndsAt: Long,
    val heldKeys: Set<HeldKey> = emptySet(),
    val confirmed: Boolean = false,
    /** 第几张（1 起），供间隔拍摄与日志定位 */
    val attemptIndex: Int = 1,
    val failure: CaptureFailure? = null
) {
    val isTerminal: Boolean get() = phase.isTerminal

    /** 还剩哪些键必须释放。顺序即发送顺序：先快门回位，再抬半按 */
    val outstandingReleases: List<HeldKey>
        get() = buildList {
            if (HeldKey.FULL_PRESS in heldKeys) add(HeldKey.FULL_PRESS)
            if (HeldKey.HALF_PRESS in heldKeys) add(HeldKey.HALF_PRESS)
        }

    /** 倒计时是否仍在走（此阶段取消不会留下任何相机侧痕迹） */
    val countingDown: Boolean get() = phase == CapturePhase.COUNTING_DOWN
}

/** 一次状态迁移：新任务 + 此刻必须发给相机的释放命令 */
data class CaptureStep(
    val job: CaptureJob,
    val releases: List<HeldKey> = emptyList()
)

/**
 * 状态机。每个函数都是纯的，返回 [CaptureStep] 而不是自己发命令——
 * 这样「取消/断线/离页必须释放按键」是一条**可测试**的契约，而不是散落在协程里的约定。
 */
object CaptureMachine {

    /** 无倒计时的便捷起点：直接进入 TRIGGERING */
    fun begin(id: Int, route: CaptureRoute, now: Long, attemptIndex: Int = 1): CaptureJob =
        CaptureJob(
            id = id,
            route = route,
            phase = CapturePhase.TRIGGERING,
            startedAt = now,
            countdownEndsAt = now,
            attemptIndex = attemptIndex
        )

    /**
     * 开始一次带倒计时的拍摄。倒计时为负时返回 null。
     *
     * 负数不能钳成 0：那等于把用户设的倒计时悄悄变成「立刻拍」，
     * 而这条路径的下一步就是往相机发快门。
     * 「不排队、不叠加」不在这里判——那是调用方用 [isBusy] 与 [IntervalScheduler] 决定的事。
     */
    fun beginWithCountdown(
        id: Int,
        route: CaptureRoute,
        now: Long,
        countdownMs: Long,
        attemptIndex: Int = 1
    ): CaptureJob? {
        if (countdownMs < 0L) return null
        return CaptureJob(
            id = id,
            route = route,
            phase = if (countdownMs > 0L) CapturePhase.COUNTING_DOWN else CapturePhase.TRIGGERING,
            startedAt = now,
            countdownEndsAt = now + countdownMs,
            attemptIndex = attemptIndex
        )
    }

    /** 当前是否有活跃任务（含倒计时） */
    fun isBusy(job: CaptureJob?): Boolean = job != null && job.phase.isActive

    /**
     * 时间推进。倒计时到点才进入 TRIGGERING，此前任何 [tick] 都不产生命令。
     */
    fun tick(job: CaptureJob, now: Long): CaptureJob {
        if (job.phase != CapturePhase.COUNTING_DOWN) return job
        return if (now >= job.countdownEndsAt) {
            job.copy(phase = CapturePhase.TRIGGERING)
        } else {
            job
        }
    }

    /** 半按对焦命令已发出（不代表相机已开始对焦） */
    fun onHalfPressSent(job: CaptureJob): CaptureJob =
        if (job.phase == CapturePhase.TRIGGERING) job.copy(heldKeys = job.heldKeys + HeldKey.HALF_PRESS) else job

    /** 全按快门命令已发出 */
    fun onShutterPressSent(job: CaptureJob): CaptureJob =
        if (job.phase == CapturePhase.TRIGGERING) job.copy(heldKeys = job.heldKeys + HeldKey.FULL_PRESS) else job

    /**
     * 相机确认快门动作。
     *
     * 这是 `已拍摄` 的唯一来源。此前界面在命令发出后就报「已拍摄（BLE）」，
     * 3 秒超时的情况下那句话是假的。
     */
    fun onCameraConfirmed(job: CaptureJob): CaptureJob {
        if (job.phase != CapturePhase.TRIGGERING && job.phase != CapturePhase.AWAITING_FILE) return job
        return job.copy(phase = CapturePhase.CONFIRMED, confirmed = true)
    }

    /** 快门回位、半按抬起完成后进入等文件阶段 */
    fun onKeysReleased(job: CaptureJob): CaptureJob =
        if (job.phase == CapturePhase.CONFIRMED) {
            job.copy(phase = CapturePhase.AWAITING_FILE, heldKeys = emptySet())
        } else {
            job.copy(heldKeys = emptySet())
        }

    /** 等到内容事件 / CaptureComplete → 完成 */
    fun onFileSeen(job: CaptureJob): CaptureStep =
        CaptureStep(job.copy(phase = CapturePhase.COMPLETED, heldKeys = emptySet()))

    /** 用户取消 */
    fun cancel(job: CaptureJob): CaptureStep = terminate(
        job,
        // 倒计时期间取消 = 一个命令都没发过，不能报成「已取消一次拍摄」
        if (job.countingDown) CaptureFailure.COUNTDOWN_CANCELLED else CaptureFailure.CANCELLED
    )

    /** 断线：仍按住的键已经发不出去了，但状态必须落到终态，不能挂着等永远不来的确认 */
    fun onDisconnected(job: CaptureJob): CaptureStep = terminate(job, CaptureFailure.DISCONNECTED)

    /** 相机未确认快门（超时） */
    fun onNotConfirmed(job: CaptureJob): CaptureStep = terminate(job, CaptureFailure.NOT_CONFIRMED)

    /** 确认了快门但没等到文件 */
    fun onNoFile(job: CaptureJob): CaptureStep = terminate(job, CaptureFailure.NO_FILE)

    /** 相机明确拒绝 */
    fun onRefused(job: CaptureJob): CaptureStep = terminate(job, CaptureFailure.REFUSED)

    /**
     * 落终态。**释放命令在这里一次性算出来**，调用方必须照单发送。
     *
     * 终态还留着 heldKeys 是状态机 bug，所以这里直接清空并把它们作为 releases 交出去。
     */
    private fun terminate(job: CaptureJob, failure: CaptureFailure): CaptureStep {
        val releases = job.outstandingReleases
        val phase = when (failure) {
            CaptureFailure.CANCELLED, CaptureFailure.COUNTDOWN_CANCELLED -> CapturePhase.CANCELLED
            else -> CapturePhase.FAILED
        }
        return CaptureStep(
            job = job.copy(phase = phase, heldKeys = emptySet(), failure = failure),
            releases = releases
        )
    }
}

/**
 * 间隔拍摄的调度判定。
 *
 * 「按上次完成 + 间隔」而不是「按墙上时钟排期」：相机写卡慢时按时钟排期会撞上还在忙的设备，
 * 这正是方案约束里「卡忙时不叠加快门」要防的情况。
 */
object IntervalScheduler {

    /**
     * 现在是否该起一张。
     *
     * @param lastFinishedAt 上一张**进入终态**的时刻；null 表示还没拍过（第一张立即允许）
     * @param busy 当前是否有活跃任务（含倒计时与等文件）
     */
    fun shouldStart(now: Long, lastFinishedAt: Long?, intervalMs: Long, busy: Boolean): Boolean {
        if (busy) return false
        if (intervalMs <= 0L) return true
        val previous = lastFinishedAt ?: return true
        return now - previous >= intervalMs
    }

    /**
     * 下一次起拍还要等多久（毫秒）；0 = 现在就能起。
     *
     * 单独抽出这个函数，是为了让「30 张连拍不重叠」这类验收能在 JVM 上被完整跑出来。
     */
    fun delayUntilNext(now: Long, lastFinishedAt: Long?, intervalMs: Long): Long? {
        if (intervalMs <= 0L) return 0L
        val previous = lastFinishedAt ?: return 0L
        val remaining = intervalMs - (now - previous)
        return if (remaining > 0L) remaining else 0L
    }
}

// 这里**刻意没有**自动重试预算。拍摄与下载遵循同一原则：一次命令一次往返，超时或断线就落
// 终态并把按住的按键送回。自动重试在相机上意味着可能把用户没打算拍的那一张真的拍下来，
// 所以「断线不无限重试」这条验收是靠**根本不存在自动重试**来满足的，而不是靠一个计数上限。
