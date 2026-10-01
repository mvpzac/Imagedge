package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [LensCorrection.bandSlack] 的上界必须是**逐像素真值**，不是估的。
 *
 * 估松了不会抛异常：条带那侧把越界的行号钳到边缘行，出来的是一条糊带而不是崩溃，
 * 所以只有「上界 ≥ 逐像素实测最大位移」这一条能钉住它。
 *
 * 画幅必须同时覆盖横、竖、16:9 与超宽。曾经那版线性估在 4:3 上够、在 16:9 上不够，
 * 只测 4:3 会照绿——这条测试存在的理由就是不让它退回那个状态。
 */
class BandSlackTest {

    private val dx = FloatArray(3)
    private val dy = FloatArray(3)

    private fun worstDisplacement(width: Int, height: Int, p: LensCorrectionParams): Float {
        var worst = 0f
        for (y in 0 until height) {
            val cy = y - (height - 1) / 2f
            for (x in 0 until width) {
                LensCorrection.offsetsOf(x.toFloat(), y.toFloat(), width, height, p, dx, dy)
                for (ch in 0..2) {
                    val d = abs(dy[ch] - cy)
                    if (d > worst) worst = d
                }
            }
        }
        return worst
    }

    @Test
    fun `the bound covers every pixel of tall and wide frames`() {
        val frames = listOf(
            320 to 240,   // 4:3
            240 to 320,   // 4:3 竖
            320 to 180,   // 16:9
            180 to 320,   // 16:9 竖
            400 to 100,   // 超宽
            100 to 400,   // 超高
        )
        for (sign in listOf(1f, -1f)) {
            val params = LensCorrectionParams(
                k1 = sign * LensCorrectionParams.K1_LIMIT,
                tca = LensCorrectionParams.TCA_LIMIT,
            )
            for ((w, h) in frames) {
                val slack = LensCorrection.bandSlack(w, h, params)
                val worst = worstDisplacement(w, h, params)
                assertTrue(
                    "${w}x$h k1=$sign：上界 $slack 行 < 逐像素最大位移 $worst 行",
                    slack >= worst,
                )
            }
        }
    }

    @Test
    fun `the bound is two rows looser than it needs to be`() {
        // 上界必须**紧**。松出来的每一行都要乘行宽再乘 4 字节：
        // 21 MP 上松 100 行就是 2 MB，估松了会把刚省下来的内存又还回去。
        val params = LensCorrectionParams(
            k1 = -LensCorrectionParams.K1_LIMIT,
            tca = LensCorrectionParams.TCA_LIMIT,
        )
        val w = 320
        val h = 180
        val slack = LensCorrection.bandSlack(w, h, params)
        val needed = kotlin.math.ceil(worstDisplacement(w, h, params)).toInt()
        assertEquals("上界应恰好是所需位移向上取整再加 2 行", needed + 2, slack)
    }

    @Test
    fun `the 21 megapixel band buffer stays small`() {
        // 这条钉的是「条带版真的省内存」。条带缓冲 = (128 + 2·上界) × 行宽 × 4，
        // 上界涨一倍缓冲就翻近一倍——比位移本身敏感。
        val params = LensCorrectionParams(
            k1 = -LensCorrectionParams.K1_LIMIT,
            tca = LensCorrectionParams.TCA_LIMIT,
        )
        val w = 5328
        val h = 4000
        val slack = LensCorrection.bandSlack(w, h, params)
        val bytes = (128 + 2 * slack).toLong() * w * 4
        assertTrue(
            "21 MP 上界 $slack 行、条带缓冲 ${bytes / 1024 / 1024} MB 超过 20 MB",
            bytes < 20L * 1024 * 1024,
        )
    }

    @Test
    fun `an identity needs no band at all`() {
        assertEquals(0, LensCorrection.bandSlack(4000, 3000, LensCorrectionParams()))
    }
}