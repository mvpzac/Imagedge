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
     * coerceIn 在 max < min 时抛 IllegalArgumentException。OpenLoop 的
     * TrimHandleMath.kt 记着一次真实崩溃：335ms 的片段配 400ms 下限。
     * 这里的场景是止手被拖到起手左边，两个边界会交叉。
     */
    @Test
    fun `边界交叉时不抛异常而是给出最短片段`() {
        assertEquals(0L, ClipMath.clampStart(3000L, endMs = 0L))
        assertEquals(1000L, ClipMath.clampEnd(0L, startMs = 1000L, durationMs = 1000L))
    }

    /**
     * 两条硬约束：不能抛异常，且**不能造出倒置区间**。
     * 倒置区间会一路传到 trimVideo，止手落在素材末尾之外。
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
     * 最短片段 400ms 在 360dp 轨道上占 13.3%，而 48dp 触控宽也是 13.3%——
     * 两个手柄的命中区正好在边界上重叠。规则必须是全序，不能是「最近的之一」。
     */
    @Test
    fun `手柄命中在重叠区给出确定的优先级`() {
        assertEquals(Zone.Start, ClipMath.resolveZone(x = 100f, startPx = 100f, endPx = 200f, coverPx = 500f))
        assertEquals(Zone.End, ClipMath.resolveZone(x = 200f, startPx = 100f, endPx = 200f, coverPx = 500f))
        // 恰好居中时取起手，且不依赖书写顺序。
        // 这里必须让两柄相距 < 2×触摸半径，命中区才真的重叠、居中点才真的可命中；
        // 若沿用上面 100px 的间距，居中点距任一手柄 50px，已在 24px 半径之外，
        // 与「外侧一点必须是 None」的那条断言在数学上无法同时成立。
        assertEquals(Zone.Start, ClipMath.resolveZone(x = 112f, startPx = 100f, endPx = 124f, coverPx = 500f))
        assertEquals(Zone.Cover, ClipMath.resolveZone(x = 500f, startPx = 100f, endPx = 200f, coverPx = 500f))
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