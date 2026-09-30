package com.imagedge.camera.edit

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imagedge.camera.lut.ColorAdjust
import com.imagedge.camera.lut.CpuLutProcessor
import com.imagedge.camera.lut.GpuLutProcessor
import com.imagedge.camera.lut.KeyAxis
import com.imagedge.camera.lut.LutProcessor
import com.imagedge.camera.lut.RangeKey
import com.imagedge.camera.lut.SelectiveAdjust
import com.imagedge.camera.lut.SelectiveSpec
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CPU 与 GPU 的区间键控同值。输入刻意含三类最可能分家的像素：
 * 跨红端接缝的色相、纯灰（max == min）、亮度地板以下。
 * 只测中间调的同值测试是本仓库抓到过的「绿灯钉错事」那一类。
 *
 * 跑法：`./gradlew :app:connectedDebugAndroidTest`（模拟器即可；真机上装的是 release 包，
 * 不要用仪器化测试覆盖它）。
 */
@RunWith(AndroidJUnit4::class)
class SelectiveParityTest {

    /**
     * 512×512 = 262 144 像素，**必须**跨过 `GpuLutProcessor.minPixels`（200 000）。
     *
     * 第一版这张图是 256×4 = 1024 像素，于是 GPU 路径按设计拒绝了小图、`applyToBitmap`
     * 返回 null，而断言拒绝了一次空跑——三条用例全红，理由却与同值毫无关系。
     * 一道「小图不走 GPU」的合理闸，正好能把一条测不到东西的测试照出来。
     */
    private fun gradient(): Bitmap {
        val w = 512
        val h = 512
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            // 四类像素各占四分之一屏，沿 x 重复同一模式以便每一行都被充分采样
            val band = y / (h / 4)
            for (x in 0 until w) {
                px[y * w + x] = when (band) {
                    0 -> Color.rgb(x * 255 / (w - 1), x * 255 / (w - 1), x * 255 / (w - 1)) // 亮度全量程，含纯灰列
                    1 -> Color.rgb(255, (x * 7) % 256, (x * 13) % 256)                         // 色相环带，含跨接缝
                    2 -> Color.rgb(128, 128, 128)                                          // 全灰（无色相）
                    else -> Color.rgb(2, 2, 2)                                             // 亮度地板以下
                }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Bitmap → RGBA8 字节（ARGB_8888 在小端机上就是 RGBA 字节序，与 CpuLutProcessor 的入参一致） */
    private fun rgbaBytes(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 4)
        for (i in px.indices) {
            out[i * 4] = (px[i] shr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (px[i] shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (px[i] and 0xFF).toByte()
            out[i * 4 + 3] = (px[i] ushr 24 and 0xFF).toByte()
        }
        return out
    }

    private fun bitmapFromRgba(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) {
            px[i] = (bytes[i * 4].toInt() and 0xFF shl 16) or
                (bytes[i * 4 + 1].toInt() and 0xFF shl 8) or
                (bytes[i * 4 + 2].toInt() and 0xFF) or
                (bytes[i * 4 + 3].toInt() and 0xFF shl 24)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 逐通道最差差。[compareAlpha] 为真时把 alpha 也算进去——原来的版本只看 RGB，
     * 所以「掩码模式把 alpha 写死成 1.0、CPU 侧却透传」这条差异它**结构上看不见**。
     * RGB 的位移是 `shr (16 - 8c)`，alpha 是 `ushr 24`，两者不是一个式子。
     */
    private fun worstDelta(a: ByteArray, b: Bitmap, compareAlpha: Boolean = false): Int {
        val w = b.width
        val px = IntArray(w * b.height)
        b.getPixels(px, 0, w, 0, 0, w, b.height)
        var worst = 0
        for (i in px.indices) {
            for (c in 0..2) {
                worst = maxOf(worst, abs((a[i * 4 + c].toInt() and 0xFF) - (px[i] shr (16 - 8 * c) and 0xFF)))
            }
            if (compareAlpha) {
                worst = maxOf(worst, abs((a[i * 4 + 3].toInt() and 0xFF) - (px[i] ushr 24 and 0xFF)))
            }
        }
        return worst
    }

    @Test
    fun cpu_and_gpu_agree_on_the_hue_mask_within_one_lsb() = runBlocking {
        val spec = SelectiveSpec(
            RangeKey.of(KeyAxis.HUE, 0.85f, 1.15f, feather = 0.1f),
            SelectiveAdjust(exposure = 40)
        )
        val src = gradient()
        // CpuLutProcessor 不覆写 applyToBitmap（接口默认返回 null）：CPU 走 apply() 的字节数组入口
        val cpuBytes = CpuLutProcessor().apply(
            rgbaBytes(src), src.width, src.height, LutProcessor.EMPTY_LUT, 0, 100,
            ColorAdjust.NONE, selective = spec, maskMode = true
        )
        val gpu = GpuLutProcessor().applyToBitmap(
            src, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE, selective = spec, maskMode = true
        )
        assertNotNull("模拟器上 GPU 路径必须可用，否则这条测试没有意义", gpu)

        val worst = worstDelta(cpuBytes, gpu!!, compareAlpha = true)
        assertTrue("CPU/GPU 掩码最大差 $worst LSB（容差 1）", worst <= 1)
    }

    /**
     * 调整路径（掩码关）——掩码那两条**测不到**这一半。
     *
     * 它们比的是权重 `rkt_weight`，而用户真正看见的是权重之后改变像素的那一段：
     * 增益、绕支点的对比度、线性光饱和度、按 w 混合。GLSL 里那四行（`GpuLutProcessor.kt`
     * 的 751-754 附近）在 afb88d6 之前一次都没被对比过。
     *
     * 容差 1 的来历：CPU 无 LUT 的出口走 `quantize(..., dither = true)`，三角抖动落在
     * (-1, 1) 个码值；GPU 无 LUT 时不抖（那段被 `uHasLut > 0.5` 挡掉）。两侧**算法**结果相同，
     * 差的只是量化前那一个 ±1 码值。所以 `<= 1` 可证，`< 1` 会把一条真同值的图判红。
     * 三个轴都给非零值，少给一个就有一整段没被走到。
     */
    @Test
    fun cpu_and_gpu_agree_on_the_adjusted_pixels_within_one_lsb() = runBlocking {
        val spec = SelectiveSpec(
            RangeKey.of(KeyAxis.LUMA, 0.3f, 0.7f, feather = 0.1f),
            SelectiveAdjust(exposure = 40, contrast = 12, saturation = -20)
        )
        val src = gradient()
        val cpuBytes = CpuLutProcessor().apply(
            rgbaBytes(src), src.width, src.height, LutProcessor.EMPTY_LUT, 0, 100,
            ColorAdjust.NONE, selective = spec
        )
        val gpu = GpuLutProcessor().applyToBitmap(
            src, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE, selective = spec
        )
        assertNotNull("模拟器上 GPU 路径必须可用", gpu)

        val worst = worstDelta(cpuBytes, gpu!!)
        assertTrue("CPU/GPU 调整后最大差 $worst LSB（容差 1，见本用例的注释）", worst <= 1)
    }

    @Test
    fun cpu_and_gpu_agree_on_the_luma_mask_within_one_lsb() = runBlocking {
        val spec = SelectiveSpec(
            RangeKey.of(KeyAxis.LUMA, 0.3f, 0.7f, feather = 0.1f),
            SelectiveAdjust(exposure = 40)
        )
        val src = gradient()
        val cpuBytes = CpuLutProcessor().apply(
            rgbaBytes(src), src.width, src.height, LutProcessor.EMPTY_LUT, 0, 100,
            ColorAdjust.NONE, selective = spec, maskMode = true
        )
        val gpu = GpuLutProcessor().applyToBitmap(
            src, LutProcessor.EMPTY_LUT, 0, 100, ColorAdjust.NONE, selective = spec, maskMode = true
        )
        assertNotNull("模拟器上 GPU 路径必须可用", gpu)

        val worst = worstDelta(cpuBytes, gpu!!, compareAlpha = true)
        assertTrue("CPU/GPU 亮度掩码最大差 $worst LSB（容差 1）", worst <= 1)
    }
}
