package com.imagedge.camera.feature.edit.triptych

import com.imagedge.camera.feature.edit.triptych.LiveTriptychViewModel.Aspect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 三拼档位标签的算术——标签写的是「每格」还是「成品」必须与画布实算一致
 * </pre>
 */
class LiveTriptychAspectLabelTest {

    /**
     * 成品 = 每格纵向堆叠 3 格（见 `buildTriptychBitmap` 的 `cellH * slots.size`）。
     *
     * 这条钉的是**标签与画布一致**，不是标签好不好看。曾经有过一次判断是
     * 「每格 16:9 堆三格就是 9:16」，实算是 16:27——差 5%，而 9:16 是 Story/Reels
     * 的硬规格，差这一点就得二次裁切，那一档于是并不能直接用。
     */
    @Test
    fun `label states the cell ratio and the stacked canvas ratio together`() {
        assertEquals("每格 16:9 → 整体 16:27", Aspect.R16_9.label(3))
        assertEquals("每格 1:1 → 整体 1:3", Aspect.R1_1.label(3))
        assertEquals("每格 4:5 → 整体 4:15", Aspect.R4_5.label(3))
        assertEquals("每格 27:16 → 整体 9:16", Aspect.R27_16.label(3))
    }

    @Test
    fun `the story cell is the only one whose stacked canvas is exactly 9 by 16`() {
        val stories = Aspect.entries.filter {
            it.targetW.toFloat() / (it.targetH * 3) == 9f / 16f
        }
        assertEquals(
            "必须有且只有一档能直接产出 1080×1920，否则「发故事」这一档是虚的",
            listOf(Aspect.R27_16),
            stories
        )
    }

    /** ratio 决定裁切窗口，targetW/targetH 决定转码归一的格子；两者必须是同一个比例 */
    @Test
    fun `every entry's declared ratio is its cell ratio`() {
        Aspect.entries.forEach {
            assertEquals(
                "${it.name} 的 ratio 必须等于 targetW/targetH",
                it.targetW.toDouble() / it.targetH,
                it.ratio.toDouble(),
                1e-4
            )
        }
    }
}
