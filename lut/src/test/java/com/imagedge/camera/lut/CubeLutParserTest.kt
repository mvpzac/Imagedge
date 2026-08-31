package com.imagedge.camera.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CubeLutParserTest {

    private fun cubeText(size: Int, value: Float = 0.5f): String = buildString {
        appendLine("TITLE \"test\"")
        appendLine("# 注释行应被跳过")
        appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        appendLine("LUT_3D_SIZE $size")
        repeat(size * size * size) { appendLine("$value $value $value") }
    }

    @Test
    fun `parses valid cube with metadata lines`() {
        val lut = CubeLutParser.parse(cubeText(2))
        assertNotNull(lut)
        assertEquals(2, lut!!.size)
        assertEquals(24, lut.data.size)
        assertEquals(0.5f, lut.data[0])
    }

    @Test
    fun `rejects 1D LUT`() {
        assertNull(CubeLutParser.parse("LUT_1D_SIZE 4\n0 0 0\n"))
    }

    @Test
    fun `rejects missing size`() {
        assertNull(CubeLutParser.parse("0.1 0.2 0.3\n"))
    }

    @Test
    fun `rejects truncated data`() {
        assertNull(CubeLutParser.parse("LUT_3D_SIZE 2\n0.1 0.2 0.3\n"))
    }

    @Test
    fun `rejects out of range size`() {
        assertNull(CubeLutParser.parse("LUT_3D_SIZE 1\n0 0 0\n"))
        assertNull(CubeLutParser.parse("LUT_3D_SIZE 100\n0 0 0\n"))
    }

    @Test
    fun `generate identity maps grid corners`() {
        val lut = CubeLutParser.generate(size = 2) { r, g, b -> Triple(r, g, b) }
        assertEquals(2, lut.size)
        // 索引布局 = r + g*size + b*size*size：(0,0,0) 在首位，(1,1,1) 在末位
        assertEquals(0f, lut.data[0])
        val last = (lut.size * lut.size * lut.size - 1) * 3
        assertEquals(1f, lut.data[last])
        assertEquals(1f, lut.data[last + 1])
        assertEquals(1f, lut.data[last + 2])
    }
}
