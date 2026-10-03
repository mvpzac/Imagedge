package com.imagedge.camera.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/03
 *     desc   : 监看设置的存取还原（含像素叠加层开关）
 * </pre>
 */
class MonitoringSettingsOverlayTest {

    @Test
    fun `overlay toggles survive a store round trip`() {
        val saved = MonitoringSettings(focusPeak = true, zebra = true)

        // 模拟 store 的落盘/读回：值一律以字符串进出
        val stored = mapOf(
            MonitoringSettings.KEY_FOCUS_PEAK to saved.focusPeak.toString(),
            MonitoringSettings.KEY_ZEBRA to saved.zebra.toString()
        )

        val restored = MonitoringSettings.fromStored(stored)

        assertTrue(restored.focusPeak)
        assertTrue(restored.zebra)
    }

    @Test
    fun `absent keys mean off, not on`() {
        val restored = MonitoringSettings.fromStored(emptyMap())

        // 首次安装没有任何键。若把「缺键」当成「开着」，用户一进监看页就被条纹糊脸
        assertFalse(restored.focusPeak)
        assertFalse(restored.zebra)
        assertFalse(restored.hasPixelOverlay)
    }

    @Test
    fun `a garbage value is treated as off rather than crashing`() {
        val restored = MonitoringSettings.fromStored(
            mapOf(
                MonitoringSettings.KEY_FOCUS_PEAK to "yes",
                MonitoringSettings.KEY_ZEBRA to "1"
            )
        )

        assertFalse(restored.focusPeak)
        assertFalse(restored.zebra)
    }

    @Test
    fun `only one overlay on still reports that a pixel overlay is active`() {
        assertTrue(MonitoringSettings(focusPeak = true).hasPixelOverlay)
        assertTrue(MonitoringSettings(zebra = true).hasPixelOverlay)
        assertFalse(MonitoringSettings().hasPixelOverlay)
    }

    @Test
    fun `pre-existing settings keys keep decoding unchanged`() {
        // 加上两个新键不能动到旧键的还原规则：老用户的存储里根本没有这两个键
        val restored = MonitoringSettings.fromStored(
            mapOf(
                MonitoringSettings.KEY_ROTATION to ViewRotation.Deg90.name,
                MonitoringSettings.KEY_GRID to GridMode.QUARTER.name,
                MonitoringSettings.KEY_ASPECT to AspectMarker.R16_9.name
            ),
            mirrored = true
        )

        assertEquals(ViewRotation.Deg90, restored.rotation)
        assertEquals(GridMode.QUARTER, restored.gridMode)
        assertEquals(AspectMarker.R16_9, restored.aspectMarker)
        assertTrue(restored.mirrored)
        assertFalse(restored.focusPeak)
    }
}