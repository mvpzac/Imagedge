package com.imagedge.camera.feature.capture.monitoring

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.imagedge.camera.feature.capture.CameraControlViewModel.AssistOverlay
import com.imagedge.camera.image.ExposureAnalysis

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/03
 *     desc   : 对焦峰值与斑马纹的叠加绘制
 *     version: 1.1
 * </pre>
 */

/** 斑马纹条纹色。半透以便同时看见被标注的亮部本身 */
internal val ZebraStripe = Color(0xFFFF3B30).copy(alpha = 0.5f)

/** 对焦峰值框线色 */
internal val FocusPeakMark = Color(0xFF00E5FF).copy(alpha = 0.9f)

/** 斑马纹：画面短边大约这么多条。取固定分数，与缩放无关 */
private const val ZEBRA_STRIPE_COUNT = 14f

/**
 * 把帧画进视口所用的变换，**叠加层必须走同一个**。
 *
 * 单独算一遍「掩码格 → 预览坐标」的话，旋转与镜像就得算第二遍，而两处必然漂：
 * 表现是「转了 90° 之后条纹和亮部对不上」，而且只在转屏后才出现。
 * 所以变换抽成这一个入口，画面与叠加层共用——形状变了只改一处。
 *
 * 变换之后绘制处于**原始帧像素坐标**（0..width × 0..height），
 * 叠加层因而直接按原始尺寸换算，不必关心视口、缩放与平移。
 */
internal inline fun DrawScope.withFrameTransform(
    transform: ViewportTransform,
    frameSize: Size,
    viewport: Size,
    block: DrawScope.() -> Unit
) {
    val rect = transform.contentRect(frameSize, viewport)
    val scale = transform.fitScale(frameSize, viewport) * transform.effectiveZoom
    if (scale <= 0f || rect.width <= 0f) return
    withTransform({
        // 顺序必须与 ViewportTransform 的分解一致：先调用的在最外层，
        // 而点的变换顺序自内向外，所以「镜像」排在「旋转」之前
        translate(rect.center.x, rect.center.y)
        if (transform.mirrored) scale(-1f, 1f)
        rotate(transform.rotation.degrees.toFloat())
        translate(-frameSize.width * scale / 2f, -frameSize.height * scale / 2f)
        scale(scale, scale)
    }, block)
}

/**
 * 斑马纹：在原始帧像素空间里，把掩码命中的格子画成斜条纹。
 *
 * 与 [MonitoringMarkers] 同一条规矩：**只画不裁**。预览像素不被改动，
 * 叠加层也不影响用户对成片构图的判断。
 */
internal fun DrawScope.drawAssistMask(
    overlay: AssistOverlay?,
    frameSize: Size,
    color: Color
) {
    if (overlay == null || frameSize.width <= 0f || frameSize.height <= 0f) return
    val cellWidth = frameSize.width / overlay.gridWidth
    val cellHeight = frameSize.height / overlay.gridHeight
    if (cellWidth <= 0f || cellHeight <= 0f) return

    val step = minOf(frameSize.width, frameSize.height) / ZEBRA_STRIPE_COUNT
    if (step <= 0f) return
    val stroke = step / 2f

    for (oy in 0 until overlay.gridHeight) {
        for (run in ExposureAnalysis.rowRuns(overlay.mask, oy * overlay.gridWidth, overlay.gridWidth)) {
            val left = run.first * cellWidth
            val right = run.last * cellWidth
            val top = oy * cellHeight
            val bottom = top + cellHeight

            // 每段各自画斜线而不是「先裁剪再铺满整幅」：当前 Compose 版本已移除
            // DrawScope.clipPath，而按段算区间既不依赖它，也没有整幅过绘
            val span = bottom - top
            var x0 = left - span
            while (x0 <= right) {
                drawLine(
                    color = color,
                    start = Offset(x0, bottom),
                    end = Offset(x0 + span, top),
                    strokeWidth = stroke,
                    cap = StrokeCap.Butt
                )
                x0 += step
            }
        }
    }
}

/**
 * 对焦峰值：画成描边小方框而不是实心块。
 *
 * 实心块在密合焦区域会连成一大片糊住画面，反而看不清焦点在哪；
 * 描边只占格边一线的面积，一眼能数清有几个焦点。
 */
internal fun DrawScope.drawFocusPeaks(
    overlay: AssistOverlay?,
    frameSize: Size,
    color: Color
) {
    if (overlay == null || frameSize.width <= 0f || frameSize.height <= 0f) return
    val mask = overlay.mask
    if (mask.isEmpty()) return
    val cellWidth = frameSize.width / overlay.gridWidth
    val cellHeight = frameSize.height / overlay.gridHeight
    if (cellWidth <= 0f || cellHeight <= 0f) return

    val stroke = (minOf(cellWidth, cellHeight) * 0.22f).coerceAtLeast(0.5f)
    for (oy in 0 until overlay.gridHeight) {
        val rowOffset = oy * overlay.gridWidth
        for (ox in 0 until overlay.gridWidth) {
            if (!mask[rowOffset + ox]) continue
            val left = ox * cellWidth
            val top = oy * cellHeight
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(cellWidth, cellHeight),
                style = Stroke(width = stroke)
            )
        }
    }
}