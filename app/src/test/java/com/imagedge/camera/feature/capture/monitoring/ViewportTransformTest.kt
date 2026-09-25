package com.imagedge.camera.feature.capture.monitoring

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.imagedge.camera.data.model.ViewRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 监看坐标模型验收：letterbox、四角表、镜像语义、缩放/平移夹取、
 *             以及「正向 ∘ 逆向 = 恒等」的往返一致性（T4 触摸对焦的地基）
 * </pre>
 */
class ViewportTransformTest {

    /** 便于手算的小尺寸用例：帧 6×4（3:2），视口 12×12 */
    private val frame = Size(6f, 4f)
    private val square = Size(12f, 12f)

    /** 真实量级：960×640 预览帧 */
    private val realFrame = Size(960f, 640f)

    private fun t(
        rotation: ViewRotation = ViewRotation.Deg0,
        mirrored: Boolean = false,
        zoom: Float = ViewportTransform.MIN_ZOOM,
        pan: Offset = Offset.Zero
    ) = ViewportTransform(rotation, mirrored, zoom, pan)

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, EPS)
        assertEquals(expected.top, actual.top, EPS)
        assertEquals(expected.right, actual.right, EPS)
        assertEquals(expected.bottom, actual.bottom, EPS)
    }

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, EPS)
        assertEquals(expected.y, actual.y, EPS)
    }

    // ── letterbox 适配 ───────────────────────────────────────────────

    @Test
    fun `identity transform fits the frame with letterbox margins`() {
        // 6×4 放进 12×12：min(12/6, 12/4)=2 → 12×8，上下各留 2
        assertRect(Rect(0f, 2f, 12f, 10f), t().contentRect(frame, square))
    }

    @Test
    fun `rotating 90 swaps the frame axes before fitting`() {
        // 旋转后有效尺寸 4×6：min(12/4, 12/6)=2 → 8×12，左右各留 2
        val rect = t(rotation = ViewRotation.Deg90).contentRect(frame, square)

        assertEquals(8f, rect.width, EPS)
        assertEquals(12f, rect.height, EPS)
        assertRect(Rect(2f, 0f, 10f, 12f), rect)
    }

    @Test
    fun `invalid sizes yield an empty rect and no hit rather than NaN geometry`() {
        listOf(
            Size.Zero to square,
            frame to Size.Zero,
            Size(-6f, -4f) to square,
            frame to Size(12f, 0f)
        ).forEach { (f, v) ->
            assertRect(Rect.Zero, t().contentRect(f, v))
            assertNull(t().viewportToFrame(Offset(6f, 6f), f, v))
            assertOffset(Offset.Zero, t().frameToViewport(FramePoint(0.5f, 0.5f), f, v))
        }
    }

    // ── 四角表：旋转与镜像的语义必须写死，不能靠实现顺手 ──────────────

    @Test
    fun `no rotation keeps frame corners on content corners`() {
        val x = t()
        val rect = x.contentRect(frame, square)

        assertOffset(rect.topLeft, x.frameToViewport(FramePoint(0f, 0f), frame, square))
        assertOffset(rect.bottomRight, x.frameToViewport(FramePoint(1f, 1f), frame, square))
    }

    @Test
    fun `rotating 90 clockwise moves frame corners around the content clockwise`() {
        val x = t(rotation = ViewRotation.Deg90)
        val rect = x.contentRect(frame, square) // (2,0)-(10,12)

        // 顺时针 90°：帧的四角沿顺时针各挪一个位置
        assertOffset(rect.topRight, x.frameToViewport(FramePoint(0f, 0f), frame, square))
        assertOffset(rect.topLeft, x.frameToViewport(FramePoint(0f, 1f), frame, square))
        assertOffset(rect.bottomRight, x.frameToViewport(FramePoint(1f, 0f), frame, square))
        assertOffset(rect.bottomLeft, x.frameToViewport(FramePoint(1f, 1f), frame, square))
    }

    @Test
    fun `rotating 180 flips both axes`() {
        val x = t(rotation = ViewRotation.Deg180)
        val rect = x.contentRect(frame, square)

        assertOffset(rect.bottomRight, x.frameToViewport(FramePoint(0f, 0f), frame, square))
        assertOffset(rect.topLeft, x.frameToViewport(FramePoint(1f, 1f), frame, square))
    }

    @Test
    fun `rotating 270 moves frame corners around the content counter-clockwise`() {
        val x = t(rotation = ViewRotation.Deg270)
        val rect = x.contentRect(frame, square) // (2,0)-(10,12)

        // 逆时针 90°：帧的四角沿逆时针各挪一个位置（与 Deg90 恰好相反）
        assertOffset(rect.bottomLeft, x.frameToViewport(FramePoint(0f, 0f), frame, square))
        assertOffset(rect.bottomRight, x.frameToViewport(FramePoint(0f, 1f), frame, square))
        assertOffset(rect.topLeft, x.frameToViewport(FramePoint(1f, 0f), frame, square))
        assertOffset(rect.topRight, x.frameToViewport(FramePoint(1f, 1f), frame, square))
    }

    @Test
    fun `mirror flips horizontally and leaves the vertical axis alone`() {
        val mirrored = t(mirrored = true)
        val rect = t().contentRect(frame, square) // (0,2)-(12,10)

        // 帧左上 → 内容右上；帧右上 → 内容左上
        assertOffset(rect.topRight, mirrored.frameToViewport(FramePoint(0f, 0f), frame, square))
        assertOffset(rect.topLeft, mirrored.frameToViewport(FramePoint(1f, 0f), frame, square))
        // 纵向不变：帧左下仍在内容右下…
        assertOffset(rect.bottomRight, mirrored.frameToViewport(FramePoint(0f, 1f), frame, square))
        assertOffset(rect.bottomLeft, mirrored.frameToViewport(FramePoint(1f, 1f), frame, square))
    }

    @Test
    fun `mirror is applied after rotation, not before`() {
        // 语义固定为「先摆正朝向，再左右翻」。若绘制与命中测试的施加顺序不一致，
        // 画面会显示正常但点下去左右反向——这里把 90°+镜像 的结果显式钉住
        val x = t(rotation = ViewRotation.Deg90, mirrored = true)
        val rect = x.contentRect(frame, square) // (2,0)-(10,12)

        // 纯 90° 时帧左上在内容右上；叠加镜像后换到内容左上
        assertOffset(rect.topLeft, x.frameToViewport(FramePoint(0f, 0f), frame, square))
        assertOffset(rect.bottomLeft, x.frameToViewport(FramePoint(1f, 0f), frame, square))
    }

    // ── 命中测试 ─────────────────────────────────────────────────────

    @Test
    fun `taps in the letterbox margin return null instead of clamping to the edge`() {
        val x = t()
        // 内容矩形 (0,2)-(12,10)：上留边里的点不能算命中
        assertNull(x.viewportToFrame(Offset(6f, 0.5f), frame, square))
        assertNull(x.viewportToFrame(Offset(6f, 11.5f), frame, square))
        assertEquals(FramePoint(0.5f, 0.5f), x.viewportToFrame(Offset(6f, 6f), frame, square))
    }

    @Test
    fun `a point a fraction of a pixel outside still counts as a hit`() {
        // 内容上边在 y=2：1.8 只差 0.2 像素，落在容差内算命中；0.5 差 1.5 像素则不算
        assertNotNull(t().viewportToFrame(Offset(6f, 1.8f), frame, square))
        assertNull(t().viewportToFrame(Offset(6f, 0.4f), frame, square))
    }

    @Test
    fun `the frame center maps back to the normalized center`() {
        assertEquals(
            FramePoint(0.25f, 0.75f),
            t(rotation = ViewRotation.Deg180)
                .viewportToFrame(
                    t(rotation = ViewRotation.Deg180).frameToViewport(FramePoint(0.25f, 0.75f), frame, square),
                    frame,
                    square
                )
        )
    }

    // ── 缩放与平移 ───────────────────────────────────────────────────

    @Test
    fun `zoom is clamped to the display-only range and survives dirty input`() {
        assertEquals(ViewportTransform.MIN_ZOOM, t(zoom = 0f).effectiveZoom, EPS)
        assertEquals(ViewportTransform.MIN_ZOOM, t(zoom = -3f).effectiveZoom, EPS)
        assertEquals(ViewportTransform.MIN_ZOOM, t(zoom = Float.NaN).effectiveZoom, EPS)
        assertEquals(ViewportTransform.MIN_ZOOM, t(zoom = Float.POSITIVE_INFINITY).effectiveZoom, EPS)
        assertEquals(ViewportTransform.MAX_ZOOM, t(zoom = 999f).effectiveZoom, EPS)
    }

    @Test
    fun `zoom scales about the content center`() {
        val x = t(zoom = 2f)

        assertRect(Rect(-6f, -2f, 18f, 14f), x.contentRect(frame, square))
        // 画面中心不动
        assertOffset(Offset(6f, 6f), x.frameToViewport(FramePoint(0.5f, 0.5f), frame, square))
    }

    @Test
    fun `pan is ignored while the content does not exceed the viewport`() {
        // 未放大时 12×8 小于 12×12：没有可平移余量，锁死居中
        assertRect(
            t().contentRect(frame, square),
            t(pan = Offset(500f, 500f)).contentRect(frame, square)
        )
    }

    @Test
    fun `pan cannot drag the content edge inside the viewport`() {
        // 放大 2× 后内容 24×16，横向余量 (24-12)/2 = 6，所以最多右移 6
        assertRect(Rect(0f, -2f, 24f, 14f), t(zoom = 2f, pan = Offset(999f, 0f)).contentRect(frame, square))
    }

    @Test
    fun `pan moves rendering and hit testing identically`() {
        val x = t(zoom = 3f, pan = Offset(-100f, 40f))
        val hit = x.viewportToFrame(
            x.frameToViewport(FramePoint(0.25f, 0.75f), frame, square),
            frame,
            square
        )

        assertNotNull(hit)
        assertEquals(0.25f, hit!!.x, ROUND_TRIP_EPSILON)
        assertEquals(0.75f, hit.y, ROUND_TRIP_EPSILON)
    }

    // ── 手势入口：捏合缩放与拖动（与命中测试共用夹取逻辑）──────────────

    @Test
    fun `zoom about the viewport center keeps that point fixed for every rotation and mirror`() {
        // 视口中心永远在余量之内，是最常用、也必须永远精确锚定的情形
        val anchor = Offset(6f, 6f)
        ViewRotation.entries.forEach { rotation ->
            listOf(false, true).forEach { mirrored ->
                val start = t(rotation = rotation, mirrored = mirrored)
                val before = start.viewportToFrame(anchor, frame, square)
                requireNotNull(before)

                val after = start.zoomedBy(2f, anchor, frame, square)
                    .viewportToFrame(anchor, frame, square)

                assertNotNull(after)
                assertEquals(before.x, after!!.x, 0.001f)
                assertEquals(before.y, after.y, 0.001f)
            }
        }
    }

    @Test
    fun `zoom pins the anchor when the pan slack allows it and otherwise keeps the viewport covered`() {
        // 锚点能否钉住取决于放大后的可平移余量，而余量随旋转（90° 宽高互换）变化，
        // 所以这里由模型自己算出可锚定性，而不是把某个角度的巧合写进期望值：
        // 可锚定 → 锚点下的帧坐标必须不动；不可锚定 → 必须退化为贴边铺满而非留空边。
        listOf(Offset(6f, 6f), Offset(8f, 7f), Offset(2f, 9f), Offset(10f, 3f), Offset(0.6f, 2.2f))
            .forEach { anchor ->
                ViewRotation.entries.forEach { rotation ->
                    listOf(false, true).forEach { mirrored ->
                        val start = t(rotation = rotation, mirrored = mirrored)
                        val before = start.viewportToFrame(anchor, frame, square) ?: return@forEach
                        val zoomed = start.zoomedBy(2f, anchor, frame, square)
                        val center = start.centerInViewport(frame, square)
                        val ratio = zoomed.effectiveZoom / start.effectiveZoom
                        val target = anchor - (anchor - center) * ratio -
                            Offset(square.width / 2f, square.height / 2f)
                        val content = zoomed.contentSize(frame, square)
                        val slackX = ((content.width - square.width) / 2f).coerceAtLeast(0f)
                        val slackY = ((content.height - square.height) / 2f).coerceAtLeast(0f)

                        if (abs(target.x) <= slackX && abs(target.y) <= slackY) {
                            val after = zoomed.viewportToFrame(anchor, frame, square)
                            assertNotNull(after)
                            assertEquals(
                                "rotation=$rotation mirror=$mirrored anchor=$anchor 应钉住锚点",
                                before.x, after!!.x, 0.01f
                            )
                            assertEquals(before.y, after.y, 0.01f)
                        } else {
                            assertCoversViewport(zoomed, frame, square)
                        }
                    }
                }
            }
    }

    @Test
    fun `zoomed content always keeps covering the viewport however the user pinches`() {
        // 放大后宁可让锚点轻微偏移，也不能把画面推出视口留下空边——
        // 监看时看到一条黑边会让人以为画面真的到边界了
        val viewports = listOf(
            square,
            Size(2400f, 1080f),
            Size(1080f, 2400f),
            Size(960f, 640f)
        )
        val frames = listOf(frame, realFrame)
        viewports.forEach { viewport ->
            frames.forEach { f ->
                ViewRotation.entries.forEach { rotation ->
                    listOf(false, true).forEach { mirrored ->
                        listOf(1.5f, 2f, 3f, 6f).forEach { factor ->
                            listOf(
                                Offset(0f, 0f),
                                Offset(viewport.width, viewport.height),
                                Offset(viewport.width / 4f, viewport.height * 0.9f),
                                Offset(viewport.width * 0.8f, viewport.height / 5f)
                            ).forEach { anchor ->
                                val zoomed = t(rotation, mirrored)
                                    .zoomedBy(factor, anchor, f, viewport)
                                assertCoversViewport(zoomed, f, viewport)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertCoversViewport(x: ViewportTransform, f: Size, viewport: Size) {
        val rect = x.contentRect(f, viewport)
        val content = x.contentSize(f, viewport)
        val context = "rotation=${x.rotation} mirror=${x.mirrored} zoom=${x.zoom} frame=$f viewport=$viewport"
        if (content.width >= viewport.width) {
            assertTrue("$context 左侧留出了空边", rect.left <= EPS)
            assertTrue("$context 右侧留出了空边", rect.right >= viewport.width - EPS)
        }
        if (content.height >= viewport.height) {
            assertTrue("$context 上方留出了空边", rect.top <= EPS)
            assertTrue("$context 下方留出了空边", rect.bottom >= viewport.height - EPS)
        }
    }

    @Test
    fun `zooming back out to one re-centers the view`() {
        val zoomed = t().zoomedBy(3f, Offset(2f, 9f), frame, square)
        assertTrue(zoomed.effectiveZoom > ViewportTransform.MIN_ZOOM)

        val back = zoomed.zoomedBy(1f / zoomed.effectiveZoom, Offset(2f, 9f), frame, square)

        assertEquals(ViewportTransform.MIN_ZOOM, back.effectiveZoom, EPS)
        assertEquals(0f, back.pan.x, EPS)
        assertEquals(0f, back.pan.y, EPS)
    }

    @Test
    fun `zoom at the clamp boundary is a no-op rather than a jitter`() {
        val maxed = t(zoom = ViewportTransform.MAX_ZOOM)
        assertEquals(maxed, maxed.zoomedBy(1.5f, Offset(6f, 6f), frame, square))
        assertEquals(t(), t().zoomedBy(0.2f, Offset(6f, 6f), frame, square))
    }

    @Test
    fun `zoom ignores a non-finite or non-positive factor`() {
        val base = t(zoom = 2f)

        assertEquals(base, base.zoomedBy(Float.NaN, Offset(6f, 6f), frame, square))
        assertEquals(base, base.zoomedBy(0f, Offset(6f, 6f), frame, square))
        assertEquals(base, base.zoomedBy(-1f, Offset(6f, 6f), frame, square))
    }

    @Test
    fun `drag cannot move the view while the content does not fill the viewport`() {
        assertEquals(t(), t().draggedBy(Offset(50f, 50f), frame, square))
    }

    @Test
    fun `drag moves the view by the requested offset once zoomed in`() {
        // 放大 3× 后内容 36×24，横向余量 (36-12)/2 = 12
        val dragged = t(zoom = 3f).draggedBy(Offset(10f, 0f), frame, square)

        assertEquals(10f, dragged.pan.x, EPS)
        assertEquals(0f, dragged.pan.y, EPS)
    }

    @Test
    fun `drag rejects a non-finite delta instead of poisoning the transform`() {
        val base = t(zoom = 2f)

        assertEquals(base, base.draggedBy(Offset(Float.NaN, 5f), frame, square))
        assertEquals(base, base.draggedBy(Offset(5f, Float.POSITIVE_INFINITY), frame, square))
        assertEquals(base, base.draggedBy(Offset.Zero, frame, square))
    }

    // ── 往返一致性：T4 触摸对焦依赖这一条 ────────────────────────────

    @Test
    fun `forward and inverse round trip across every rotation mirror and zoom`() {
        ViewRotation.entries.forEach { rotation ->
            listOf(false, true).forEach { mirrored ->
                listOf(1f, 1.75f, 3f, ViewportTransform.MAX_ZOOM).forEach { zoom ->
                    listOf(Offset.Zero, Offset(120f, -80f), Offset(-40f, 300f)).forEach { pan ->
                        assertRoundTrip(t(rotation, mirrored, zoom, pan), frame, square)
                    }
                }
            }
        }
    }

    @Test
    fun `round trip holds for portrait landscape and square viewports at real preview dimensions`() {
        listOf(
            Size(2400f, 1080f),   // 横屏工作台
            Size(1080f, 2400f),   // 竖屏
            Size(2000f, 2000f),   // 方形
            Size(960f, 640f)      // 与帧同比例：无留边
        ).forEach { viewport ->
            ViewRotation.entries.forEach { rotation ->
                listOf(false, true).forEach { mirrored ->
                    assertRoundTrip(
                        t(rotation, mirrored, 2.5f, Offset(60f, -20f)),
                        realFrame,
                        viewport
                    )
                }
            }
        }
    }

    private fun assertRoundTrip(x: ViewportTransform, frame: Size, viewport: Size) {
        val steps = 10
        for (i in 0..steps) {
            for (j in 0..steps) {
                val original = FramePoint(i / steps.toFloat(), j / steps.toFloat())
                val hit = x.viewportToFrame(x.frameToViewport(original, frame, viewport), frame, viewport)
                assertNotNull(
                    "rotation=${x.rotation} mirror=${x.mirrored} zoom=${x.zoom} pan=${x.pan} " +
                        "frame=$frame viewport=$viewport 的帧坐标 $original 反查不回来",
                    hit
                )
                assertEquals(original.x, hit!!.x, ROUND_TRIP_EPSILON)
                assertEquals(original.y, hit.y, ROUND_TRIP_EPSILON)
            }
        }
    }

    @Test
    fun `identity flag reports the reset state and rejects any deviation`() {
        assertTrue(ViewportTransform.IDENTITY.isIdentity)
        assertTrue(t().isIdentity)
        assertTrue(!t(mirrored = true).isIdentity)
        assertTrue(!t(rotation = ViewRotation.Deg180).isIdentity)
        assertTrue(!t(zoom = 1.2f).isIdentity)
        assertTrue(!t(pan = Offset(1f, 0f)).isIdentity)
    }

    private companion object {
        const val EPS = 0.0001f

        /** 归一化坐标的往返容差：放大到上限时浮点误差仍在千分之几以内 */
        const val ROUND_TRIP_EPSILON = 0.005f
    }
}
