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
 * 片段编辑的全部数学。无 Android 依赖，故可 JVM 单测。
 *
 * 文件存在的理由与 ClearCut 的 `TimelineClipLayout.kt`、OpenLoop 的
 * `TrimHandleMath.kt` 相同：把夹取与判定从 Compose 里摘出来，
 * 于是「边界交叉会不会崩」这类问题由测试回答而不是由手指回答。
 */
object ClipMath {

    /** 最短片段。取 OpenLoop `ui/OpenLoopViewModel.kt` 的 `MIN_TRIM_DURATION` */
    const val MIN_CLIP_MS = 400L

    /** 手柄命中的半径（像素）。调用方按密度换算，48dp 触控目标的半径即 24dp */
    const val TOUCH_RADIUS_PX = 24f

    enum class Zone { None, Start, End, Cover }

    /**
     * 起手钳制到 `[0, endMs - MIN_CLIP_MS]`。
     *
     * 手写比较而非 `coerceIn`：后者在 `max < min` 时抛异常，而边界交叉
     * （止手被拖到起手左边）在拖拽中真的会发生。
     *
     * 退化区间（`endMs - MIN_CLIP_MS <= 0`，两个手柄已经交叉或素材太短）
     * 里不存在合法起手，返回 [MIN_CLIP_MS] 作为「压到最短片段」的哨兵而不是 0——
     * 0 会让片段长度变成 0 帧，哨兵至少还能被 ClipSpec.durationMs 夹回非负，
     * 且调用方必须同时夹两个手柄才能得到合法区间。
     */
    fun clampStart(targetMs: Long, endMs: Long): Long {
        val max = endMs - MIN_CLIP_MS
        if (max <= 0L) return MIN_CLIP_MS
        return targetMs.coerceIn(0L, max)
    }

    /**
     * 止手钳制到 `[startMs + MIN_CLIP_MS, durationMs]`。同样手写比较，退化区间返回 [MIN_CLIP_MS]。
     *
     * 上界是 `durationMs` 本身而不是 `durationMs - MIN_CLIP_MS`：止手必须能落到素材末尾，
     * 否则永远选不到最后一帧。
     */
    fun clampEnd(targetMs: Long, startMs: Long, durationMs: Long): Long {
        val min = startMs + MIN_CLIP_MS
        val max = durationMs
        if (max <= min) return MIN_CLIP_MS
        return targetMs.coerceIn(min, max)
    }

    /**
     * 封面收口。**预览与导出必须都调它**，这是 spec §2.1 的裁定：
     * UI 放行越界是为了保住「先挑最好的帧再决定裁哪段」，但导出必须落在片段内，
     * 而这次钳制一旦只发生在导出侧，用户就会拿到一帧他在屏幕上没见过的画面。
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
     * 全序而非「最近的」：最短片段 [MIN_CLIP_MS] 在 360dp 轨道上占 13.3%，
     * 与 48dp 手柄的 13.3% 正好相等，两个手柄的命中区会重叠。
     * 同距时取起手，用 `<=` 而非 `<`，使结果不依赖两个手柄的书写顺序。
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