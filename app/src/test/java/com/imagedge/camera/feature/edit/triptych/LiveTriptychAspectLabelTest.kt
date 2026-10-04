package com.imagedge.camera.feature.edit.triptych

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 三拼档位标签的算术，以及比例×画质推导出的格子像素
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
        val stories = Aspect.entries.filter { it.ratio / 3f == 9f / 16f }
        assertEquals(
            "必须有且只有一档能直接产出 9:16 成品，否则「发故事」这一档是虚的",
            listOf(Aspect.R27_16),
            stories
        )
    }

    // ── 比例 × 画质 → 格子像素 ──────────────────────────────────

    // 期望值写成 CellSize 而不是「1920 to 1080」这样的对子：Pair 与 CellSize 是
    // 互不相干的两种类型，JUnit 走的是 assertEquals(Object, Object)，
    // 编译能过、运行时恒不相等——那样的断言钉不住任何东西
    @Test
    fun `1080p 档位下每格的像素与规格一致`() {
        assertEquals(CellSize(1920, 1080), Aspect.R16_9.cellSize(Quality.P1080))
        assertEquals(CellSize(1080, 1080), Aspect.R1_1.cellSize(Quality.P1080))
        assertEquals(CellSize(1080, 1350), Aspect.R4_5.cellSize(Quality.P1080))
        assertEquals(CellSize(1080, 640), Aspect.R27_16.cellSize(Quality.P1080))
    }

    @Test
    fun `720p 档位等比缩小且不放大`() {
        assertEquals(CellSize(1280, 720), Aspect.R16_9.cellSize(Quality.P720))
        assertEquals(CellSize(720, 720), Aspect.R1_1.cellSize(Quality.P720))
        assertEquals(CellSize(720, 900), Aspect.R4_5.cellSize(Quality.P720))
        // 27:16 的短边是 640，本来就小于 720 的上限，所以**不缩**
        assertEquals(CellSize(1080, 640), Aspect.R27_16.cellSize(Quality.P720))
    }

    /**
     * 这一条钉的是 spec §6.1 的第 3 步。720p 下 27:16 的长边是
     * `720 × 27/16 = 1215`——奇数。H.264 的 yuv420 要求偶数宽高，
     * Media3 Transformer 会在那一步失败或静默拒绝。
     * 原实现写死 1080×640（偶数）所以从未暴露，引入画质档位才会撞上。
     */
    @Test
    fun `任何档位下两条边都是偶数`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val size = aspect.cellSize(quality)
                assertEquals("${aspect.name}/${quality.name} 宽必须是偶数", 0, size.width % 2)
                assertEquals("${aspect.name}/${quality.name} 高必须是偶数", 0, size.height % 2)
            }
        }
    }

    @Test
    fun `短边不超过该档位的上限`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val size = aspect.cellSize(quality)
                assertTrue(
                    "${aspect.name}/${quality.name} 短边 ${minOf(size.width, size.height)} 超过 ${quality.shortSideCap}",
                    minOf(size.width, size.height) <= quality.shortSideCap
                )
            }
        }
    }

    @Test
    fun `推导出的格子比例与档位声明的比例一致`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val size = aspect.cellSize(quality)
                assertEquals(
                    "${aspect.name}/${quality.name} 取偶后比例会有微小偏差，但不得偏差超过 1%",
                    aspect.ratio.toDouble(),
                    size.width.toDouble() / size.height,
                    aspect.ratio * 0.01
                )
            }
        }
    }
}