package com.imagedge.camera.feature.edit.clip

import com.imagedge.camera.feature.edit.clip.ClipMath.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 片段钳制与手柄命中判定——UI 不参与，全部是纯算术
 * </pre>
 */
class ClipMathTest {

    @Test
    fun `起手不能越过止手减去最短片段`() {
        assertEquals(0L, ClipMath.clampStart(-500L, endMs = 3000L))
        assertEquals(2600L, ClipMath.clampStart(9999L, endMs = 3000L))
    }

    @Test
    fun `止手不能越过起手加上最短片段`() {
        assertEquals(2400L, ClipMath.clampEnd(-100L, startMs = 2000L, durationMs = 5000L))
        assertEquals(5000L, ClipMath.clampEnd(9999L, startMs = 2000L, durationMs = 5000L))
    }

    /**
     * 钉住的是**退化区间塌陷到素材端点**：素材 0ms → 起手 0；起手已等于素材末尾 →
     * 止手落在 `durationMs`。两条都产出零长度但有序的区间（不是 [ClipMath.MIN_CLIP_MS]）。
     *
     * 为什么必须是纯算术：coerceIn 在 max < min 时抛 IllegalArgumentException。
     * OpenLoop `ui/components/TrimHandleMath.kt` 的 KDoc 记着一次真实崩溃
     * （Crashlytics `7169b499` / issue #95）：335ms 的片段配 400ms 下限，派生边界倒置。
     * 本文件的场景是止手被拖到起手左边（targetMs=0 < startMs=1000），两个边界交叉。
     */
    @Test
    fun `边界交叉时塌陷到零长度但有序的区间`() {
        assertEquals(0L, ClipMath.clampStart(3000L, endMs = 0L))
        assertEquals(1000L, ClipMath.clampEnd(0L, startMs = 1000L, durationMs = 1000L))
    }

    /**
     * 两条硬约束：不能抛异常，且**不能造出倒置区间**。
     * 倒置区间一旦离开 ClipMath 就没有东西能修回来：`ClipSpec.durationMs` 对倒置与零长
     * 产出同一个 0；下游 `VideoTrimmer.trim` 虽会重夹一次
     * （`motionphoto/.../compose/VideoTrimmer.kt`，`endMs.coerceAtLeast(startMs + MIN_CLIP_MS)`，
     * 该处 MIN_CLIP_MS = 1500L），重夹只会把止手推到用户没选的位置。
     */
    @Test
    fun `退化输入下也不会造出倒置区间`() {
        for (end in longArrayOf(0L, 100L, 399L, 400L)) {
            assertTrue("end=$end", ClipMath.clampStart(9999L, end) <= end)
        }
        for (d in longArrayOf(0L, 100L, 399L, 400L)) {
            assertTrue("duration=$d", ClipMath.clampEnd(0L, startMs = 0L, durationMs = d) <= d)
        }
    }

    @Test
    fun `片段不会被拖成负长度`() {
        assertTrue(ClipMath.clampEnd(0L, startMs = 0L, durationMs = 100L) > 0L)
        assertTrue(ClipMath.clampEnd(0L, startMs = 0L, durationMs = 0L) >= 0L)
    }

    @Test
    fun `封面收口是预览与导出共用的唯一来源`() {
        assertEquals(2000L, ClipMath.effectiveCoverMs(1000L, 3000L, 2000L))
        assertEquals(3000L, ClipMath.effectiveCoverMs(1000L, 3000L, 9999L))
        assertEquals(1000L, ClipMath.effectiveCoverMs(1000L, 3000L, 0L))
    }

    /**
     * 钉住 [ClipMath.effectiveCoverMs] 的守卫：区间交叉时上下界一起被抬到 `startMs`，
     * 结果恒为 `startMs`，与 `coverMs` 取值无关。没有那个 `coerceAtLeast`，
     * `coerceIn` 会在 `max < min` 时抛 IllegalArgumentException——这正是本模块要防的崩溃。
     */
    @Test
    fun `区间交叉时封面收口退回起手而不是抛异常`() {
        assertEquals(3000L, ClipMath.effectiveCoverMs(startMs = 3000L, endMs = 1000L, coverMs = 2000L))
        assertEquals(3000L, ClipMath.effectiveCoverMs(startMs = 3000L, endMs = 1000L, coverMs = 0L))
        assertEquals(3000L, ClipMath.effectiveCoverMs(startMs = 3000L, endMs = 1000L, coverMs = 9999L))
    }

    @Test
    fun `越界会被标出来而不是被静默改写`() {
        assertFalse(ClipMath.coverOutOfRange(1000L, 3000L, 2000L))
        assertTrue(ClipMath.coverOutOfRange(1000L, 3000L, 4000L))
        assertTrue(ClipMath.coverOutOfRange(1000L, 3000L, 500L))
    }

    @Test
    fun `像素按轨道线性换算成毫秒`() {
        assertEquals(1500L, ClipMath.pxToMs(px = 150f, trackPx = 300f, durationMs = 3000L))
        assertEquals(0L, ClipMath.pxToMs(px = 0f, trackPx = 300f, durationMs = 3000L))
        assertEquals(3000L, ClipMath.pxToMs(px = 300f, trackPx = 300f, durationMs = 3000L))
    }

    /**
     * 轨道宽还没有量出来（Compose 首次布局前、或 `trackPx` 被减到 0）时的守卫：
     * `trackPx <= 0` 直接返回 0，不做除法。这里 `px` 刻意取非零值，
     * 以便确认返回 0 来自守卫而不是来自 ratio 恰好为 0。
     */
    @Test
    fun `轨道像素为零或负数时换算结果为零`() {
        assertEquals(0L, ClipMath.pxToMs(px = 150f, trackPx = 0f, durationMs = 3000L))
        assertEquals(0L, ClipMath.pxToMs(px = 150f, trackPx = -300f, durationMs = 3000L))
    }

    /**
     * 两个命中圆相交的条件是柄间距 < 2 × [ClipMath.TOUCH_RADIUS_PX]（density=1 下 48px）。
     * 400ms 只在 3s 素材 / 360dp 轨道上才折合 48px，此时两圆**外切**、只共享中点，
     * 真正重叠要片段时间更短（退化区间两个手柄塌陷到素材两端）。
     * 所以这条用的是 24px 间距——真的重叠，检验的仍是「同距给确定答案」这一原意。
     */
    @Test
    fun `手柄命中在重叠区给出确定的优先级`() {
        assertEquals(Zone.Start, ClipMath.resolveZone(x = 100f, startPx = 100f, endPx = 200f, coverPx = 500f))
        assertEquals(Zone.End, ClipMath.resolveZone(x = 200f, startPx = 100f, endPx = 200f, coverPx = 500f))
        // 恰好居中时取起手——靠的是比较器里 Start 的优先键，不是候选表的书写顺序。
        // 这里必须让两柄相距 < 2×触摸半径，命中区才真的重叠、居中点才真的可命中；
        // 若沿用上面 100px 的间距，居中点距任一手柄 50px，已在 24px 半径之外，
        // 与「外侧一点必须是 None」的那条断言在数学上无法同时成立。
        assertEquals(Zone.Start, ClipMath.resolveZone(x = 112f, startPx = 100f, endPx = 124f, coverPx = 500f))
        assertEquals(Zone.Cover, ClipMath.resolveZone(x = 500f, startPx = 100f, endPx = 200f, coverPx = 500f))
        // 全序不止 Start 优先：End 与 Cover 同距（各 20px，起手 120px 已在半径外）时取 End，
        // 因为 minWithOrNull 保留先出现的那个。三路同距同理是 Start > End > Cover。
        assertEquals(Zone.End, ClipMath.resolveZone(x = 220f, startPx = 100f, endPx = 200f, coverPx = 240f))
    }

    @Test
    fun `远离全部手柄的中段不产生拖拽目标`() {
        assertEquals(Zone.None, ClipMath.resolveZone(x = 300f, startPx = 100f, endPx = 200f, coverPx = 500f))
    }

    /** 触摸宽内才认；外侧一点就必须是 None */
    @Test
    fun `命中判定有触摸宽上限`() {
        val touch = 24f
        assertEquals(Zone.Start, ClipMath.resolveZone(x = 100f + touch - 1f, startPx = 100f, endPx = 200f, coverPx = 500f))
        assertEquals(Zone.None, ClipMath.resolveZone(x = 100f + touch + 1f, startPx = 100f, endPx = 200f, coverPx = 500f))
    }
}