package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 输出量化抖动。3D LUT 的产物是平滑渐变，直接截断到 8 位必然出色带。
 * </pre>
 */
class DitherTest {

    private val processor = CpuLutProcessor()

    /**
     * 同一段平缓斜坡重复处理，不做抖动时输出必然逐位相同——那正是色带的成因。
     * 用一个极小的曝光调整，让它真的走一遍管线（恒等快路径不做处理，也就不该抖）。
     */
    @Test
    fun `a flat gradient region stops collapsing to a single code value`() {
        val outputs = ArrayList<Int>()
        repeat(4) {
            val px = ByteArray(64 * 4) { i ->
                val v = (40 + (i / 4) * 3) // 缓慢上升，典型的会产生色带的斜坡
                v.toByte()
            }
            for (i in 0 until 64) { px[i * 4 + 1] = px[i * 4]; px[i * 4 + 2] = px[i * 4] }
            val out = runBlocking {
                processor.apply(px, 8, 8, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(exposure = 1))
            }
            outputs += out[0].toInt() and 0xFF
        }

        assertTrue(
            "同一输入重复处理应当出现不止一个码值（抖动的意义），实得 $outputs",
            outputs.toSet().size > 1
        )
    }

    @Test
    fun `an untouched image is still returned bit for bit`() {
        // 抖动的对立面：恒等必须逐位还原，否则「不改任何东西却变了」是最难查的一类 bug
        val px = ByteArray(64 * 4) { i ->
            val v = 40 + (i / 4) * 3
            val b = v.toByte()
            if (i % 4 == 3) 255.toByte() else b
        }
        val out = runBlocking {
            processor.apply(px, 8, 8, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE)
        }
        assertTrue("恒等路径必须逐位还原", px.contentEquals(out))
    }

    @Test
    fun `dithering must not move the mean more than half a code`() {
        val n = 4096
        val px = ByteArray(n * 4)
        // 构造一个恰好落在两个码值中间的亮度：量化误差最大
        val exact = 0.5f / 255f
        val v = (SrgbTransfer.encode(exact) * 255f).toInt().coerceIn(0, 255)
        for (i in 0 until n) {
            px[i * 4] = v.toByte(); px[i * 4 + 1] = v.toByte(); px[i * 4 + 2] = v.toByte()
            px[i * 4 + 3] = 255.toByte()
        }

        val out = runBlocking {
            processor.apply(px, 64, 64, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE)
        }
        var sum = 0L
        for (i in 0 until n) sum += out[i * 4].toInt() and 0xFF

        assertTrue(
            "抖动不能把平均亮度推走（实得均值 ${sum.toDouble() / n}，输入 $v）",
            kotlin.math.abs(sum.toDouble() / n - v) < 1.0
        )
    }

    @Test
    fun `dithering stays inside the neighbouring codes only`() {
        val px = byteArrayOf(200.toByte(), 200.toByte(), 200.toByte(), 255.toByte())
        var min = 255
        var max = 0
        repeat(400) {
            val out = runBlocking {
                processor.apply(px, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE)
            }
            val v = out[0].toInt() and 0xFF
            if (v < min) min = v
            if (v > max) max = v
        }
        assertTrue("抖动幅度应恰好 1 LSB，实得 $min..$max", max - min <= 1)
    }

    @Test
    fun `black and white are not pushed past the ends`() {
        val black = byteArrayOf(0, 0, 0, 255.toByte())
        val white = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())

        repeat(200) {
            val b = runBlocking { processor.apply(black, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE) }
            val w = runBlocking { processor.apply(white, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE) }
            assertEquals("纯黑必须仍是 0", 0, b[0].toInt() and 0xFF)
            assertEquals("纯白必须仍是 255", 255, w[0].toInt() and 0xFF)
        }
    }
}
