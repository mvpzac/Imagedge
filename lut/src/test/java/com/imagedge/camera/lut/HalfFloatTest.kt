package com.imagedge.camera.lut

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 半精度转换单测：位模式一旦错了，GPU 上的 LUT 会整体偏色（还很不容易看出来是编码问题）。
 * 期望值取自 IEEE 754 半精度表示的标准样例。
 */
class HalfFloatTest {

    private fun halfBits(value: Float): Int = HalfFloat.fromFloat(value).toInt() and 0xFFFF

    @Test
    fun `exact powers of two map to their canonical bit patterns`() {
        assertEquals(0x0000, halfBits(0f))
        assertEquals(0x3800, halfBits(0.5f))
        assertEquals(0x3400, halfBits(0.25f))
        assertEquals(0x3C00, halfBits(1f))
        assertEquals(0x4000, halfBits(2f))
        assertEquals(0x3E00, halfBits(1.5f))
    }

    @Test
    fun `one tenth rounds to the standard half representation`() {
        assertEquals(0x2E66, halfBits(0.1f))
    }

    @Test
    fun `negative values keep the sign bit`() {
        assertEquals(0xB800, halfBits(-0.5f))
        assertEquals(0x8000, halfBits(-0f))
    }

    @Test
    fun `very small values flush to zero and large ones saturate to infinity`() {
        assertEquals(0x0000, halfBits(1e-12f))
        assertEquals(0x7C00, halfBits(70000f))
        assertEquals(0x7C00, halfBits(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `lut range values are all inside zero and one`() {
        // LUT 系数是 0..1：转换结果必须单调不减，且端点精确
        var previous = -1
        var x = 0f
        while (x <= 1f) {
            val bits = halfBits(x)
            assertEquals("端点 0", 0x0000, halfBits(0f))
            if (previous >= 0) {
                assert(bits >= previous) { "半精度转换必须单调：$x → ${bits.toString(16)}" }
            }
            previous = bits
            x += 0.01f
        }
        assertEquals(0x3C00, halfBits(1f))
    }
}
