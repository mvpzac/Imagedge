package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/03
 *     desc   : 监看叠加层的采样格 ↔ 预览像素映射
 * </pre>
 *
 * 尺寸刻意选**非方形、且宽高都不是 stride 整除**的：正是这几种尺寸下 ceil 除法
 * 与坐标回投最容易错，而错的表现只是"条纹和亮部对不上"，极难定位。
 */
class AssistGridMappingTest {

    private fun gray(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test
    fun `grid size rounds up and never collapses`() {
        // 37x19 配 stride 3 → ceil(37/3)=13, ceil(19/3)=7
        assertEquals(13 to 7, ExposureAnalysis.gridSize(37, 19, 3))
        // 整除时不多出一格
        assertEquals(10 to 5, ExposureAnalysis.gridSize(30, 15, 3))
        // stride 大于尺寸时也至少是 1×1，不能是 0×0（0 会让绘制侧除零）
        assertEquals(1 to 1, ExposureAnalysis.gridSize(2, 2, 8))
        // stride ≤ 0 退化为 1，与采样路径同款兜底
        assertEquals(4 to 2, ExposureAnalysis.gridSize(4, 2, 0))
    }

    @Test
    fun `grid size always covers the frame`() {
        // 不变式：格数 × stride 必须不小于原尺寸，否则末尾会有一段永远没被采样
        val cases = listOf(
            37 to 19, 1 to 1, 2 to 2, 100 to 7, 7 to 100, 640 to 480, 13 to 13
        )
        for (stride in listOf(1, 2, 3, 5, 8, 64)) {
            for ((w, h) in cases) {
                val (gw, gh) = ExposureAnalysis.gridSize(w, h, stride)
                val step = stride.coerceAtLeast(1)
                assertTrue("${w}x$h stride=$stride 横向没盖满", gw * step >= w)
                assertTrue("${w}x$h stride=$stride 纵向没盖满", gh * step >= h)
            }
        }
    }

    @Test
    fun `zebra mask matches a naive per-sample reference on a mixed frame`() {
        val width = 37
        val height = 19
        val threshold = 200
        // 左半暗右半亮，中间再插一条亮带
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            gray(
                when {
                    x < 10 -> 20
                    x in 20..24 -> 255
                    else -> 120
                }
            )
        }

        val actual = ExposureAnalysis.zebraMask(pixels, width, height, 3, threshold)
        val (gw, _) = ExposureAnalysis.gridSize(width, height, 3)

        // 参考实现：先把每行的命中位读成字符串，再逐行与实现比对——避免两处用同一种下标算术
        val expectedRows = (0 until height step 3).map { y ->
            (0 until width step 3).map { x ->
                ExposureAnalysis.lumaOf(pixels[y * width + x]) >= threshold
            }
        }
        assertEquals(expectedRows.size * gw, actual.size)
        expectedRows.forEachIndexed { oy, row ->
            assertEquals("第 $oy 行不一致", row.toBooleanArray().toList(), actual.slice(oy * gw until (oy + 1) * gw).toList())
        }
    }

    @Test
    fun `zebra mask is silent on a flat frame and full on a blown one`() {
        val dark = IntArray(20 * 10) { gray(10) }
        val blown = IntArray(20 * 10) { gray(250) }

        val darkMask = ExposureAnalysis.zebraMask(dark, 20, 10, 2)
        val blownMask = ExposureAnalysis.zebraMask(blown, 20, 10, 2)

        assertTrue(darkMask.none { it })
        assertTrue(blownMask.all { it })
    }

    @Test
    fun `zebra mask honours the threshold exactly at the boundary`() {
        // 阈值就是 235：恰好等于算命中，低一档不算
        val on = IntArray(4 * 4) { gray(235) }
        val justUnder = IntArray(4 * 4) { gray(234) }

        assertTrue(ExposureAnalysis.zebraMask(on, 4, 4, 1).all { it })
        assertFalse(ExposureAnalysis.zebraMask(justUnder, 4, 4, 1).any { it })
    }

    @Test
    fun `zebra mask shares the peak grid so the two overlays line up`() {
        val pixels = IntArray(37 * 19) { gray((it % 256)) }

        val (zw, zh) = ExposureAnalysis.gridSize(37, 19, 3)
        val peaks = ExposureAnalysis.peaks(ExposureAnalysis.peakDensity(pixels, 37, 19, 3))
        val zebra = ExposureAnalysis.zebraMask(pixels, 37, 19, 3)

        // 两张叠加层必须落在同一套格子上，否则条纹与峰值会互相错开一格
        assertEquals(zw * zh, zebra.size)
        assertEquals(peaks.size, zebra.size)
    }

    @Test
    fun `row runs merge contiguous hits without changing the covered cells`() {
        val mask = booleanArrayOf(
            false, false, true, true, true, false, true, false, true, true, false
        )

        val runs = ExposureAnalysis.rowRuns(mask, 0, mask.size)

        assertEquals(listOf(2 until 5, 6 until 7, 8 until 10), runs)
        // 合并只改绘制次数：把各段展开回去必须与原掩码逐位相同
        val rebuilt = BooleanArray(mask.size)
        runs.forEach { range -> for (i in range) rebuilt[i] = true }
        assertEquals(mask.toList(), rebuilt.toList())
    }

    @Test
    fun `row runs handle the degenerate rows`() {
        assertEquals(emptyList<IntRange>(), ExposureAnalysis.rowRuns(BooleanArray(5), 0, 5))
        assertEquals(emptyList<IntRange>(), ExposureAnalysis.rowRuns(BooleanArray(0), 0, 0))
        // 全命中 → 整行一段
        assertEquals(listOf(0 until 4), ExposureAnalysis.rowRuns(BooleanArray(4) { true }, 0, 4))
        // offset=2 起取 4 格，即绝对下标 2..5（内容 false,false,true,false）。
        // 返回的是**相对 offset 的下标**，绘制侧据此乘 stride 换算回预览坐标——
        // 返回绝对下标会让第二行以后的掩码整体左移一整行
        val mask = booleanArrayOf(true, true, false, false, true, false)
        assertEquals(listOf(2 until 3), ExposureAnalysis.rowRuns(mask, 2, 4))
    }
}