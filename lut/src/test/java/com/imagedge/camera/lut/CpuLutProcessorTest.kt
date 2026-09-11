package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 基础调色（[ColorAdjust]）回归测试。
 *
 * 覆盖新增能力：曝光/对比度/色温/饱和度在 LUT 之前应用，
 * 以及「强度=0 保留调色、强度=100 完整套 LUT」的混合语义。
 */
class ColorAdjustTest {

    private val processor = CpuLutProcessor()

    /** 构造 1×N 的 RGBA 像素：每像素给定 (r,g,b,255) */
    private fun pixels(vararg rgb: Triple<Int, Int, Int>): ByteArray {
        val out = ByteArray(rgb.size * 4)
        rgb.forEachIndexed { i, (r, g, b) ->
            out[i * 4] = r.toByte()
            out[i * 4 + 1] = g.toByte()
            out[i * 4 + 2] = b.toByte()
            out[i * 4 + 3] = 255.toByte()
        }
        return out
    }

    private fun ByteArray.pixel(i: Int): Triple<Int, Int, Int> = Triple(
        this[i * 4].toInt() and 0xFF,
        this[i * 4 + 1].toInt() and 0xFF,
        this[i * 4 + 2].toInt() and 0xFF,
    )

    @Test
    fun `identity adjust without lut is a pass through`() = runBlocking {
        val src = pixels(Triple(80, 120, 200))
        val out = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE)
        assertEquals(80, out.pixel(0).first)
        assertEquals(120, out.pixel(0).second)
        assertEquals(200, out.pixel(0).third)
    }

    @Test
    fun `positive exposure brightens and negative darkens`() = runBlocking {
        val src = pixels(Triple(100, 100, 100))
        val brighter = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(exposure = 50))
        val darker = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(exposure = -50))
        assertTrue("曝光 +50 应变亮（实际 ${brighter.pixel(0).first}）", brighter.pixel(0).first > 100)
        assertTrue("曝光 -50 应变暗（实际 ${darker.pixel(0).first}）", darker.pixel(0).first < 100)
    }

    @Test
    fun `temperature shifts red and blue in opposite directions`() = runBlocking {
        val src = pixels(Triple(128, 128, 128))
        val warm = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(temperature = 80))
        val cool = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(temperature = -80))
        val w = warm.pixel(0)
        val c = cool.pixel(0)
        assertTrue("暖色应加红减蓝（实际 $w）", w.first > 128 && w.third < 128)
        assertTrue("冷色应加蓝减红（实际 $c）", c.first < 128 && c.third > 128)
    }

    @Test
    fun `saturation minus 100 produces neutral gray`() = runBlocking {
        val src = pixels(Triple(200, 60, 20))
        val out = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(saturation = -100))
        val (r, g, b) = out.pixel(0)
        // 完全去色后三通道应相等（±1 取整误差）
        assertTrue("去色后应为灰（实际 $r,$g,$b）", kotlin.math.abs(r - g) <= 1 && kotlin.math.abs(g - b) <= 1)
    }

    @Test
    fun `contrast zero pivot keeps mid gray unchanged`() = runBlocking {
        val src = pixels(Triple(128, 128, 128))
        val out = processor.apply(src, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(contrast = 100))
        assertEquals("中灰是对比度轴心，不应移动", 128, out.pixel(0).first)
    }

    @Test
    fun `lut strength zero keeps adjustments but skips lut`() = runBlocking {
        // 2 点 3D LUT：把一切映射成纯蓝
        val size = 2
        val data = FloatArray(size * size * size * 3)
        for (i in 0 until size * size * size) {
            data[i * 3] = 0f; data[i * 3 + 1] = 0f; data[i * 3 + 2] = 1f
        }
        val src = pixels(Triple(100, 100, 100))
        val atZero = processor.apply(src, 1, 1, data, size, 0, ColorAdjust(exposure = 40))
        val atFull = processor.apply(src, 1, 1, data, size, 100, ColorAdjust(exposure = 40))
        // 强度 0：只调色、不套 LUT → 仍是灰且比原图亮
        val z = atZero.pixel(0)
        assertTrue("强度 0 应保留曝光调整（实际 $z）", z.first > 100 && z.third > 100)
        // 强度 100：完整 LUT → 蓝通道饱和
        val f = atFull.pixel(0)
        assertTrue("强度 100 应输出 LUT 结果（实际 $f）", f.third > f.first)
    }
}

class CpuLutProcessorTest {

    private val processor = CpuLutProcessor()

    private fun identityLut(size: Int = 4): CubeLut =
        CubeLutParser.generate(size) { r, g, b -> Triple(r, g, b) }

    @Test
    fun `strength zero returns original`() = runBlocking {
        val pixels = byteArrayOf(10, 20, 30, -1, 40, 50, 60, -1)
        val lut = identityLut()
        val out = processor.apply(pixels, 2, 1, lut.data, lut.size, 0)
        assertArrayEquals(pixels, out)
    }

    @Test
    fun `identity lut preserves pixels within rounding`() = runBlocking {
        val pixels = byteArrayOf(0, 127, -128, -1, -1, 0, 1, -1)
        val lut = identityLut()
        val out = processor.apply(pixels, 2, 1, lut.data, lut.size, 100)
        for (i in pixels.indices) {
            if (i % 4 == 3) {
                // alpha 通道原样透传
                assertEquals(pixels[i], out[i])
            } else {
                val diff = (out[i].toInt() and 0xFF) - (pixels[i].toInt() and 0xFF)
                assert(abs(diff) <= 1) { "通道 $i 漂移 $diff（恒等 LUT 不应改变像素）" }
            }
        }
    }

    @Test
    fun `all-white lut maps black to white`() = runBlocking {
        val lut = CubeLutParser.generate(2) { _, _, _ -> Triple(1f, 1f, 1f) }
        val pixels = byteArrayOf(0, 0, 0, -1)
        val out = processor.apply(pixels, 1, 1, lut.data, lut.size, 100)
        assertEquals(255, out[0].toInt() and 0xFF)
        assertEquals(255, out[1].toInt() and 0xFF)
        assertEquals(255, out[2].toInt() and 0xFF)
        // alpha 不受 LUT 影响
        assertEquals(-1, out[3].toInt())
    }

    @Test
    fun `degenerate lut passes through without crash`() = runBlocking {
        val pixels = byteArrayOf(1, 2, 3, 4)
        // lutSize < 2 无法插值：必须原样返回而非负索引越界
        val out = processor.apply(pixels, 1, 1, FloatArray(3), 1, 100)
        assertArrayEquals(pixels, out)
    }

    @Test
    fun `strength blends between original and lut result`() = runBlocking {
        val lut = CubeLutParser.generate(2) { _, _, _ -> Triple(1f, 1f, 1f) }
        val pixels = byteArrayOf(0, 0, 0, -1)
        val out = processor.apply(pixels, 1, 1, lut.data, lut.size, 50)
        val v = out[0].toInt() and 0xFF
        assert(v in 126..128) { "50% 强度应约为 127，实际 $v" }
    }
}
