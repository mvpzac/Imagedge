package com.imagedge.camera.feature.edit.clip

import com.imagedge.camera.motionphoto.ClipBounds
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
 * 片段编辑的全部数学。只 import `kotlin.math` 与 `:motionphoto` 的
 * [com.imagedge.camera.motionphoto.ClipBounds]，两者都不碰 Android API，故可 JVM 单测。
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
 *   它的 400ms 下限**不是本项目的下限**，故本文件不再引用那个数；
 *   沿用的只有它真正支持的两点：手写比较而非 `coerceIn`（见 [clampStart]）、
 *   以及边界倒置必须塌陷成有序点而不是抛异常（见 `ClipMathTest` 同名测试）。
 *
 * 时长边界本身不在这里定：[MIN_CLIP_MS] / [MAX_CLIP_MS] 是
 * `com.imagedge.camera.motionphoto.ClipBounds` 的转发值——UI 与导出钳制必须同源，
 * 各写一份就会漂移，而漂移的后果是用户的选择被静默改写。
 */
object ClipMath {

    /**
     * 最短片段。与 [ClipBounds.MIN_CLIP_MS] 同源——两者各写一份就会漂移，
     * 而漂移的后果是用户选 400ms 却拿到 1.5s 的产物。
     */
    const val MIN_CLIP_MS = ClipBounds.MIN_CLIP_MS

    /** 最长片段。同样与 [ClipBounds.MAX_CLIP_MS] 同源 */
    const val MAX_CLIP_MS = ClipBounds.MAX_CLIP_MS

    /**
     * 手柄命中的半径（像素）。**调用方须按密度换算**：48dp 的触控目标在 density=1 时
     * 是 `24f`，density=2 时要传 `48f`。这里存的 24 是密度为 1 时的基准值。
     *
     * 与 [MIN_CLIP_MS] / [MAX_CLIP_MS] 无关：它只描述手指命中范围，**不随片段长度缩放**。
     * 推论是 [resolveZone] 的「同距」只在长素材上才可能出现——两柄最近的间距是段长折算的
     * 轨道像素，短素材上它离 2 倍半径还差得远（推导见 [resolveZone]）。
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
     * 而哨兵值会造出 `startMs=1500 > endMs=0` 的倒置区间。
     * 返回的是零长度但**有序**的区间——这不是「最短片段」，是「没有片段」。
     *
     * 这里只管下界不管段长上限：**起手越靠左段就可能越长**，而 5s 的上限由
     * [clampEnd] 的 `startMs + MAX_CLIP_MS` 承担。两个钳制都跑过时，
     * `ClipSpec.durationMs` 必落在 `[MIN_CLIP_MS, MAX_CLIP_MS]`（未退化时）。
     */
    fun clampStart(targetMs: Long, endMs: Long): Long {
        val max = endMs - MIN_CLIP_MS
        if (max <= 0L) return 0L
        return targetMs.coerceIn(0L, max)
    }

    /**
     * 止手钳制到 `[startMs + MIN_CLIP_MS, min(startMs + MAX_CLIP_MS, durationMs)]`。
     *
     * **前置条件**：调用方须保证 `0 <= startMs <= durationMs`——[clampStart] 对同一素材
     * 的产出已保证这一点。
     *
     * 上界取两个的较小者，各有各的理由：
     * - `durationMs`：止手必须能落到素材末尾，否则永远选不到最后一帧；
     * - `startMs + MAX_CLIP_MS`：与导出侧 `VideoTrimmer.trim` 的
     *   `endMs.coerceAtMost(clampedStart + MAX_CLIP_MS)` **逐字同一条**。少了它，
     *   用户在 10s 素材上选满全长，预览显示 10s 而导出静默截成 5s。
     *
     * **上界是相对 `startMs` 的，不是相对素材的**。若把天花板写成 `durationMs`
     * （或 `durationMs - MIN_CLIP_MS`），3s 素材上从 1s 起手就能选到 5s 窗口——
     * 素材根本不够 5s，而导出那边照样按 `startMs + MAX_CLIP_MS` 截，差异只是换个形状。
     *
     * **在前置条件成立时**本函数永不产出 `endMs < startMs`：非退化区间的结果落在
     * `[startMs + MIN_CLIP_MS, startMs + MAX_CLIP_MS]` 内，退化区间落在 `durationMs`。
     * 结果也恒 `<= durationMs`（上界取了 `min`）。两条保证都来自前置条件，**不是**
     * 来自退化分支本身——违反前置条件时它们不成立，本函数也不为此兜底：
     * `clampEnd(targetMs = 100, startMs = 5000, durationMs = 100)` 返回 `100`，
     * 低于调用方给它的 `startMs`。
     *
     * 退化区间（`durationMs <= startMs + MIN_CLIP_MS`）同样没有合法片段，此时塌陷到素材
     * 末尾 `durationMs` 而不是 [MIN_CLIP_MS]：后者会把止手放到 100ms 的素材之外的 1500ms。
     */
    fun clampEnd(targetMs: Long, startMs: Long, durationMs: Long): Long {
        val min = startMs + MIN_CLIP_MS
        val max = minOf(startMs + MAX_CLIP_MS, durationMs)
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
     * 为什么需要全序：两个命中圆相交的条件是柄间距 < `2 * TOUCH_RADIUS_PX`，
     * 而柄间距是段长折算的轨道像素 `(clipMs / durationMs) * trackPx`——**随素材变，
     * 不是常数**。[MIN_CLIP_MS] 因此并不恒占轨道的某个百分比：它要折成 2 倍半径
     * （density=1 下 48px）需满足 `trackPx / durationMs = 48 / 1500 = 3.2%`，在
     * 360px（360dp、density=1）轨道上即素材须 ≥ `1500 × 360 / 48` = 11250ms。
     * 3s 素材里最短片段已占半条轨道（180px），是 48px 直径的 3.75 倍，两圆够不着，
     * 同距不可达。真正要处理的仍是那半边几何：用户在长素材上把段收到比 2 倍半径还短、
     * 命中区真的重叠时，按下点可能与两个柄等距，全序才给出确定答案。
     * 退化区间**不是**那种情形：两个钳制塌陷到素材两端，柄间距恰是轨道全长（最宽）。
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