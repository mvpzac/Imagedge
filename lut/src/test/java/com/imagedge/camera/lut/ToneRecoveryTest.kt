package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 影调分区恢复（高光/阴影）与色调(tint) 轴的行为契约
 * </pre>
 */
class ToneRecoveryTest {

    private val processor = CpuLutProcessor()

    private fun gray(v: Int) = byteArrayOf(v.toByte(), v.toByte(), v.toByte(), 255.toByte())

    private fun ByteArray.luma() = this[0].toInt() and 0xFF

    private suspend fun apply(v: Int, adjust: ColorAdjust): Int =
        processor.apply(gray(v), 1, 1, LutProcessor.EMPTY_LUT, 0, 100, adjust).luma()

    private fun out(v: Int, adjust: ColorAdjust): Int = runBlocking { apply(v, adjust) }

    @Test
    fun `shadows lift the dark end without moving the bright end`() {
        // 阴影滑条的意义就是「只救暗部」。如果它等价于加曝光，那它不该存在。
        val shadows = ColorAdjust(shadows = 100)

        val darkGain = out(32, shadows) - 32
        val brightGain = out(220, shadows) - 220

        assertTrue("阴影必须明显抬起暗部，实测 $darkGain", darkGain > 8)
        assertTrue(
            "阴影不该动高光（暗部 +$darkGain vs 高光 +$brightGain）",
            kotlin.math.abs(darkGain) > kotlin.math.abs(brightGain) * 4
        )
    }

    @Test
    fun `highlights pull the bright end without moving the dark end`() {
        val highlights = ColorAdjust(highlights = -100)

        val darkDelta = out(32, highlights) - 32
        val brightDelta = out(220, highlights) - 220

        assertTrue("高光必须明显压下亮部，实测 $brightDelta", brightDelta < -8)
        assertTrue(
            "高光不该动暗部（亮部 $brightDelta vs 暗部 $darkDelta）",
            kotlin.math.abs(brightDelta) > kotlin.math.abs(darkDelta) * 4
        )
    }

    @Test
    fun `shadows and highlights are independent of exposure`() {
        // 三者叠在一起要能各自生效，而不是互相覆盖成同一个增益
        val both = ColorAdjust(shadows = 100, highlights = -100)

        assertTrue(
            "同时抬阴影压高光应当拉开明暗范围：32→${out(32, both)} vs 220→${out(220, both)}",
            out(32, both) - out(220, both) > out(32, ColorAdjust.NONE) - out(220, ColorAdjust.NONE) + 8
        )
    }

    @Test
    fun `tint is a green-magenta axis orthogonal to the red-blue temperature axis`() {
        // 此前色温是纯 R/B 跷跷板，绿通道增益恒为 1——没有绿/品轴，
        // 而荧光灯与部分 LED 下的偏色恰恰是这一轴，调不回来。
        val warm = AdjustUniforms.of(ColorAdjust(temperature = 100))
        assertEquals("色温不该动绿通道", 1f, warm.gainG, 1e-4f)
        assertTrue("色温暖向应当抬红压蓝", warm.gainR > 1f && warm.gainB < 1f)

        val green = AdjustUniforms.of(ColorAdjust(tint = 100))
        assertTrue("色调正向应当抬绿（实测 ${green.gainG}）", green.gainG > 1f)
        assertEquals("色调不该动红通道", 1f, green.gainR, 1e-4f)
        assertEquals("色调不该动蓝通道", 1f, green.gainB, 1e-4f)
    }

    @Test
    fun `tint actually shifts a grey away from neutral`() {
        // 灰在色调下**必须**不再相等——色调的定义就是打破三通道等比。
        // 这条与上一条（gainR/gainB 不变）合起来才说明动的是 G 而不是整体亮度。
        val px = byteArrayOf(120.toByte(), 120.toByte(), 120.toByte(), 255.toByte())
        val out = runBlocking {
            processor.apply(px, 1, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust(tint = -100))
        }
        val r = out[0].toInt() and 0xFF
        val g = out[1].toInt() and 0xFF

        assertTrue("负色调应当压绿（R=$r G=$g）", g < r)
    }

    @Test
    fun `identity still round-trips exactly with the new parameters present`() {
        // 反证：新增三个参数不能把恒等路径带偏
        for (v in listOf(0, 17, 64, 128, 200, 255)) {
            assertEquals("v=$v", v, out(v, ColorAdjust.NONE))
        }
        assertNotEquals(
            "shadows 非零时应当真的改变画面",
            out(32, ColorAdjust.NONE),
            out(32, ColorAdjust(shadows = 50)),
        )
    }
}
