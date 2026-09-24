package com.imagedge.camera.feature.control.monitoring

import com.imagedge.camera.data.model.AspectMarker
import com.imagedge.camera.data.model.GridMode
import com.imagedge.camera.data.model.MonitoringSettings
import com.imagedge.camera.data.model.ViewRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 监看偏好枚举语义与存储解码的验收测试
 * </pre>
 */
class MonitoringSettingsTest {

    @Test
    fun `stored values decode back to the same enums`() {
        ViewRotation.entries.forEach { rotation ->
            val decoded = MonitoringSettings.fromStored(
                mapOf(MonitoringSettings.KEY_ROTATION to rotation.name)
            )
            assertEquals(rotation, decoded.rotation)
        }
        GridMode.entries.forEach { grid ->
            val decoded = MonitoringSettings.fromStored(mapOf(MonitoringSettings.KEY_GRID to grid.name))
            assertEquals(grid, decoded.gridMode)
        }
        AspectMarker.entries.forEach { marker ->
            val decoded = MonitoringSettings.fromStored(mapOf(MonitoringSettings.KEY_ASPECT to marker.name))
            assertEquals(marker, decoded.aspectMarker)
        }
    }

    @Test
    fun `unknown or missing stored values fall back instead of crashing`() {
        // 降级安装、手改配置文件、版本间枚举增删都可能留下读不懂的值。
        // 这里绝不能抛异常或给出「上一个有效值的一半」——整套偏好回退到默认才是诚实结果
        val decoded = MonitoringSettings.fromStored(
            mapOf(
                MonitoringSettings.KEY_ROTATION to "Deg45",
                MonitoringSettings.KEY_GRID to "DIAGONAL",
                MonitoringSettings.KEY_ASPECT to null
            )
        )

        assertEquals(MonitoringSettings.DEFAULT, decoded)
        assertEquals(MonitoringSettings.DEFAULT, MonitoringSettings.fromStored(emptyMap()))
    }

    @Test
    fun `mirror flag is decoded separately because boolean storage differs`() {
        val stored = emptyMap<String, String?>()

        assertTrue(MonitoringSettings.fromStored(stored, mirrored = true).mirrored)
        assertFalse(MonitoringSettings.fromStored(stored, mirrored = false).mirrored)
    }

    @Test
    fun `rotation steps clockwise through all four quarter turns and wraps`() {
        var current = ViewRotation.Deg0
        repeat(4) { current = current.rotate90Clockwise() }

        assertEquals(ViewRotation.Deg0, current)
        assertEquals(ViewRotation.Deg90, ViewRotation.Deg0.rotate90Clockwise())
        assertEquals(ViewRotation.Deg270, ViewRotation.Deg180.rotate90Clockwise())
    }

    @Test
    fun `only the quarter turns swap the frame axes`() {
        assertFalse(ViewRotation.Deg0.swapsAxes)
        assertTrue(ViewRotation.Deg90.swapsAxes)
        assertFalse(ViewRotation.Deg180.swapsAxes)
        assertTrue(ViewRotation.Deg270.swapsAxes)
        assertEquals(90, ViewRotation.Deg90.degrees)
    }
}
