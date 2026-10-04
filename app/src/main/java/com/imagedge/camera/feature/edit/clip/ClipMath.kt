package com.imagedge.camera.feature.edit.clip

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 片段编辑的纯算术——钳制、封面收口、像素换算、手柄命中判定
 * </pre>
 */

/**
 * 片段编辑的全部数学。只 import `kotlin.math`，无 Android 依赖，故可 JVM 单测。
 *
 * 把夹取与判定从 Compose 里摘出来，于是「边界交叉会不会崩」这类问题由测试回答
 * 而不是由手指回答。两个开源实现独立收敛到同一条：
 * - ClearCut(MIT) `app/src/main/java/com/novacut/editor/ui/editor/TimelineClipLayout.kt`
 *   —— 无 Compose import；`resolveTimelineClipGestureZone(touchXPx, …)` 按按下位置定区，
 *   `resolveTimelineClipGestureAction` 在区间退化时返回 `null` 而不是造出倒置区间；
 *   配 JVM 测试 `TimelineClipLayoutTest.kt`。
 * - OpenLoop(Apache-2.0)
 *   `app/src/main/java/io/github/stozo04/openloop/ui/components/TrimHandleMath.kt`
 *   —— 无 Compose import；两个 `clampTrim*Ms` 纯函数 + `TrimHandleMathTest.kt`。
 */
object ClipMath {

    /**
     * 最短片段。400ms 取自 OpenLoop `ui/OpenLoopViewModel.kt` 的 `MIN_TRIM_DURATION`
     * （`400.milliseconds`，见该文件 companion object）。
     */
    const val MIN_CLIP_MS = 400L

    /**
     * 手柄命中的半径（像素）。**调用方须按密度换算**：48dp 的触控目标在 density=1 时
     * 是 `24f`，density=2 时要传 `48f`。这里存的 24 是密度为 1 时的基准值。
     */
    const val TOUCH_RADIUS_PX = 24f

    enum class Zone { None, Start, End, Cover }

    /**
     * 起手钳制到 `[0, endMs - MIN_CLIP_MS]`。
     *
     * 手写比较而非 `coerceIn`：后者在 `max < min` 时抛 `IllegalArgumentException`，
     * 而边界交叉（止手被拖到起手左边）在拖拽中真的会发生。
     *
     * 前置条件：`endMs` 是素材时长，调用方须保证 `>= 0`。**在此前提下**返回值恒满足
     * `0 <= 返回值 <= endMs`；`endMs` 为负时本函数不兜底，会返回 0 而高于 `endMs`。
     *
     * 退化区间（`endMs - MIN_CLIP_MS <= 0`）意味着素材比 [MIN_CLIP_MS] 还短、
     * 根本不存在合法片段。此时**塌陷到 0 而不是 [MIN_CLIP_MS]**：0 不会超过非负的 `endMs`，
     * 而哨兵值会造出 `startMs=400 > endMs=0` 的倒置区间。
     * 返回的是零长度但**有序**的区间——这不是「最短片段」，是「没有片段」。
     */
    fun clampStart(targetMs: Long, endMs: Long): Long {
        val max = endMs - MIN_CLIP_MS
        if (max <= 0L) return 0L
        return targetMs.coerceIn(0L, max)
    }

    /**
     * 止手钳制到 `[startMs + MIN_CLIP_MS, durationMs]`。
     *
     * **前置条件**：调用方须保证 `0 <= startMs <= durationMs`——[clampStart] 对同一素材
     * 的产出已保证这一点。
     *
     * 上界是 `durationMs` 本身而不是 `durationMs - MIN_CLIP_MS`：止手必须能落到素材末尾，
     * 否则永远选不到最后一帧。
     *
     * **在前置条件成立时**本函数永不产出 `endMs < startMs`：非退化区间的结果落在
     * `[startMs + MIN_CLIP_MS, durationMs]` 内，退化区间落在 `durationMs`。
     * 保证来自前置条件，**不是**来自退化分支本身——违反前置条件时它不成立，本函数也不
     * 为此兜底：`clampEnd(targetMs = 100, startMs = 5000, durationMs = 100)` 返回 `100`，
     * 低于调用方给它的 `startMs`。
     *
     * 退化区间（`durationMs <= startMs + MIN_CLIP_MS`）同样没有合法片段，此时塌陷到素材
     * 末尾 `durationMs` 而不是 [MIN_CLIP_MS]：后者会把止手放到 100ms 的素材之外的 400ms。
     */
    fun clampEnd(targetMs: Long, startMs: Long, durationMs: Long): Long {
        val min = startMs + MIN_CLIP_MS
        val max = durationMs
        if (max <= min) return max.coerceAtLeast(0L)
        return targetMs.coerceIn(min, max)
    }

    /**
     * 封面收口。**预览与导出必须都调它**，这是 spec §2.1 的裁定：
     * UI 放行越界是为了保住「先挑最好的帧再决定裁哪段」，但导出必须落在片段内，
     * 而这次钳制一旦只发生在导出侧，用户就会拿到一帧他在屏幕上没见过的画面。
     *
     * `endMs.coerceAtLeast(startMs)` 是必需的守卫：`coerceIn(min, max)` 在 `max < min` 时抛
     * `IllegalArgumentException`。区间交叉（`startMs > endMs`）时上下界都被抬到 `startMs`，
     * 结果恒为 `startMs`，与 `coverMs` 取值无关。
     */
    fun effectiveCoverMs(startMs: Long, endMs: Long, coverMs: Long): Long =
        coverMs.coerceIn(startMs, endMs.coerceAtLeast(startMs))

    /** 越界判定，供 UI 渲染越界态——钳制可以晚发生，但不能隐形 */
    fun coverOutOfRange(startMs: Long, endMs: Long, coverMs: Long): Boolean =
        coverMs < startMs || coverMs > endMs

    /** 轨道像素 → 毫秒。`trackPx <= 0` 时返回 0，避免除零产出天文数字 */
    fun pxToMs(px: Float, trackPx: Float, durationMs: Long): Long {
        if (trackPx <= 0f) return 0L
        val ratio = (px / trackPx).coerceIn(0f, 1f)
        return (ratio * durationMs).roundToLong()
    }

    /**
     * 按下位置 → 拖拽目标。**只在 `onDragStart` 调一次**，之后整个拖拽过程不再重判。
     *
     * 半径 [TOUCH_RADIUS_PX] 外的候选先滤掉，剩下的按 `compareBy(距离, Start 优先键)`
     * 取最小；比较器相等时 `minWithOrNull` 保留**先出现**的那个，所以全序是
     * `Start > End > Cover`（即候选表的书写顺序），并非「只有 Start 有优先键」。
     * 「同距」指浮点**精确相等**，实践上只出现在恰好中点这类测度为零的位置。
     *
     * 为什么需要全序：两个命中圆相交的条件是柄间距 < `2 * TOUCH_RADIUS_PX`。
     * [MIN_CLIP_MS] 并不恒占轨道的 13.3%——那要在 3s 素材、360dp 轨道、density=1 下
     * 才成立（400/3000 = 13.3%）；此时它折合 48dp，恰等于两个 24dp 半径之和，
     * 两圆**外切**、只共享中点那一个像素。同距因而可达，但那不是「重叠区」；
     * 真正重叠发生在柄间距小于 2 倍半径时，也就是素材比 [MIN_CLIP_MS] 还短的退化区间
     * （两个钳制塌陷到素材两端，柄间距等于素材全长）。
     */
    fun resolveZone(x: Float, startPx: Float, endPx: Float, coverPx: Float): Zone {
        val candidates = listOf(
            Zone.Start to abs(x - startPx),
            Zone.End to abs(x - endPx),
            Zone.Cover to abs(x - coverPx),
        ).filter { it.second <= TOUCH_RADIUS_PX }
        if (candidates.isEmpty()) return Zone.None
        val best = candidates.minWithOrNull(
            compareBy({ it.second }, { if (it.first == Zone.Start) 0 else 1 })
        )
        return best?.first ?: Zone.None
    }
}