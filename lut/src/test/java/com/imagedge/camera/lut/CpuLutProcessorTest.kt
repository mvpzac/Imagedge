package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

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
