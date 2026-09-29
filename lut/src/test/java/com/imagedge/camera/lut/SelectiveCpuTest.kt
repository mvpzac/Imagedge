package com.imagedge.camera.lut

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPU 侧接区间键控的两条底线：未命中的像素逐位不变（局部不该碰框外的一切），
 * 掩码模式输出的是权重本身而不是画面。这两条各自都有变异在背后等着（见计划 Step 5）。
 */
class SelectiveCpuTest {

    /** 两个像素：[暗, 亮]，各 RGBA。ByteArray 没有 `+` 运算符，所以手写 */
    private fun twoPixels(dark: Int, light: Int): ByteArray {
        val px = ByteArray(8)
        for (i in 0..2) px[i] = dark.toByte()
        px[3] = 255.toByte()
        for (i in 4..6) px[i] = light.toByte()
        px[7] = 255.toByte()
        return px
    }

    private fun spec(axis: KeyAxis = KeyAxis.LUMA, exposure: Int = 60) = SelectiveSpec(
        RangeKey.of(axis, 0.5f, 0.9f, feather = 0.05f),
        SelectiveAdjust(exposure = exposure)
    )

    @Test
    fun `pixels outside the range come back bit identical`() = runBlocking {
        // 左半暗（在区间外）、右半亮（在区间内）；局部只加曝光。
        // 未命中那一半必须逐位不变——「局部不该碰未命中区」是这条特性的底线
        val out = CpuLutProcessor().apply(
            twoPixels(20, 240), 2, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE, selective = spec()
        )

        assertEquals(twoPixels(20, 20).take(4), out.take(4))
        assertNotEquals(twoPixels(240, 240).drop(4).toList(), out.drop(4).toList())
    }

    @Test
    fun `mask mode outputs the weight as undithered grey`() = runBlocking {
        val out = CpuLutProcessor().apply(
            twoPixels(20, 255), 2, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE,
            selective = spec(), maskMode = true
        )

        assertEquals(0, out[0].toInt() and 0xFF)          // 区间外权重 0
        assertEquals(255, out[4].toInt() and 0xFF)        // 区间内权重 1
        assertEquals(255, out[7].toInt() and 0xFF)        // alpha 满
    }

    @Test
    fun `a pixel in the feather zone lands between untouched and full effect`() = runBlocking {
        // 三像素：暗（在区间外）、中间亮度（在羽化带里，w 约 0.5）、亮（在区间内）。
        // 中间那一档必须**严格落在**「不施加」与「满额施加」之间——这才是「按权重插值」的定义；
        // 少了它，把 w 丢掉、直接满额施加的实现也能全绿。
        val spec = SelectiveSpec(
            RangeKey.of(KeyAxis.LUMA, 0.55f, 0.85f, feather = 0.15f),
            SelectiveAdjust(exposure = 80)
        )
        val cpu = CpuLutProcessor()
        val input = threePixels(20, 120, 250)
        val untouched = cpu.apply(input, 3, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE)
        val selective = cpu.apply(input, 3, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE, selective = spec)
        // 「满额」= 换一个把整条轴都盖住的键（w ≡ 1），不是同一个键。to+feather 必须 <= 1
        val full = cpu.apply(input, 3, 1, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE,
            selective = SelectiveSpec(
                RangeKey.of(KeyAxis.LUMA, 0f, 0.99f, feather = RangeKey.MIN_FEATHER),
                spec.adjust
            ))

        fun byteAt(a: ByteArray, i: Int) = a[i].toInt() and 0xFF
        // 中间像素：未施加的值 < 选择性后的值，且 < 满额的值（w<1 所以插值在两者之间）
        val midUntouched = byteAt(untouched, 4)
        val midSelective = byteAt(selective, 4)
        val midFull = byteAt(full, 4)
        assertTrue("未施加到中间像素: $midUntouched", midSelective > midUntouched)
        // 差距要够大才说明「按 w 插值」：只断言严格大于的话，满额与「忽略 w」在亮部
        // 只差 1 个级（编码曲线在那里压得很扁），那种情况下断言等于没有牙
        assertTrue("满额($midFull) 与羽化带插值($midSelective) 差得太小，测不出权重：$midFull - $midSelective", midFull - midSelective > 8)
    }

    private fun threePixels(dark: Int, mid: Int, light: Int): ByteArray {
        val px = ByteArray(12)
        val vals = intArrayOf(dark, mid, light)
        for (pxIdx in 0..2) {
            for (c in 0..2) px[pxIdx * 4 + c] = vals[pxIdx].toByte()
            px[pxIdx * 4 + 3] = 255.toByte()
        }
        return px
    }
}
