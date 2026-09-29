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

    private fun gradient(): Bitmap {
        val w = 256
        val h = 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = when (y) {
                    0 -> Color.rgb(x, x, x)                                   // 亮度全量程，含纯灰列
                    1 -> Color.rgb(255, (x * 7) % 256, (x * 13) % 256)        // 色相环带，含跨接缝
                    2 -> Color.rgb(128, 128, 128)                             // 全灰行（无色相）
                    else -> Color.rgb(2, 2, 2)                                // 亮度地板以下
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

    private fun worstDelta(a: ByteArray, b: Bitmap): Int {
        val w = b.width
        val px = IntArray(w * b.height)
        b.getPixels(px, 0, w, 0, 0, w, b.height)
        var worst = 0
        for (i in px.indices) {
            for (c in 0..2) {
                worst = maxOf(worst, abs((a[i * 4 + c].toInt() and 0xFF) - (px[i] shr (16 - 8 * c) and 0xFF)))
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

        val worst = worstDelta(cpuBytes, gpu!!)
        assertTrue("CPU/GPU 掩码最大差 $worst LSB（容差 1）", worst <= 1)
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

        val worst = worstDelta(cpuBytes, gpu!!)
        assertTrue("CPU/GPU 亮度掩码最大差 $worst LSB（容差 1）", worst <= 1)
    }
}
