package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 调色的色彩空间契约：曝光/对比度/饱和度必须在**线性光**里做，3D LUT 必须在
 *              **sRGB 编码**空间里做。这两件事搞反任何一边，画质都是错的而且不易察觉。
 * </pre>
 */
class ColorScienceTest {

    private val processor = CpuLutProcessor()

    private fun gray(v: Int) = byteArrayOf(v.toByte(), v.toByte(), v.toByte(), 255.toByte())

    private fun ByteArray.channel(i: Int) = this[i].toInt() and 0xFF

    /** 参考实现：sRGB EOTF，独立于被测代码算期望值 */
    private fun srgbDecode(v: Int): Float {
        val c = v / 255.0
        return if (c <= 0.04045) (c / 12.92).toFloat() else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    private fun srgbEncode(l: Float): Float {
        if (l <= 0.0031308f) return l * 12.92f
        return (1.055 * Math.pow(l.toDouble(), 1.0 / 2.4) - 0.055).toFloat()
    }

    private fun assertNear(expected: Int, actual: Int, tol: Int, what: String) {
        assertEquals(
            "$what（期望 $expected ±$tol，实得 $actual）",
            expected.toDouble(), actual.toDouble(), tol.toDouble()
        )
    }

    @Test
    fun `one stop of exposure lands on the linear result, not on white`() {
        // 128 是最常见的中间调。gamma 空间里乘 2.0 会直接顶到 255；
        // 线性光里 128(sRGB) ≈ 0.216 → ×2 = 0.432 → 回 sRGB 约 176。
        val out = runBlocking {
            processor.apply(gray(128), 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(exposure = 62))
        }

        val expected = (srgbEncode(srgbDecode(128) * 2.0f) * 255f).toInt().coerceIn(0, 255)
        assertNear(expected, out.channel(0), 2, "+1 档后的中间调")
        assertTrue("+1 档不该把中间调削平成纯白，实得 ${out.channel(0)}", out.channel(0) < 250)
    }

    @Test
    fun `contrast pivots on middle grey rather than on encoded half`() {
        // 18% 灰在 sRGB 编码里约 118。绕它缩放时 118 附近应当是不动点。
        val out = runBlocking {
            processor.apply(gray(118), 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(contrast = 80))
        }

        assertNear(118, out.channel(0), 3, "18% 灰在提高对比度后应基本不动")
    }

    @Test
    fun `saturation collapsed to zero yields the linear luminance`() {
        val px = byteArrayOf(200.toByte(), 100.toByte(), 60.toByte(), 255.toByte())
        val out = runBlocking {
            processor.apply(px, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(saturation = -100))
        }

        val luma = 0.2126f * srgbDecode(200) + 0.7152f * srgbDecode(100) + 0.0722f * srgbDecode(60)
        val expected = (srgbEncode(luma) * 255f).toInt().coerceIn(0, 255)
        assertNear(expected, out.channel(0), 2, "去色后的 R")
        assertNear(expected, out.channel(1), 2, "去色后的 G")
        assertNear(expected, out.channel(2), 2, "去色后的 B")
    }

    @Test
    fun `desaturation clips shadows before the luma, exactly like the shader`() {
        // 对比度拉满会把暗道算成**负光量**。两条路径都只能在落进 8 位之前把它钳成黑，
        // 真正的分歧是钳在算亮度之前还是之后：着色器先 max(...,0) 再取亮度，
        // CPU 若留到编码之前才钳，参与亮度的就还是那个负的暗道——
        // 同一张照片在有没有 GPU 的机器上会掉进两个不同的灰。
        // 期望值按着色器的写法独立算一遍（钳位 → 亮度 → 直接作为灰）。
        val r = 20   // 对比度 100 下线性值约 -0.105，正是会被负值污染的那一格
        val g = 150
        val b = 150
        val px = byteArrayOf(r.toByte(), g.toByte(), b.toByte(), 255.toByte())
        val out = runBlocking {
            processor.apply(px, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(contrast = 100, saturation = -100))
        }

        fun clipped(v: Int): Float =
            ((srgbDecode(v) - 0.18f) * 1.65f + 0.18f).coerceAtLeast(0f)
        val luma = 0.2126f * clipped(r) + 0.7152f * clipped(g) + 0.0722f * clipped(b)
        val expected = (srgbEncode(luma) * 255f).toInt().coerceIn(0, 255)

        assertNear(expected, out.channel(0), 2, "按已钳位的通道算出的灰")
    }

    @Test
    fun `identity adjustment round trips an 8 bit value exactly`() {
        // 反证：上面的修正不能靠「整体偏一点」蒙混过关——不改任何东西就该逐位还原
        for (v in 0..255) {
            val out = runBlocking {
                processor.apply(gray(v), 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE)
            }
            assertEquals("v=$v 应当在无调色时原样返回", v, out.channel(0))
        }
    }

    @Test
    fun `a 3d lut still receives srgb encoded coordinates`() {
        // look LUT 定义在 sRGB 编码输入域上：坐标必须是编码值本身，不能是线性值——
        // 这正是「调色进线性、LUT 留 gamma」的分工。
        val size = 2
        val data = FloatArray(size * size * size * 3) { i -> if (i % 3 == 0) 1f else 0f }
        val px = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 255.toByte())

        val out = runBlocking { processor.apply(px, 1, 1, data, size, 100, ColorAdjust.NONE) }

        assertEquals(255, out.channel(0))
        assertEquals(0, out.channel(1))
        assertEquals(0, out.channel(2))
    }
}
