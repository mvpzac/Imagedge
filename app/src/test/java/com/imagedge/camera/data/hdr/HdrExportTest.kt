package com.imagedge.camera.data.hdr

import com.imagedge.camera.share.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HDR 导出**能不能做**的那一条判断。
 *
 * 为什么它得是一份纯函数、而不是两处各判一次：界面要决定开关亮不亮，
 * 导出要决定放不放行。两处各写一遍的结果就是「开关亮着、点下去被拒」——
 * 那一格控件等于是坏的，而用户看不出是哪一步坏的。规则只有这一份，
 * 两个出口都调它，导出时给的理由与界面上灰掉的理由是同一句。
 */
class HdrExportTest {

    private fun availability(
        sdkInt: Int = 34,
        format: ExportFormat = ExportFormat.JPEG,
        sourceHasGainMap: Boolean = true,
    ) = HdrExport.availability(sdkInt, format, sourceHasGainMap)

    @Test
    fun `a jpeg from a source with a gain map on api 34 can go hdr`() {
        assertNull(availability())
    }

    @Test
    fun `below api 34 it cannot`() {
        // Gainmap 是 API 34 才有的类。低版本上给一个亮着的开关，
        // 换来的是点下去没反应——本仓库反复出现的那种坏法
        assertEquals(HdrUnavailable.SDK_TOO_LOW, availability(sdkInt = 33))
    }

    @Test
    fun `only jpeg carries the gain map`() {
        // v1 只做 JPEG。PNG 与 WebP 各有各的增益图封装，没验过就不给开关，
        // 免得导出一张「格式对、HDR 丢了」的文件而用户不知道
        assertEquals(HdrUnavailable.FORMAT_UNSUPPORTED, availability(format = ExportFormat.PNG))
        assertEquals(HdrUnavailable.FORMAT_UNSUPPORTED, availability(format = ExportFormat.WEBP))
    }

    @Test
    fun `a photo with no hdr data of its own is refused rather than faked`() {
        // 这一条是本轮最贵的裁定：从一张普通 SDR 照片「反推」出高光，
        // 造出来的是用户照片里本来没有的东西——那不叫还原 HDR，叫加滤镜，
        // 而且用户分辨不出来。宁可不给，也不给一个自己编的
        assertEquals(
            HdrUnavailable.NO_SOURCE_GAIN_MAP,
            availability(sourceHasGainMap = false)
        )
    }

    @Test
    fun `the most fundamental reason wins when several apply`() {
        // 三条都不满足时只能报一条，否则界面上灰掉的理由会随条件跳变。
        // 顺序按「最基础」排：设备 → 格式 → 源数据。改这个顺序要改这条用例
        assertEquals(
            HdrUnavailable.SDK_TOO_LOW,
            availability(sdkInt = 30, format = ExportFormat.PNG, sourceHasGainMap = false)
        )
        assertEquals(
            HdrUnavailable.FORMAT_UNSUPPORTED,
            availability(sdkInt = 34, format = ExportFormat.PNG, sourceHasGainMap = false)
        )
    }

    @Test
    fun `every reason carries a message the user can read`() {
        // 理由是给用户看的。空字符串的灰掉等于「不告诉你为什么」，
        // 而这条与「悄悄丢 HDR」是同一类问题
        for (reason in HdrUnavailable.entries) {
            assertTrue("${reason.name} 没有可读的理由", reason.message.isNotBlank())
        }
    }
}
