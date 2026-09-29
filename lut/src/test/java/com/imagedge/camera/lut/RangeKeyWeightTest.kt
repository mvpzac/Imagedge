package com.imagedge.camera.lut

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 键控数学的边界。这里的每一条都对应 CPU/GPU 最可能分家、或用户最可能觉得「坏了」的地方：
 * 对数地板、梯形平台与缓变中点、反选、色相跨红端接缝、纯灰不属于任何色相区间、羽化下限。
 */
class RangeKeyWeightTest {

    private fun key(axis: KeyAxis, from: Float, to: Float, feather: Float = 0.1f, inverted: Boolean = false) =
        RangeKey.of(axis, from, to, feather, inverted)

    @Test
    fun `luma is log mapped with a floor at minus twelve ev`() {
        // 全白线性光 → (log2(1)+12)/20 = 0.6；全黑与地板以下 → 0
        // 诚实说明：**这条用例证明不了地板本身**——Kotlin 的 log2(0) = -Infinity，
        // coerceIn(0,1) 同样把它压到 0，所以把 max(l, 2^-12) 删掉这条照样绿。
        // 地板存在的理由是 GLSL 那边：log2(0.0) 在着色器里是未定义，而 max(NaN, x) 逐驱动不同。
        // 真正钉住它的是仪器化的 CPU/GPU 同值测试（输入含全黑行与地板以下行）。
        assertEquals(0.6f, RangeKeyWeight.keyQuantity(KeyAxis.LUMA, 1f, 1f, 1f), 1e-6f)
        assertEquals(0f, RangeKeyWeight.keyQuantity(KeyAxis.LUMA, 0f, 0f, 0f), 1e-6f)
        assertEquals(
            0f,
            RangeKeyWeight.keyQuantity(KeyAxis.LUMA, 2f.pow(-13f), 0f, 0f),
            1e-6f
        )
    }

    @Test
    fun `the trapezoid is one on the plateau and zero outside`() {
        val k = key(KeyAxis.LUMA, 0.4f, 0.6f)
        // 全白线性光 = x 0.6 → 平台右缘，权重 1；全黑 = x 0 → 梯形左外，权重 0
        assertEquals(1f, RangeKeyWeight.weight(k, 1f, 1f, 1f), 1e-6f)
        assertEquals(0f, RangeKeyWeight.weight(k, 0f, 0f, 0f), 1e-6f)
        // 缓变中点：x = from - feather/2 = 0.35 → smoothstep 中点 0.5
        val midLuma = 2f.pow(0.35f * 20f - 12f)
        assertEquals(0.5f, RangeKeyWeight.weight(k, midLuma, midLuma, midLuma), 1e-5f)
    }

    @Test
    fun `inverting swaps plateau and outside`() {
        val k = key(KeyAxis.LUMA, 0.4f, 0.6f, inverted = true)
        assertEquals(0f, RangeKeyWeight.weight(k, 1f, 1f, 1f), 1e-6f)
        assertEquals(1f, RangeKeyWeight.weight(k, 0f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `a hue range may cross the red seam`() {
        // from 0.9 / to 1.1：h=0.95 与 h=0.05 都在平台内，h=0.5 在外。
        // 红（h=0）正是接缝，不跨就永远键不到它
        val k = key(KeyAxis.HUE, 0.9f, 1.1f, feather = RangeKey.MIN_FEATHER)
        assertEquals(1f, RangeKeyWeight.weight(k, 1f, 0f, 0f), 1e-4f)      // 纯红 h=0
        assertEquals(0f, RangeKeyWeight.weight(k, 0f, 1f, 0f), 1e-4f)      // 纯绿 h≈0.33
    }

    @Test
    fun `grey pixels belong to no hue range`() {
        // max == min 时 rgbToHsv 的 h 是 0；若不特判，「只推红色」会把灰天点亮
        val k = key(KeyAxis.HUE, 0.9f, 1.1f)
        val inv = key(KeyAxis.HUE, 0.9f, 1.1f, inverted = true)
        assertEquals(0f, RangeKeyWeight.weight(k, 0.5f, 0.5f, 0.5f), 1e-6f)
        assertEquals(1f, RangeKeyWeight.weight(inv, 0.5f, 0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun `a degenerate feather cannot be constructed`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RangeKey.of(KeyAxis.LUMA, 0.4f, 0.6f, feather = 0f)
        }
        assertEquals(true, e.message!!.contains("羽化"))
    }

    @Test
    fun `the upper knot is clamped per axis`() {
        // 亮度/饱和度：to + feather 必须 <= 1，否则 §2.3 的第二个梯形会在最暗端绕回；
        // 色相允许到 2（跨接缝）
        assertThrows(IllegalArgumentException::class.java) {
            RangeKey.of(KeyAxis.LUMA, 0.95f, 0.95f, feather = 0.1f)
        }
        RangeKey.of(KeyAxis.HUE, 0.95f, 0.95f, feather = 0.1f) // 不抛
    }
}
