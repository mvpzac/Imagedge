package com.imagedge.camera.edit

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imagedge.camera.image.LensCorrection
import com.imagedge.camera.image.LensCorrectionParams
import com.imagedge.camera.image.LensResample
import com.imagedge.camera.image.correctLensDistortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 条带式重采样 vs 整图式重采样——**逐像素对拍**。
 *
 * 条带版是为了把 21 MP 上的内存从约 426 MB 压到约 88 MB，而它换来的是一层新的取样代码。
 * 这层代码**只能在仪器化里验**（要 `android.graphics`），所以它唯一的防线就是与
 * [LensResample.correctRgba] 对拍：后者有 8 条 JVM 用例，两边不一致时红的必须是它。
 *
 * 输入刻意带**结构**（渐变 + 纯色块 + alpha 梯度）：常数图上两种实现的差别可能全被取整吃掉，
 * 而结构图上的位移会被放大成一个码值——那才是我们要看的东西。
 *
 * ## 画幅必须覆盖 `高 > 条带高`
 *
 * 条带高是 128。测试图若比它矮，`while` 只跑一轮、缓冲装的是整张图，
 * 取样那侧的行号钳制**永远不触发**——于是「条带读窄了取到错误的行」这类缺陷可以照绿。
 * 所以下面至少有一条 `180 × 320`：高 320 > 128，且是竖幅。
 */
@RunWith(AndroidJUnit4::class)
class LensResampleBitmapTest {

    private fun source(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / (w - 1)
                val g = y * 255 / (h - 1)
                // 蓝通道放一个中间值：常数通道在重采样里最容易把两版实现的差别抹平
                val b = if (x < w / 2) 200 else 40
                // alpha 也带梯度：alpha 是逐位透传的一维，被改坏时 RGB 全对也看不出来。
                //
                // RGB 必须**先按 alpha 预乘**再写进去。ARGB_8888 存的是预乘值，而
                // 「alpha 小于 RGB」是非法像素：条带版的结果要过一遍 `Bitmap.setPixels`，
                // 整图版只在字节数组里比——于是对拍会报出一个差 1 的假失败，
                // 而那个 1 与取样、与条带边界都无关。真实相机位图是全不透明的，
                // 这里的半透明只是为了让 alpha 那一维也参与对拍。
                // 源图**全不透明**。这不是省事，是必需的：结果要过一遍 `Bitmap.setPixels`，
                // 而带 alpha 的像素写进 ARGB_8888 再读出来，RGB 已经不是写进去的那些字节
                // （见 [aSemiTransparentPixelDoesNotSurviveABitmap]）。拿它跟整图版的
                // ByteArray 对拍，差异来自 Bitmap 往返而不是重采样——实测差 1，
                // 而且换任何参数构造都消不掉。真实相机位图本来也是不透明的。
                val a = 255
                px[y * w + x] = (a shl 24) or
                    (r * a / 255 shl 16) or (g * a / 255 shl 8) or (b * a / 255)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun toRgba(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        for (i in px.indices) {
            out[i * 4] = (px[i] shr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (px[i] shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (px[i] and 0xFF).toByte()
            out[i * 4 + 3] = (px[i] ushr 24 and 0xFF).toByte()
        }
        return out
    }

    /** 差异最大的位置也一并报出来——只报一个数会让人在四个通道里瞎猜 */
    private fun worstChannelDelta(expected: ByteArray, actual: Bitmap, w: Int, h: Int): Pair<Int, String> {
        val px = IntArray(w * h)
        actual.getPixels(px, 0, w, 0, 0, w, h)
        var worst = 0
        var worstAt = ""
        // ARGB 里 A 在 24..31、R 在 16..23、G 在 8..15、B 在 0..7。
        // 写成 `16 - 8 * channel` 对 channel=3 会算成负移位，靠掩码绕回来——别这么写。
        val shifts = intArrayOf(16, 8, 0, 24)
        for (i in px.indices) {
            for (c in 0..3) {
                val e = expected[i * 4 + c].toInt() and 0xFF
                val a = (px[i] ushr shifts[c]) and 0xFF
                val d = kotlin.math.abs(e - a)
                if (d > worst) {
                    worst = d
                    worstAt = "(${(i % w)},${i / w}) 通道$c 期望$e 实得$a"
                }
            }
        }
        return worst to worstAt
    }

    private fun assertMatchesWholeImage(w: Int, h: Int, params: LensCorrectionParams) {
        val src = source(w, h)
        val expected = LensResample.correctRgba(toRgba(src, w, h), w, h, params)
        val actual = correctLensDistortion(src, params)
        val (worst, worstAt) = worstChannelDelta(expected, actual, w, h)
        assertEquals("${w}x$h：条带与整图必须逐像素相同（含 alpha），最差 $worst 于 $worstAt", 0, worst)
    }

    @Test
    fun zeroParametersReturnTheSourceItself() {
        val src = source(96, 64)
        assertSame("零系数必须原样返回入参（同一实例），调方据此整趟跳过", src, correctLensDistortion(src, LensCorrectionParams()))
    }

    @Test
    fun theBandedResultMatchesTheWholeImageResultPixelForPixel() {
        val params = LensCorrectionParams(k1 = 0.02f, tca = 0.004f)
        assertMatchesWholeImage(96, 64, params)
        // 竖幅与 16:9：位移界在这两种画幅上最容易估松
        assertMatchesWholeImage(180, 320, params)
        assertMatchesWholeImage(320, 180, params)
        assertMatchesWholeImage(320, 120, params)
    }

    @Test
    fun theBandedResultMatchesOnThePincushionEndToo() {
        // k1 < 0 那一端：牛顿迭代在某个半径外不再收敛、因子回落，取样因子沿半径非单调，
        // 位移的最大值因此既不在角点也不在半径端点——这是最容易估松的一端。
        val params = LensCorrectionParams(k1 = -0.02f, tca = 0.004f)
        assertMatchesWholeImage(96, 64, params)
        assertMatchesWholeImage(180, 320, params)
        assertMatchesWholeImage(320, 180, params)
        // 高 900：有 7 条条带，其中 5 条是**中间条带**——首尾两条的越界无害
        //（它们本来就覆盖到画面边缘，与整图版的钳制结果一致），只有中间那些会取到错误的行。
        // 位移界一旦估松，错的就是这几条
        assertMatchesWholeImage(320, 900, params)
        assertMatchesWholeImage(600, 900, params)
    }

    @Test
    fun aFrameTallerThanTheBandHeightActuallyBands() {
        // 320 > 128（条带高），所以这一条在结构上保证条带循环跑了多于一轮。
        // 若有人把条带高调大到超过图高，上面几条会集体失效——这一条是唯一还活着的。
        assertMatchesWholeImage(160, 320, LensCorrectionParams(k1 = -0.02f, tca = 0.004f))
    }

    @Test
    fun aSemiTransparentPixelDoesNotSurviveABitmap() {
        // 这条断言的是一个**测量到的事实**，不是产品行为：把半透明像素写进 ARGB_8888
        // 再读回来，RGB 已经不是写进去的那些字节。
        //
        // 它是对拍测试必须用不透明源图的原因，也是「把源图改回带 alpha 的对拍」时会先撞到
        // 的那道墙。哪天这条断言变红，说明 Bitmap 往返已经不损坏了，那时
        // `source()` 可以把 alpha 梯度加回来，让 alpha 那一维也参与逐像素对拍。
        val w = 96
        val h = 64
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x * 255 / (w - 1)
                val g = y * 255 / (h - 1)
                val b = if (x < w / 2) 200 else 40
                val a = 128 + x * 127 / (w - 1)
                px[y * w + x] = (a shl 24) or
                    (r * a / 255 shl 16) or (g * a / 255 shl 8) or (b * a / 255)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val back = IntArray(w * h)
        bmp.getPixels(back, 0, w, 0, 0, w, h)

        val shifts = intArrayOf(16, 8, 0, 24)
        var worst = 0
        for (i in px.indices) {
            for (c in 0..3) {
                val sent = (px[i] ushr shifts[c]) and 0xFF
                val read = (back[i] ushr shifts[c]) and 0xFF
                worst = kotlin.math.max(worst, kotlin.math.abs(sent - read))
            }
        }
        assertTrue(
            "半透明像素经 Bitmap 往返后逐位无损（最大差 0）：source() 可以把 alpha 梯度加回来",
            worst > 0,
        )
    }

    @Test
    fun parametersBeyondTheCapArePulledBack() {
        // 上限不是滑条两端的装饰：`LensCorrectionParams` 是公开可构造的 data class，
        // 一次直接构造就能把它顶回去，而那条路径不经过滑条
        val pulled = LensCorrectionParams(k1 = 0.5f, tca = 0.9f).coerceIntoRange()

        assertEquals(LensCorrectionParams.K1_LIMIT, pulled.k1, 0f)
        assertEquals(LensCorrectionParams.TCA_LIMIT, pulled.tca, 0f)
    }

    @Test
    fun theBandBufferOnA21MegapixelFrameStaysSmall() {
        // 调的是**生产函数**，不是把它的公式抄一遍——抄一遍只会证明公式没被改写，
        // 证明不了公式是对的。缓冲 = (条带高 + 2·上界) × 行宽 × 4
        val params = LensCorrectionParams(k1 = -LensCorrectionParams.K1_LIMIT, tca = LensCorrectionParams.TCA_LIMIT)
        val imageW = 5328
        val imageH = 4000
        val slack = LensCorrection.bandSlack(imageW, imageH, params)

        val bytes = (128 + 2 * slack).toLong() * imageW * 4L
        assertTrue("21 MP 条带缓冲 $bytes 字节（${bytes / 1024 / 1024} MB）超过 20 MB", bytes < 20L * 1024 * 1024)
    }
}