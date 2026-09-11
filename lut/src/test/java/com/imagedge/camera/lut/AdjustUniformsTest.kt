package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调色换算单测。
 *
 * 这组数值是 **CPU 查表与 GPU 着色器共用的唯一来源**：
 * 两边一旦不一致，用户在同一台设备上从 GPU 回退到 CPU 时会看到画面突变。
 */
class AdjustUniformsTest {

    private val eps = 1e-4f

    @Test
    fun `identity adjust leaves every factor neutral`() {
        val u = AdjustUniforms.of(ColorAdjust.NONE)
        assertEquals(AdjustUniforms.IDENTITY, u)
    }

    @Test
    fun `exposure maps to two to the power of stops`() {
        // 滑条 +50 → 0.5 档 × 1.6 = 0.8 档 → 增益 2^0.8 ≈ 1.7411
        val up = AdjustUniforms.of(ColorAdjust(exposure = 50))
        assertEquals(Math.pow(2.0, 0.8).toFloat(), up.gainR, 1e-3f)
        assertEquals(up.gainR, up.gainG, eps)
        assertEquals(up.gainG, up.gainB, eps)
        val down = AdjustUniforms.of(ColorAdjust(exposure = -50))
        assertTrue("负曝光应压暗：${down.gainG}", down.gainG < 1f)
    }

    @Test
    fun `temperature raises red and lowers blue by 12 percent at full scale`() {
        val warm = AdjustUniforms.of(ColorAdjust(temperature = 100))
        assertEquals(1.12f, warm.gainR, 1e-3f)
        assertEquals(1f, warm.gainG, eps)
        assertEquals(0.88f, warm.gainB, 1e-3f)
        val cool = AdjustUniforms.of(ColorAdjust(temperature = -100))
        assertTrue(cool.gainR < 1f && cool.gainB > 1f)
    }

    @Test
    fun `contrast and saturation use their documented amplitudes`() {
        assertEquals(1.65f, AdjustUniforms.of(ColorAdjust(contrast = 100)).contrast, 1e-3f)
        assertEquals(0.35f, AdjustUniforms.of(ColorAdjust(contrast = -100)).contrast, 1e-3f)
        assertEquals(1f, AdjustUniforms.of(ColorAdjust()).saturation, eps)
        // 去色必须真的等于全灰
        assertEquals(0f, AdjustUniforms.of(ColorAdjust(saturation = -100)).saturation, eps)
        assertEquals(2.2f, AdjustUniforms.of(ColorAdjust(saturation = 100)).saturation, 1e-3f)
    }

    /**
     * 一致性护栏：CPU 处理器对中灰的曝光结果必须等于 uniform 增益的直接结果。
     * 若有人改了其中一边的公式，这里会立刻红。
     */
    @Test
    fun `cpu processor matches shared uniforms for exposure`() = runBlocking {
        val processor = CpuLutProcessor()
        val src = byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 255.toByte())
        val adjust = ColorAdjust(exposure = 50)
        val u = AdjustUniforms.of(adjust)
        // CPU 查表按四舍五入取整（+0.5 再截断），这里保持同一套取整方式
        val expected = ((128f / 255f) * u.gainG * 255f + 0.5f).toInt().coerceIn(0, 255)
        val out = processor.applyAdjustOnly(src, 1, 1, adjust)
        assertEquals(expected, out[1].toInt() and 0xFF)
    }
}
