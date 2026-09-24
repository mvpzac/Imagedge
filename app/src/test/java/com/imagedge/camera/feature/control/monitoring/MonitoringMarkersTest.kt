package com.imagedge.camera.feature.control.monitoring

import androidx.compose.ui.geometry.Rect
import com.imagedge.camera.data.model.AspectMarker
import com.imagedge.camera.data.model.GridMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 网格与比例标记几何的验收测试
 * </pre>
 */
class MonitoringMarkersTest {

    /** 3:2 监看内容，1200×800 */
    private val content = Rect(0f, 0f, 1200f, 800f)

    // ── 网格 ─────────────────────────────────────────────────────────

    @Test
    fun `grid fractions follow the mode and NONE draws nothing`() {
        assertTrue(MonitoringMarkers.gridFractions(GridMode.NONE).isEmpty())
        assertEquals(listOf(1f / 3f, 2f / 3f), MonitoringMarkers.gridFractions(GridMode.THIRD))
        assertEquals(listOf(0.25f, 0.5f, 0.75f), MonitoringMarkers.gridFractions(GridMode.QUARTER))
    }

    @Test
    fun `every grid fraction is strictly inside the frame`() {
        // 等分点不能贴在边缘上——贴边的线与画面边框重合，等于白画
        listOf(GridMode.THIRD, GridMode.QUARTER).forEach { mode ->
            MonitoringMarkers.gridFractions(mode).forEach { fraction ->
                assertTrue("$mode 的 $fraction 不在 (0,1) 内", fraction > 0f && fraction < 1f)
            }
        }
    }

    // ── 比例标记 ─────────────────────────────────────────────────────

    @Test
    fun `none marker and invalid content draw nothing`() {
        assertNull(MonitoringMarkers.aspectRect(content, AspectMarker.NONE))
        assertNull(MonitoringMarkers.aspectRect(Rect.Zero, AspectMarker.R16_9))
        assertNull(MonitoringMarkers.aspectRect(Rect(0f, 0f, 1200f, -800f), AspectMarker.R1_1))
    }

    @Test
    fun `a wider marker is limited by content width`() {
        // 3:2 内容里放 16:9：1200/1.7778 = 675 < 800 → 受宽度限制，横向铺满
        val rect = MonitoringMarkers.aspectRect(content, AspectMarker.R16_9)

        requireNotNull(rect)
        assertEquals(1200f, rect.width, 1f)
        assertEquals(675f, rect.height, 1f)
        // 垂直居中
        assertEquals(62.5f, rect.top, 1f)
        assertEquals(0f, rect.left, 1f)
    }

    @Test
    fun `a taller marker is limited by content height`() {
        // 3:2 内容里放 1:1：1200/1 = 1200 > 800 → 受高度限制
        val rect = MonitoringMarkers.aspectRect(content, AspectMarker.R1_1)

        requireNotNull(rect)
        assertEquals(800f, rect.height, 1f)
        assertEquals(800f, rect.width, 1f)
        assertEquals(200f, rect.left, 1f)
        assertEquals(0f, rect.top, 1f)
    }

    @Test
    fun `a marker matching the content ratio fills it exactly`() {
        val rect = MonitoringMarkers.aspectRect(content, AspectMarker.R3_2)

        requireNotNull(rect)
        assertEquals(content.left, rect.left, 0.01f)
        assertEquals(content.top, rect.top, 0.01f)
        assertEquals(content.right, rect.right, 0.01f)
        assertEquals(content.bottom, rect.bottom, 0.01f)
    }

    @Test
    fun `no marker ever exceeds the content rect and all stay centered`() {
        AspectMarker.entries.filter { it.isDrawn }.forEach { marker ->
            val rect = MonitoringMarkers.aspectRect(content, marker)

            requireNotNull(rect) { "$marker 应给出矩形" }
            assertTrue("$marker 越界", rect.left >= content.left - 0.01f)
            assertTrue("$marker 越界", rect.top >= content.top - 0.01f)
            assertTrue("$marker 越界", rect.right <= content.right + 0.01f)
            assertTrue("$marker 越界", rect.bottom <= content.bottom + 0.01f)
            // 居中：两侧留空相等
            assertEquals(rect.left - content.left, content.right - rect.right, 0.05f)
            assertEquals(rect.top - content.top, content.bottom - rect.bottom, 0.05f)
            // 比例准确
            assertEquals(marker.ratio, rect.width / rect.height, 0.001f)
        }
    }

    @Test
    fun `markers below the content ratio are limited by height`() {
        // 4:3 (1.333) 在 3:2 (1.5) 内容里 → 受高度限制；2.35:1 比内容更扁 → 受宽度限制
        val fourThree = MonitoringMarkers.aspectRect(content, AspectMarker.R4_3)
        val scope = MonitoringMarkers.aspectRect(content, AspectMarker.R235)

        requireNotNull(fourThree)
        requireNotNull(scope)
        assertEquals(800f, fourThree.height, 1f)
        assertEquals(1200f, scope.width, 1f)
    }

    @Test
    fun `only a positive finite aspect ratio is drawn`() {
        assertFalse(AspectMarker.NONE.isDrawn)
        AspectMarker.entries.filter { it != AspectMarker.NONE }.forEach {
            assertTrue("$it 应可绘制", it.isDrawn)
        }
    }
}
