package com.imagedge.camera.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imagedge.camera.data.hdr.UltraHdrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 增益图从写出去到读回来的那一圈。**这是 ALPHA_8 能不能 JPEG 压缩的实证**：
 * 应用自己不压缩增益图（AOSP 的 `Bitmap_compress` 根本不碰它，只转交 hwui），
 * 所以唯一能回答「行不行」的办法就是让框架写一遍、再读一遍看对不对。
 *
 * 输入全部是**已知的合成值**（线性斜坡 + 两端平坦区），不依赖任何 HDR 源的估计策略——
 * 「HDR 从哪来」是另一条还没裁定的路（从 SDR 反推 / 多张包围曝光 / 相机直出），
 * 混进来会让这条测试测不到它该测的东西。
 *
 * 跑法：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.imagedge.camera.edit.UltraHdrRoundTripTest`
 * **没有设备时它必须是 SKIP 而不是 PASS**——API 33 的机器上跳过比「绿了但什么都没测」诚实。
 */
@RunWith(AndroidJUnit4::class)
class UltraHdrRoundTripTest {

    private val context = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().targetContext

    private val width = 64
    private val height = 8

    private fun baseBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val px = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = (x * 255 / (width - 1)).toInt()
                px[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(px, 0, width, 0, 0, width, height)
        return bmp
    }

    /**
     * 增益图字节：中间一段线性斜坡，两端各留 8 列**平坦区**。
     * 平坦区是断言能站住的关键——JPEG 对平坦块的往返是精确的，
     * 而渐变上会掉一两个码值，那是压缩本身的行为，不是我们写错了。
     */
    private fun gainMapBytes(): ByteArray {
        val out = ByteArray(width * height)
        val flat = 8
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = when {
                    x < flat -> 0
                    x >= width - flat -> 255
                    else -> (x - flat) * 255 / (width - 2 * flat)
                }
                out[y * width + x] = v.toByte()
            }
        }
        return out
    }

    @Test
    fun an_alpha8_gainMapSurvivesTheJpegRoundTrip() {
        assumeTrue("Gainmap 需要 API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        val expected = gainMapBytes()
        val jpeg = UltraHdrEncoder.encode(
            base = baseBitmap(),
            gainMap = expected,
            gainMapWidth = width,
            gainMapHeight = height,
            ratioMin = 0.25f,
            ratioMax = 4.0f,
            quality = 95,
        )
        assertNotNull("API 34+ 上必须能写出 Ultra HDR", jpeg)

        val decoded = BitmapFactory.decodeByteArray(jpeg!!, 0, jpeg.size)
        assertNotNull("写出的文件必须是一张可解码的 JPEG", decoded)
        assertTrue("读回来必须还带增益图", decoded.hasGainmap())

        val map = decoded.gainmap!!
        assertEquals("ratioMin 必须原样往返", 0.25f, map.ratioMin[0], 1e-4f)
        assertEquals("ratioMax 必须原样往返", 4.0f, map.ratioMax[0], 1e-4f)

        val contents = map.gainmapContents
        assertNotNull("增益图内容不该为空", contents)
        val back = ByteArray(contents.width * contents.height)
        contents.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(back))
        assertEquals("增益图的宽高必须与我们写进去的一致", width, contents.width)
        assertEquals(height, contents.height)

        // 记录框架实际给回的配置：ALPHA_8 原样保留，还是被转成了别的。
        // 断言写死 ALPHA_8 是**有意为之**——它是官方推荐的形态（8 位、单通道、
        // 内嵌色彩描述被忽略），而且体积是彩色增益图的三分之一。哪天框架改了，
        // 这条会红，届时要改的是写回时的配置而不是把断言放宽
        assertEquals("增益图应当原样保留 ALPHA_8", Bitmap.Config.ALPHA_8, contents.config)

        var worstFlat = 0
        var worstRamp = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val got = back[y * width + x].toInt() and 0xFF
                val want = expected[y * width + x].toInt() and 0xFF
                val d = kotlin.math.abs(got - want)
                if (x < 8 || x >= width - 8) worstFlat = maxOf(worstFlat, d) else worstRamp = maxOf(worstRamp, d)
            }
        }
        assertEquals("平坦区的往返必须逐位相同", 0, worstFlat)
        assertTrue("渐变区最差差 $worstRamp，超过 6（JPEG 压缩本身会掉一两个码值）", worstRamp <= 6)
    }

    /**
     * `ExifInterface.saveAttributes()` 会不会把增益图**重写掉**。
     *
     * 导出落盘之后会调 `applyMetadata`，它是拿 `ExifInterface(file)` 改完再写回的——
     * 而增益图不在 EXIF 里，它在 MPF 段。`saveAttributes()` 重写 JPEG 各段时保住哪些、
     * 丢掉哪些，**没有依据可查**，所以只能问设备。
     *
     * 丢的后果是本仓库最贵的那一种：导出**成功**，`applyMetadata` 的
     * `onFailure` 最多打一行「不影响分享」，而用户拿到一张普通 JPEG，以为它是 HDR。
     * 导出路径因此在 HDR 打开时跳过重写（`skipMetadataRewrite`）。
     *
     * **断言写成「保住」是故意的**：设备上跑出来若是丢的，这条会红——那时要改的是
     * 决定（继续不重写，或改成只补 EXIF 不动其他段），不是把这条删掉或者放宽。
     */
    @Test
    fun exifRewritePreservesTheGainMap() {
        assumeTrue("Gainmap 需要 API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        val jpeg = UltraHdrEncoder.encode(baseBitmap(), gainMapBytes(), width, height, 0.25f, 4.0f, 95)
        assertNotNull(jpeg)
        val file = File(context.cacheDir, "hdr-exif-probe.jpg")
        file.writeBytes(jpeg!!)

        androidx.exifinterface.media.ExifInterface(file).apply {
            setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ARTIST, "probe")
            saveAttributes()
        }

        val after = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull(after)
        assertTrue(
            "ExifInterface 重写之后增益图没了——导出必须继续跳过重写，" +
                "否则用户拿到的是一张他以为是 HDR 的普通照片",
            after.hasGainmap()
        )
        file.delete()
    }

    @Test
    fun theBaseImageSurvivesTheGainMapRoundTrip() {
        // 增益图是挂在基础图上的第二条数据。基础图被改坏而增益图还在，
        // 是一张「看起来更亮、实际颜色全错」的照片——比直接失败更难查
        assumeTrue("Gainmap 需要 API 34+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        val base = baseBitmap()
        val jpeg = UltraHdrEncoder.encode(base, gainMapBytes(), width, height, 0.25f, 4.0f, 95)
        assertNotNull(jpeg)

        val decoded = BitmapFactory.decodeByteArray(jpeg!!, 0, jpeg.size)!!
        assertEquals(base.width, decoded.width)
        assertEquals(base.height, decoded.height)

        var worst = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val want = base.getPixel(x, y) and 0xFF
                val got = decoded.getPixel(x, y) and 0xFF
                worst = maxOf(worst, kotlin.math.abs(want - got))
            }
        }
        // JPEG q95 对一条平滑灰阶渐变的往返通常在 2 以内；给 6 是为了容忍
        // 不同设备 libjpeg 版本与色度子采样的差异
        assertTrue("基础图最差差 $worst，超过 6", worst <= 6)
    }
}
