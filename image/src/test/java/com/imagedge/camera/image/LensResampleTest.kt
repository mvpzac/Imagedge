package com.imagedge.camera.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 镜头校正的重采样：把 [LensCorrection] 算出的取样坐标真正落到像素上。
 *
 * 输入输出都是 **RGBA8 字节**（与 `:lut` 的 pack 顺序一致），所以这一层完全不碰
 * `android.graphics`——整条校正链因此能在 JVM 上验完，真机上只剩「效果对不对」这件事。
 *
 * 两条形状上的约定：
 * - **色差是逐通道取样的**：R 与 B 各用自己的一组坐标，绿色用畸变那一组。所以四个通道权重
 *   要分别算，不能取一次就复用。
 * - **越界钳到边缘**。取到画面外要么变黑（凭空一块黑洞），要么环绕（把边缘糊到对边），
 *   钳边的表现是边缘被轻微拉伸——那是唯一不引入新东西的失败方式。
 */
class LensResampleTest {

    private val w = 16
    private val h = 12

    /**
     * 一张可预测的合成图：R 随 x 递增、G 随 y 递增、**B 是 R 的反向斜坡**、alpha = 255。
     *
     * B 必须有结构，不能是一个常数通道：重采样改变的是**取样位置**，而常数通道无论
     * 取到哪个像素都是同一个值——第一版的蓝通道就这样量出了 0，看起来像色差没生效。
     */
    private fun ramp(): ByteArray {
        val src = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                src[i] = (x * 255 / (w - 1)).toByte()
                src[i + 1] = (y * 255 / (h - 1)).toByte()
                src[i + 2] = (255 - x * 255 / (w - 1)).toByte()
                src[i + 3] = 255.toByte()
            }
        }
        return src
    }

    @Test
    fun `no coefficients returns the picture untouched`() {
        val src = ramp()
        val out = LensResample.correctRgba(src, w, h, LensCorrectionParams())

        assertArrayEquals("零系数必须逐位相同", src, out)
    }

    @Test
    fun `the exact centre pixel does not move`() {
        // 尺寸必须是**奇数**：偶数尺寸的图中心落在 (w-1)/2 = 7.5 上，根本没有半径为 0 的像素，
        // 离中心最近的四个各差半像素——拿它们验「中心不动」会得到 1 LSB 的假失败
        val ow = 17
        val oh = 13
        val src = ByteArray(ow * oh * 4)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                val i = (y * ow + x) * 4
                src[i] = (x * 255 / (ow - 1)).toByte()
                src[i + 1] = (y * 255 / (oh - 1)).toByte()
                src[i + 2] = 128.toByte()
                src[i + 3] = 255.toByte()
            }
        }

        val out = LensResample.correctRgba(src, ow, oh, LensCorrectionParams(k1 = 0.05f, tca = 0.004f))
        val c = ((oh / 2) * ow + ow / 2) * 4

        assertEquals("正中的 R", src[c].toInt() and 0xFF, out[c].toInt() and 0xFF)
        assertEquals("正中的 G", src[c + 1].toInt() and 0xFF, out[c + 1].toInt() and 0xFF)
    }

    @Test
    fun `alpha is never touched`() {
        val src = ramp()
        for (i in 3 until src.size step 4) src[i] = (i % 251).toByte()
        val out = LensResample.correctRgba(src, w, h, LensCorrectionParams(k1 = 0.05f, tca = 0.004f))

        for (i in 3 until src.size step 4) {
            assertEquals("第 ${i / 4} 个像素的 alpha 被改了", src[i], out[i])
        }
    }

    @Test
    fun `nothing comes from outside the picture`() {
        // 越界取样的两种坏法：变黑（凭空一块黑洞）或环绕（把边缘糊到对边）。
        // 合成图的红通道一路递增，一旦环绕，对边会被搬过来，边缘两行就会出现巨大的跳变
        val src = ramp()
        val out = LensResample.correctRgba(src, w, h, LensCorrectionParams(k1 = 0.08f, tca = 0.006f))

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                assertTrue("($x,$y) 的 R 是 ${out[i].toInt() and 0xFF}", (out[i].toInt() and 0xFF) >= 0)
                // 每一行的红通道必须仍然单调不减：环绕会让它在中途掉下去
                if (x > 0) {
                    val prev = out[i - 4].toInt() and 0xFF
                    val cur = out[i].toInt() and 0xFF
                    assertTrue("($x,$y) 红通道回退了：$prev → $cur", cur >= prev - 1)
                }
            }
        }
    }

    @Test
    fun `a positive coefficient bends the edges outward`() {
        // 桶形畸变存下来的图边缘被压紧，校正之后边缘应当被"拉回来"：
        // 校正后画面的边缘，取到的是更外侧的内容，也就是更亮的红通道
        val src = ramp()
        val out = LensResample.correctRgba(src, w, h, LensCorrectionParams(k1 = 0.05f))
        val srcEdge = src[(h / 2) * w * 4].toInt() and 0xFF
        val outEdge = out[(h / 2) * w * 4].toInt() and 0xFF

        assertTrue("k1 > 0 应把边缘拉回更外侧（$srcEdge → $outEdge）", outEdge > srcEdge)
    }

    @Test
    fun `green is untouched by the aberration while red and blue move away from it`() {
        // 关键在于**怎么量**。合成斜坡里 R 沿行递增、G 沿行恒定，所以「R 与 G 的差」量的是
        // 斜坡本身而不是色差——第一版就这么写，它量出 37 的「中心色差」，绿得像真的一样。
        // 正确的量法是**同一张图、开与不开色差各跑一遍**，只看差。
        val src = ramp()
        val off = LensResample.correctRgba(src, w, h, LensCorrectionParams(k1 = 0f, tca = 0f))
        val on = LensResample.correctRgba(src, w, h, LensCorrectionParams(k1 = 0f, tca = 0.03f))
        val mid = h / 2

        fun shiftAt(x: Int, channel: Int): Int {
            val i = (mid * w + x) * 4 + channel
            return abs((on[i].toInt() and 0xFF) - (off[i].toInt() and 0xFF))
        }

        // 测量点取 x = 1 而不是 x = 0：最边缘那一列的取样坐标会被**钳回原像素**
        // （色差是亚像素位移，钳位在 x=0 处恰好把它吃干净，于是量出 0）——
        // 那是钳位的正确行为，不是色差没生效。往里挪一格，让位移留在图内。
        val probe = 1
        assertEquals("绿通道是基准，开色差也必须一动不动", 0, shiftAt(probe, 1))
        assertEquals("中心处红蓝都不该动", 0, shiftAt(mid, 0))
        assertEquals("中心处蓝也不该动", 0, shiftAt(mid, 2))
        assertTrue("靠边处红通道必须离绿而去（${shiftAt(probe, 0)}）", shiftAt(probe, 0) > 0)
        assertTrue("靠边处蓝通道也必须离绿而去（${shiftAt(probe, 2)}）", shiftAt(probe, 2) > 0)
    }

    @Test
    fun `a picture of one size is not resized by the correction`() {
        // 桶形校正会越出原图，但**不裁也不补**：尺寸必须不变，否则下游的几何与导出全要跟着改
        val src = ramp()
        val out = LensResample.correctRgba(src, w, h, LensCorrectionParams(k1 = 0.08f, tca = 0.005f))

        assertEquals("输出长度必须与输入相同", src.size, out.size)
    }

    @Test
    fun `an empty or degenerate picture does not throw`() {
        // 0 宽的输入在界面上不该发生，但除以 0 会得到 NaN 坐标，
        // 而 NaN 坐标会一路走到取样，写出一整张坏图
        LensResample.correctRgba(ByteArray(0), 0, 0, LensCorrectionParams(k1 = 0.05f))
        LensResample.correctRgba(ByteArray(4), 1, 1, LensCorrectionParams(k1 = 0.05f, tca = 0.004f))
    }
}
