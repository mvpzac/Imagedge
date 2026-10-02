package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : 设备控制码的负载宽度与色彩档案解析
 * </pre>
 */
class SonyControlCodeTest {

    @Test
    fun `each control code declares the payload width it is sent with`() {
        // 宽度随控制码而变，发错宽度相机会整条丢弃——所以这里是查表而非调用方自选
        assertEquals(2, SonyControlCode.payloadBytes(SonyControlCode.S1_BUTTON))
        assertEquals(2, SonyControlCode.payloadBytes(SonyControlCode.MOVIE_REC_BUTTON))
        assertEquals(2, SonyControlCode.payloadBytes(SonyControlCode.NEAR_FAR))
        assertEquals(4, SonyControlCode.payloadBytes(SonyControlCode.AF_AREA_POSITION))
        assertEquals(1, SonyControlCode.payloadBytes(SonyControlCode.ZOOM_OPERATION))
    }

    @Test
    fun `an unknown control code is rejected rather than guessed a width`() {
        try {
            SonyControlCode.payloadBytes(0xDEAD)
            throw AssertionError("未知控制码应当抛错——猜一个宽度等于让相机静默丢弃指令")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未知控制码"))
        }
    }

    @Test
    fun `shutter press and release are distinct values`() {
        // 两段式的前提：按一下与松手是两个不同的值，发同一个值只会得到「只对焦」
        assertTrue(SonyControlValue.DOWN != SonyControlValue.RELEASE)
        assertEquals(2, SonyControlValue.DOWN)
        assertEquals(1, SonyControlValue.RELEASE)
    }

    @Test
    fun `focus drive directions are distinct from the shutter values by role`() {
        assertEquals(2, SonyControlValue.FAR)
        assertEquals(-2, SonyControlValue.NEAR)
        assertEquals(0, SonyControlValue.STOP)
    }

    @Test
    fun `colour profile reports missing parameters as null not zero`() {
        val profile = CameraColorProfile.from(
            mapOf(
                SonyDevicePropCode.PICTURE_PROFILE to prop(SonyDevicePropCode.PICTURE_PROFILE, 3L)
            )
        )

        assertEquals(3L, profile.pictureProfile)
        assertNull(profile.creativeLook)
        // 关键：读不到 ≠ 居中。0 是「无偏移」，当成缺省会显示成中性风格
        assertNull(profile.clContrast)
        assertFalse(profile.hasCreativeLookParams)
        assertNull(profile.toCreativeLookAxes())
    }

    @Test
    fun `creative look axes carry only the four axes the editor has`() {
        val profile = CameraColorProfile.from(
            mapOf(
                SonyDevicePropCode.CREATIVE_STYLE to prop(SonyDevicePropCode.CREATIVE_STYLE, 7L),
                SonyDevicePropCode.CREATIVE_LOOK_CONTRAST to prop(SonyDevicePropCode.CREATIVE_LOOK_CONTRAST, 12L),
                SonyDevicePropCode.CREATIVE_LOOK_HIGHLIGHTS to prop(SonyDevicePropCode.CREATIVE_LOOK_HIGHLIGHTS, -8L),
                SonyDevicePropCode.CREATIVE_LOOK_SHADOWS to prop(SonyDevicePropCode.CREATIVE_LOOK_SHADOWS, 20L),
                SonyDevicePropCode.CREATIVE_LOOK_SATURATION to prop(SonyDevicePropCode.CREATIVE_LOOK_SATURATION, 5L),
                // 褪色与清晰度在编辑器滑条里没有对应轴，不该被塞进别的轴
                SonyDevicePropCode.CREATIVE_LOOK_FADE to prop(SonyDevicePropCode.CREATIVE_LOOK_FADE, 30L),
                SonyDevicePropCode.CREATIVE_LOOK_CLARITY to prop(SonyDevicePropCode.CREATIVE_LOOK_CLARITY, 40L)
            )
        )

        val axes = requireNotNull(profile.toCreativeLookAxes())

        assertEquals(7L, profile.creativeLook)
        assertEquals(12, axes.contrast)
        assertEquals(-8, axes.highlights)
        assertEquals(20, axes.shadows)
        assertEquals(5, axes.saturation)
        assertTrue(profile.hasCreativeLookParams)
    }

    @Test
    fun `a partially reported creative look keeps its missing axes null`() {
        val profile = CameraColorProfile.from(
            mapOf(
                SonyDevicePropCode.CREATIVE_LOOK_CONTRAST to prop(SonyDevicePropCode.CREATIVE_LOOK_CONTRAST, 10L)
            )
        )

        val axes = requireNotNull(profile.toCreativeLookAxes())

        assertEquals(10, axes.contrast)
        assertNull(axes.saturation)
    }

    @Test
    fun `empty profile is all null rather than all zero`() {
        val empty = CameraColorProfile.EMPTY
        assertNull(empty.pictureProfile)
        assertNull(empty.creativeLook)
        assertNull(empty.clContrast)
        assertFalse(empty.hasCreativeLookParams)
    }

    private fun prop(code: Int, value: Long) =
        DeviceProperty(code = code, dataType = 0x0006, getSet = 0x01, enabled = true, currentValue = value)
}