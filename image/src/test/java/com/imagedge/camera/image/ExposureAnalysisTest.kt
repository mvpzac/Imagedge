package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 曝光辅助分析验收（T2）：合成灰阶/色块对照 CPU 参考实现
 *     version: 1.0
 * </pre>
 */

/**
 * T2 的验收方式是「合成灰阶/色块测阈值 + CPU 参考结果对照」，全部不需要相机。
 *
 * 参考实现故意写成最笨的那种（逐像素、分支、浮点）：优化的意义是快，
 * 而快不能靠改变结果来换。两边不一致就是这里算错了。
 */
class ExposureAnalysisTest {

    private fun gray(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    /** 宽 w、高 h 的纯色帧 */
    private fun flat(w: Int, h: Int, level: Int) = IntArray(w * h) { gray(level) }

    /** 左半黑右半白的帧（边缘在 x = w/2） */
    private fun splitFrame(w: Int, h: Int) = IntArray(w * h) { index ->
        val x = index % w
        gray(if (x < w / 2) 0 else 255)
    }

    @Test
    fun `histogram matches a naive reference loop on a grayscale ramp`() {
        val width = 37
        val height = 19
        val pixels = IntArray(width * height) { index -> gray(index % 256) }

        val actual = ExposureAnalysis.histogram(pixels, width, height)

        val reference = IntArray(ExposureAnalysis.BINS)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // 参考实现用浮点 + 四舍五入：与实现的整数乘除是两条不同的路，
            // 结果必须一致。（写成 toInt() 截断会在整数级灰阶上差 1，那是参考实现的错）
            reference[Math.round(r * 0.299 + g * 0.587 + b * 0.114).toInt()]++
        }
        assertContentEquals(reference, actual.buckets)
        assertEquals(width * height, actual.sampleCount)
    }

    @Test
    fun `a flat frame lands entirely in one bin`() {
        val frame = flat(16, 16, 128)

        val histogram = ExposureAnalysis.histogram(frame, 16, 16)

        assertEquals(128, histogram.buckets.indexOfFirst { it != 0 })
        assertEquals(256, histogram.sampleCount)
        assertEquals(128f, histogram.meanLuma(), 0.5f)
    }

    @Test
    fun `an empty sample set reports zero, not mid gray`() {
        // 没数据时说"中灰"会被读成"画面偏暗/偏亮"，而真实情况是什么都没测到
        assertEquals(0f, LumaHistogram(IntArray(ExposureAnalysis.BINS), 0).meanLuma(), 0f)
    }

    @Test
    fun `highlight ratio counts only pixels at or above the threshold`() {
        val half = splitFrame(20, 10)

        assertEquals(0.5f, ExposureAnalysis.highlightRatio(half, 20, 10), 1e-6f)
        assertEquals(0f, ExposureAnalysis.highlightRatio(flat(8, 8, 128), 8, 8), 0f)
        assertEquals(1f, ExposureAnalysis.highlightRatio(flat(8, 8, 255), 8, 8), 0f)
        // 阈值边界：235 算溢出，234 不算
        assertEquals(1f, ExposureAnalysis.highlightRatio(flat(4, 4, 235), 4, 4), 0f)
        assertEquals(0f, ExposureAnalysis.highlightRatio(flat(4, 4, 234), 4, 4), 0f)
    }

    @Test
    fun `stride shrinks a viewfinder frame to the analysis long edge`() {
        // 取景目标约 960×640 @20fps；首版要求长边降到约 320
        assertEquals(3, ExposureAnalysis.sampleStride(960, 640))
        assertEquals(1, ExposureAnalysis.sampleStride(320, 240))
        assertEquals("641 已经超出 320 一档，必须再降一级", 3, ExposureAnalysis.sampleStride(641, 400))
        assertEquals(2, ExposureAnalysis.sampleStride(640, 400))
    }

    @Test
    fun `sampling does not change the picture it is describing`() {
        val frame = splitFrame(40, 20)
        val full = ExposureAnalysis.highlightRatio(frame, 40, 20)
        val sampled = ExposureAnalysis.highlightRatio(
            frame, 40, 20, stride = ExposureAnalysis.sampleStride(40, 20)
        )

        assertEquals(full, sampled, 0.02f)
    }

    @Test
    fun `peaks fire on a hard edge and stay silent on a flat frame`() {
        val edge = splitFrame(21, 9)
        val map = ExposureAnalysis.peakDensity(edge, 21, 9)
        val flags = ExposureAnalysis.peaks(map)

        assertTrue("竖边缘那一列必须被标出来", flags.any { it })
        // 远离边缘的位置不该亮：只判断"有没有点亮"会让整屏都糊上噪点
        val edgeColumn = (21 / 2)
        assertTrue("左半内部必须安静", !flags[edgeColumn * 3 / 2])
        assertFalse(
            "纯色帧没有边缘，一个都不该亮",
            ExposureAnalysis.peaks(ExposureAnalysis.peakDensity(flat(16, 16, 90), 16, 16)).any { it }
        )
    }

    @Test
    fun `normalized histogram peaks at one`() {
        val histogram = ExposureAnalysis.histogram(splitFrame(10, 10), 10, 10)

        assertEquals(1f, histogram.normalized().max(), 1e-6f)
        assertEquals(0f, histogram.normalized().min(), 1e-6f)
    }

    private fun assertContentEquals(expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
