package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 几何换算单测（纯 Kotlin，无需 Robolectric）。
 *
 * 覆盖新增的裁剪 / 旋转 / 翻转 / 拉直：
 * 这些换算一旦错了，用户看到的就是「框选的和导出的不是同一块画面」。
 */
class GeometryTest {

    private val eps = 1e-3f

    @Test
    fun `straighten scale shrinks by root two at 45 degrees for square`() {
        val scale = Geometry.straightenScale(1000, 1000, 45f)
        assertEquals(0.7071f, scale, 5e-3f)
    }

    @Test
    fun `straighten scale is one without rotation and monotonic with angle`() {
        assertEquals(1f, Geometry.straightenScale(4000, 3000, 0f), eps)
        val mild = Geometry.straightenScale(4000, 3000, 5f)
        val strong = Geometry.straightenScale(4000, 3000, 20f)
        assertTrue("角度越大裁得越多：$mild vs $strong", strong < mild && mild < 1f)
    }

    @Test
    fun `small angle normalizes into minus 45 to 45`() {
        assertEquals(10f, Geometry.normalizeSmallAngle(10f), eps)
        assertEquals(-5f, Geometry.normalizeSmallAngle(-5f), eps)
        assertEquals(0f, Geometry.normalizeSmallAngle(90f), eps)
        assertEquals(0f, Geometry.normalizeSmallAngle(180f), eps)
    }

    @Test
    fun `rotate crop rect 90 degrees clockwise four times returns to original`() {
        val rect = NormRect(0.1f, 0.2f, 0.6f, 0.5f)
        val once = Geometry.rotate90(rect, 1)
        // 顺时针一次：左上角由 (0.1,0.2) 变到 (1-0.5, 0.1) = (0.5, 0.1)
        assertEquals(0.5f, once.left, eps)
        assertEquals(0.1f, once.top, eps)
        val four = Geometry.rotate90(rect, 4)
        assertEquals(rect.left, four.left, eps)
        assertEquals(rect.top, four.top, eps)
        assertEquals(rect.right, four.right, eps)
        assertEquals(rect.bottom, four.bottom, eps)
    }

    @Test
    fun `flip rect twice returns to original and stays inside bounds`() {
        val rect = NormRect(0.2f, 0.3f, 0.7f, 0.6f)
        val h = Geometry.flipHorizontal(rect)
        assertEquals(0.3f, h.left, eps)
        assertEquals(0.8f, h.right, eps)
        val back = Geometry.flipHorizontal(h)
        assertEquals(rect.left, back.left, eps)
        assertEquals(rect.right, back.right, eps)

        val v = Geometry.flipVertical(rect)
        assertEquals(0.4f, v.top, eps)
        assertEquals(0.7f, v.bottom, eps)
    }

    @Test
    fun `fit aspect keeps center and matches pixel aspect`() {
        // 画面 4:3（宽/高 = 1.333），要求 1:1 → 归一化空间里目标比例 = 1/1.333 = 0.75
        val rect = NormRect(0.1f, 0.1f, 0.9f, 0.9f)
        val fitted = Geometry.fitAspect(rect, imageAspect = 4f / 3f, targetAspect = 1f)
        assertEquals(rect.centerX, fitted.centerX, eps)
        assertEquals(rect.centerY, fitted.centerY, eps)
        val normRatio = fitted.width / fitted.height
        assertEquals(0.75f, normRatio, 5e-3f)
        // 像素比例 = 归一化比例 × 画面宽高比 → 应回到 1:1
        assertEquals(1f, normRatio * (4f / 3f), 5e-3f)
    }

    @Test
    fun `max rect for aspect takes full width for wide target and full height for tall target`() {
        val imageAspect = 3f / 2f
        val wide = Geometry.maxRectForAspect(imageAspect, 16f / 9f)
        assertEquals(0f, wide.left, eps)
        assertEquals(1f, wide.right, eps)
        assertTrue("更宽的比例应保留全宽、裁掉上下：$wide", wide.height < 1f)

        val tall = Geometry.maxRectForAspect(imageAspect, 9f / 16f)
        assertEquals(0f, tall.top, eps)
        assertEquals(1f, tall.bottom, eps)
        assertTrue("更高的比例应保留全高、裁掉左右：$tall", tall.width < 1f)

        assertEquals(NormRect.FULL, Geometry.maxRectForAspect(imageAspect, null))
    }

    @Test
    fun `sanitized clamps into unit square and enforces minimum size`() {
        val bad = NormRect(-0.5f, 0.9f, 1.4f, 0.95f).sanitized()
        assertTrue(bad.left >= 0f && bad.top >= 0f && bad.right <= 1f && bad.bottom <= 1f)
        assertTrue("必须有最小面积，否则框会缩成一条线再也拖不回来", bad.width >= NormRect.MIN_SIZE - eps)
        assertTrue(bad.height >= NormRect.MIN_SIZE - eps)
    }

    @Test
    fun `free resize moves only the dragged edges and respects bounds`() {
        val rect = NormRect(0.2f, 0.2f, 0.8f, 0.8f)
        // 右下角向右下拖 → 右/下边跟随（已贴边则不再越界）
        val bigger = Geometry.resizeCrop(rect, Geometry.Corner.BOTTOM_RIGHT, 0.5f, 0.5f, null)
        assertEquals(1f, bigger.right, eps)
        assertEquals(1f, bigger.bottom, eps)
        assertEquals(rect.left, bigger.left, eps)
        assertEquals(rect.top, bigger.top, eps)
        // 左上角向右下拖 → 左边/上边收缩，但不越过右边
        val shrunk = Geometry.resizeCrop(rect, Geometry.Corner.TOP_LEFT, 0.9f, 0.9f, null)
        assertTrue(shrunk.width >= NormRect.MIN_SIZE - eps)
        assertTrue(shrunk.left < rect.right && shrunk.top < rect.bottom)
    }

    @Test
    fun `locked resize keeps aspect with opposite corner as anchor`() {
        val rect = NormRect(0.1f, 0.1f, 0.7f, 0.5f)
        val k = 0.75f // 归一化空间里的目标比例
        val out = Geometry.resizeCrop(rect, Geometry.Corner.BOTTOM_RIGHT, 0.1f, 0.05f, k)
        assertEquals("锚点（左上角）必须固定", rect.left, out.left, eps)
        assertEquals(rect.top, out.top, eps)
        assertEquals(k, out.width / out.height, 5e-3f)
        assertTrue(out.right <= 1f + eps && out.bottom <= 1f + eps)
    }

    @Test
    fun `move crop keeps size and stays inside canvas`() {
        val rect = NormRect(0.2f, 0.2f, 0.6f, 0.6f)
        val moved = Geometry.moveCrop(rect, 0.5f, -0.5f)
        assertEquals(rect.width, moved.width, eps)
        assertEquals(rect.height, moved.height, eps)
        assertEquals(0.6f, moved.left, eps)
        assertEquals(0f, moved.top, eps)
    }
}
