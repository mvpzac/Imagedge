package com.imagedge.camera.image

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 取景帧的分析节流门（T2）：停用零分析、按频率放行、不留队列
 *     version: 1.0
 * </pre>
 */

/**
 * 分析准入判定。
 *
 * 为什么单独一个类型：T2 的约束是「首版最多 5Hz、停用时零分析、不得形成无界队列」。
 * 这三条最容易在接线的时候被写散到 ViewModel 里（一个 `if (frame != null)` 加一个
 * `delay`），而它们恰好是**性能出问题时唯一能解释行为的地方**。
 *
 * 时钟由 [nowMs] 注入，所以这几条能在 JVM 上直接验收，不需要真帧。
 *
 * **没有队列**：调用方拿到 false 就把这一帧丢掉。分析永远只看最新帧，
 * 积压帧既没有意义（用户要的是"现在"的曝光）又是无界内存的来源。
 */
class FrameGate(
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long
) {

    /**
     * 关掉时**一次都不分析**。重新打开后第一帧立刻放行——
     * 用户刚开的辅助，不该再等一个周期才见到东西。
     */
    var enabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) lastAdmitted = NO_PREVIOUS
            }
        }

    private var lastAdmitted = NO_PREVIOUS

    /** 这一帧要不要分析。false = 丢掉它，不要存起来 */
    fun admit(): Boolean {
        if (!enabled) return false
        val now = nowMs()
        if (lastAdmitted != NO_PREVIOUS && now - lastAdmitted < minIntervalMs) return false
        lastAdmitted = now
        return true
    }

    companion object {
        /** 5Hz（T2 首版上限）：20fps 的帧里每 4 帧最多分析 1 帧 */
        const val DEFAULT_INTERVAL_MS = 200L
        private const val NO_PREVIOUS = Long.MIN_VALUE
    }
}
