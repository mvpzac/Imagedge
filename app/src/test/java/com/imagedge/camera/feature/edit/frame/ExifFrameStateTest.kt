package com.imagedge.camera.feature.edit.frame

import com.imagedge.camera.feature.edit.frame.ExifFrameViewModel.FrameField
import com.imagedge.camera.feature.edit.frame.ExifFrameViewModel.ExifFrameState
import com.imagedge.camera.share.ExportFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重置」按钮的可用性判定。
 *
 * 只钉 `hasEdits` 一处：它决定界面骨架里 `canReset` 与 `hasEdits` 两个参数，
 * 而「重置」与「重新选择照片」是刻意拆开的两个动作——重置只回样式，不换照片。
 * 判定漏了任何一种改动，用户就看着一个灰按钮而改不回去。
 */
class ExifFrameStateTest {

    private fun state(
        template: ExifFrameViewModel.FrameTemplate = ExifFrameViewModel.FrameTemplate.CLASSIC_WHITE,
        customText: String = "",
        keepLogo: Boolean = true,
        rounded: Boolean = false,
        fields: List<FrameField> = listOf(
            FrameField("相机型号", "ZV-E10"),
            FrameField("快门", "1/125s"),
        ),
    ) = ExifFrameState(
        template = template,
        customText = customText,
        keepLogo = keepLogo,
        rounded = rounded,
        fields = fields,
    )

    @Test
    fun `刚选好的照片没有可撤销的改动`() {
        assertFalse(state().hasEdits)
    }

    @Test
    fun `换模板算改动`() {
        assertTrue(state(template = ExifFrameViewModel.FrameTemplate.MINIMAL).hasEdits)
    }

    @Test
    fun `自定义文字算改动`() {
        assertTrue(state(customText = "© 2026").hasEdits)
    }

    @Test
    fun `关掉品牌标识算改动`() {
        assertTrue(state(keepLogo = false).hasEdits)
    }

    @Test
    fun `圆角算改动`() {
        assertTrue(state(rounded = true).hasEdits)
    }

    @Test
    fun `隐藏字段算改动`() {
        val fields = listOf(FrameField("相机型号", "ZV-E10", enabled = false))
        assertTrue(state(fields = fields).hasEdits)
    }

    /**
     * 回归：**只改字段内容**也算改动。
     *
     * EXIF 读错时用户会直接改内容（相机把型号写成了 "NIKON Z 6"、被压缩掉的时间补回来），
     * 而 `resetStyle()` 本来就会把值还原。此前 `hasEdits` 只看 `enabled`，
     * 于是这类用户看着「重置」是灰的，改了就回不来。
     */
    @Test
    fun `只改字段内容也算改动`() {
        val fields = listOf(
            FrameField("相机型号", "NIKON Z 6", baseline = "ZV-E10"),
            FrameField("快门", "1/125s", baseline = "1/125s"),
        )
        assertTrue(state(fields = fields).hasEdits)
    }

    /** 内容改回原值后不再算改动——否则「改了又改回」会永久留下一个可重置状态。 */
    @Test
    fun `内容改回原值后不算改动`() {
        val fields = listOf(FrameField("相机型号", "ZV-E10", baseline = "ZV-E10"))
        assertFalse(state(fields = fields).hasEdits)
    }

    /** 首尾空格不算改动：输入框里多敲一个空格不该点亮「重置」。 */
    @Test
    fun `仅首尾空格不算改动`() {
        val fields = listOf(FrameField("相机型号", "  ZV-E10 ", baseline = "ZV-E10"))
        assertFalse(state(fields = fields).hasEdits)
    }

    /**
     * 回归：`FrameField` 的 `baseline` 默认取 `value`，
     * 所以手工构造的字段（测试、将来的预设）不会一上来就显示成"改过"。
     */
    @Test
    fun `未提供基线时视为未改动`() {
        val fields = listOf(FrameField("相机型号", "ZV-E10"))
        assertFalse(state(fields = fields).hasEdits)
    }

    // ── 实况图的格式限制 ──────────────────────────────────────────────────

    /** 普通照片不设限：格式与画质完全由用户定。 */
    @Test
    fun `普通照片不限制格式`() {
        for (format in ExportFormat.entries) {
            assertNull(ExifFrameViewModel.motionFormatReason(isMotion = false, format = format))
        }
    }

    /**
     * 回归：开放格式选择之后，实况图配 PNG/WebP 会产出相册不认的"动态照片"。
     * Motion Photo 靠 JPEG 里的 XMP 与 MPF 段关联视频，换格式后关联消失——
     * 而那是用户在相册里才发现的失败，不是点「保存副本」时的失败。
     */
    @Test
    fun `实况图只能出 JPEG`() {
        assertNull(ExifFrameViewModel.motionFormatReason(isMotion = true, format = ExportFormat.JPEG))
        assertNotNull(ExifFrameViewModel.motionFormatReason(isMotion = true, format = ExportFormat.PNG))
        assertNotNull(ExifFrameViewModel.motionFormatReason(isMotion = true, format = ExportFormat.WEBP))
    }

    /** 界面提示与导出拒绝是同一句话，所以它必须非空且是给用户看的完整句子。 */
    @Test
    fun `拒绝理由可展示`() {
        val reason = ExifFrameViewModel.motionFormatReason(isMotion = true, format = ExportFormat.PNG)
        assertTrue("理由不该为空", !reason.isNullOrBlank())
        assertTrue("理由应指出用户该选什么", reason!!.contains("JPEG"))
    }
}