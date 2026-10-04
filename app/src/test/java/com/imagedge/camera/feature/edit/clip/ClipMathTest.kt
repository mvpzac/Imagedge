package com.imagedge.camera.feature.edit.clip

import com.imagedge.camera.feature.edit.clip.ClipMath.Zone
import com.imagedge.camera.motionphoto.ClipBounds
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

    /**
     * 两个边界同时钉住两件事：**字面值**（1500 / 5000）与**转发关系**。
     *
     * 字面值是契约：预览与导出共用的就是这两个数，改一个就整体位移。
     * 转发关系是本轮 finding 的正题——`ClipMath` 必须指向
     * `com.imagedge.camera.motionphoto.ClipBounds` 同一个编译期常量，而不是拷贝一份。
     * 各写一份就会漂移，而漂移的后果是静默的：用户选 400ms，预览显示 400ms，
     * 导出按自己的 1500ms 拉长；或用户选 7s，导出悄悄截成 5s。
     */
    @Test
    fun `边界常量与导出侧同源且就是 1500 与 5000`() {
        assertEquals(1_500L, ClipBounds.MIN_CLIP_MS)
        assertEquals(5_000L, ClipBounds.MAX_CLIP_MS)
        assertEquals(ClipBounds.MIN_CLIP_MS, ClipMath.MIN_CLIP_MS)
        assertEquals(ClipBounds.MAX_CLIP_MS, ClipMath.MAX_CLIP_MS)
    }

    /**
     * 起手上界是 `endMs - MIN_CLIP_MS` = `3000 - 1500` = **1500**。
     * （上一版写死的是 `3000 - 400` = 2600；下限从 400 改到 1500 后必须跟着变，
     * 否则这条断言钉的是一个 UI 早已放行不到的区间。）
     */
    @Test
    fun `起手不能越过止手减去最短片段`() {
        assertEquals(0L, ClipMath.clampStart(-500L, endMs = 3000L))
        assertEquals(1500L, ClipMath.clampStart(9999L, endMs = 3000L))
    }

    /**
     * 止手下界是 `startMs + MIN_CLIP_MS` = `2000 + 1500` = **3500**（上一版 2400）。
     * 上界在 5000ms 素材、2000 起手下是 `min(2000 + 5000, 5000)` = 5000，故止手能到末尾。
     */
    @Test
    fun `止手不能越过起手加上最短片段`() {
        assertEquals(3500L, ClipMath.clampEnd(-100L, startMs = 2000L, durationMs = 5000L))
        assertEquals(5000L, ClipMath.clampEnd(9999L, startMs = 2000L, durationMs = 5000L))
    }

    /**
     * 素材**够长**时止手也不得越过 `startMs + MAX_CLIP_MS`——天花板相对**起手**，
     * 不是相对素材，也因此不是 `durationMs`。少了这条，10s 素材选满全长预览显示 10s，
     * 导出按 `VideoTrimmer.trim` 的 `coerceAtMost(clampedStart + MAX_CLIP_MS)` 截成 5s。
     */
    @Test
    fun `素材够长时止手也不越过起手加上最长片段`() {
        assertEquals(5_000L, ClipMath.clampEnd(9_000L, startMs = 0L, durationMs = 9_000L))
        // 起手 1000 → 天花板 6000，而不是 5000（那会把「相对起手」误写成「相对素材」）
        assertEquals(6_000L, ClipMath.clampEnd(9_000L, startMs = 1_000L, durationMs = 9_000L))
        assertEquals(5_500L, ClipMath.clampEnd(9_000L, startMs = 500L, durationMs = 9_000L))
        // 天花板之下 1.5s 下界照旧
        assertEquals(2_500L, ClipMath.clampEnd(0L, startMs = 1_000L, durationMs = 9_000L))
        for (dur in longArrayOf(5_001L, 8_000L, 60_000L)) {
            val end = ClipMath.clampEnd(dur, startMs = 2_000L, durationMs = dur)
            assertTrue("duration=$dur", end - 2_000L <= 5_000L)
        }
    }

    /**
     * 钉住的是**退化区间塌陷到素材端点**：素材 0ms → 起手 0；起手已等于素材末尾 →
     * 止手落在 `durationMs`。两条都产出零长度但有序的区间（不是 [ClipMath.MIN_CLIP_MS]）。
     *
     * 按新下限重算，值不变但理由必须重新写一遍：
     * - `clampStart(3000, endMs = 0)`：上界 `0 - 1500` = -1500 ≤ 0 → 塌陷 0；
     * - `clampEnd(0, startMs = 1000, durationMs = 1000)`：下界 `1000 + 1500` = 2500，
     *   上界 `min(1000 + 5000, 1000)` = 1000 ≤ 2500 → 塌陷到 1000（= 素材末尾）。
     *
     * 为什么必须是纯算术：coerceIn 在 max < min 时抛 IllegalArgumentException。
     * OpenLoop `ui/components/TrimHandleMath.kt` 的 KDoc 记着一次真实崩溃
     * （Crashlytics `7169b499` / issue #95）：335ms 的片段配它自己的 400ms 下限，派生边界倒置。
     * 那是 OpenLoop 的下限，不是本项目的；本项目下限 1500ms 的同类风险一样存在。
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
     * 该处 MIN_CLIP_MS = ClipBounds.MIN_CLIP_MS = 1500L），重夹只会把止手推到用户没选的位置。
     *
     * 采样点取新下限两侧：`0 / 100 / 1499 / 1500`（上一版按 400ms 取的 `399 / 400`
     * 在 1.5s 下限下已落在同一个退化区里，不再是边界）。
     */
    @Test
    fun `退化输入下也不会造出倒置区间`() {
        for (end in longArrayOf(0L, 100L, 1499L, 1500L)) {
            assertTrue("end=$end", ClipMath.clampStart(9999L, end) <= end)
        }
        for (d in longArrayOf(0L, 100L, 1499L, 1500L)) {
            assertTrue("duration=$d", ClipMath.clampEnd(0L, startMs = 0L, durationMs = d) <= d)
        }
    }

    /**
     * 本轮缺陷的正题，逐点证明 UI 放行的 `[startMs, endMs]` **原样**落在导出钳制的
     * 窗口 `end ∈ [startMs + MIN_CLIP_MS, startMs + MAX_CLIP_MS]` 内
     * （`VideoTrimmer.trim` 的 `coerceAtLeast` / `coerceAtMost` 两条）。
     * 只要这里恒真，预览显示的段长就等于导出会产出的段长，反之即然。
     *
     * 覆盖两种形态：素材 ≤ 起手 + 1.5s 的退化区间（塌陷到 `durationMs`，两端仍有序），
     * 与素材更长时的正常区间（段长必在 1500…5000 之间）。
     */
    @Test
    fun `UI 放行的段长一定落在导出钳制的窗口内`() {
        for (dur in longArrayOf(1_500L, 1_501L, 3_000L, 5_000L, 5_001L, 10_000L, 60_000L)) {
            for (start in 0L..dur step 500L) {
                val end = ClipMath.clampEnd(targetMs = Long.MAX_VALUE / 2, startMs = start, durationMs = dur)
                assertTrue("start=$start dur=$dur", end in start..dur)
                if (dur <= start + 1_500L) {
                    assertEquals("start=$start dur=$dur", dur, end)
                } else {
                    assertTrue("start=$start dur=$dur 段长=${end - start}", end - start in 1_500L..5_000L)
                }
            }
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
     * 1500ms 的最短片段**不折成**48px：那要在 `trackPx / durationMs = 48 / 1500 = 3.2%`
     * 时才成立，即 360px（360dp、density=1）轨道配 ≥ 11250ms 的素材。3s 素材里最短片段
     * 已占 180px，两圆够不着，真正重叠要用户在长素材上把段收得比 2 倍半径还短。
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