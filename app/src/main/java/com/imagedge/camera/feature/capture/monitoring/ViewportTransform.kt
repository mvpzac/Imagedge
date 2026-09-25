package com.imagedge.camera.feature.capture.monitoring

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.imagedge.camera.data.model.ViewRotation
import kotlin.math.min

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 监看画面统一坐标模型（T1），为 T4 触摸对焦预留逆变换
 *     version: 1.0
 * </pre>
 */

/** 帧内归一化坐标（0..1），与像素尺寸无关；T4 据此换算相机侧的触摸对焦坐标 */
data class FramePoint(val x: Float, val y: Float)

/**
 * 监看画面统一坐标模型。
 *
 * 显示链自内向外：**等比适配（letterbox）→ 用户放大 → 旋转 → 镜像 → 平移到视口中心**。
 * 旋转只取 90° 整数倍，所以旋转后的外框仍是轴对齐矩形，正逆向都能用初等算术写死。
 *
 * **本类的映射与 [MonitoringWorkstation] 的 `withTransform` 调用序列是同一分解**：
 * 绘制与命中测试共用这里的 [contentRect]/[effectiveZoom]/[centerInViewport]。
 * 改动任一处必须同步另一处，并由 `ViewportTransformTest` 的四角表、往返一致性与
 * 缩放锚点测试钉住。一旦两者漂移，用户看到画面在 A 处、点下去却在 B 处，
 * T4 的触摸对焦就会建立在错的基础上。
 *
 * 本类是纯显示层模型：**不产生任何相机命令**。放大只是把已收到的预览帧画大，
 * 与镜头变焦无关（方案明确「数码放大预览不算相机变焦」）；电动/清晰影像变焦属 T4。
 *
 * @param rotation 显示级旋转，不改相机文件朝向
 * @param mirrored 镜像，作用于**旋转之后**的显示朝向（自拍监看的常识：先摆正再左右翻）
 * @param zoom 显示级数码放大倍数，读取时夹取到 [MIN_ZOOM]..[MAX_ZOOM]
 * @param pan 视口像素平移；读取时按可平移余量夹取，调用方不必自行保证不越界
 */
data class ViewportTransform(
    val rotation: ViewRotation = ViewRotation.Deg0,
    val mirrored: Boolean = false,
    val zoom: Float = MIN_ZOOM,
    val pan: Offset = Offset.Zero
) {

    /** 夹取后的缩放：手势脏值、恢复出的 NaN/无穷都不允许变成 NaN 尺寸或无界放大 */
    val effectiveZoom: Float = if (!zoom.isFinite()) MIN_ZOOM else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** 旋转后的帧尺寸（90°/270° 宽高互换）；letterbox 必须按它算，不能拿原始宽高 */
    fun effectiveFrameSize(frame: Size): Size =
        if (rotation.swapsAxes) Size(frame.height, frame.width) else frame

    /** 等比适配缩放（Fit，含留边），不含用户放大；尺寸非法时返回 0 */
    fun fitScale(frame: Size, viewport: Size): Float {
        val eff = effectiveFrameSize(frame)
        if (frame.width <= 0f || frame.height <= 0f ||
            viewport.width <= 0f || viewport.height <= 0f ||
            eff.width <= 0f || eff.height <= 0f
        ) {
            return 0f
        }
        return min(viewport.width / eff.width, viewport.height / eff.height)
    }

    /** 缩放后的画面尺寸（像素）；未就绪时为 [Size.Zero] */
    fun contentSize(frame: Size, viewport: Size): Size {
        val s = fitScale(frame, viewport)
        if (s <= 0f) return Size.Zero
        val eff = effectiveFrameSize(frame)
        val k = s * effectiveZoom
        return Size(eff.width * k, eff.height * k)
    }

    /** 夹取后的画面中心（视口像素） */
    fun centerInViewport(frame: Size, viewport: Size): Offset {
        val s = fitScale(frame, viewport)
        if (s <= 0f) return Offset.Zero
        return centerFor(contentSize(frame, viewport), viewport)
    }

    /** 画面实际占据的视口矩形（像素）。未就绪（尺寸非法）时为 [Rect.Zero] */
    fun contentRect(frame: Size, viewport: Size): Rect {
        val content = contentSize(frame, viewport)
        if (content.width <= 0f || content.height <= 0f) return Rect.Zero
        val center = centerFor(content, viewport)
        return Rect(center - Offset(content.width / 2f, content.height / 2f), content)
    }

    /**
     * 视口像素 → 帧归一化坐标。
     *
     * @return null 表示点在 letterbox 空白区（画面根本没铺到那儿）。调用方**不得**把 null
     *         当成 (0,0) 处理——那会把对焦打到画面左上角
     */
    fun viewportToFrame(point: Offset, frame: Size, viewport: Size): FramePoint? {
        val s = fitScale(frame, viewport)
        if (s <= 0f) return null
        val eff = effectiveFrameSize(frame)
        val k = s * effectiveZoom
        val center = centerInViewport(frame, viewport)
        // 1) 撤销「平移到视口中心 + 缩放」，回到旋转帧像素空间（0..eff.width × 0..eff.height）
        val rx0 = (point.x - center.x) / k + eff.width / 2f
        val ry0 = (point.y - center.y) / k + eff.height / 2f
        if (!rx0.isFinite() || !ry0.isFinite()) return null
        // 2) 撤销镜像（它在旋转之后施加，而镜像自身即其逆）
        val rx = if (mirrored) eff.width - rx0 else rx0
        val ry = ry0
        // 3) 撤销旋转
        val fx: Float
        val fy: Float
        when (rotation) {
            ViewRotation.Deg0 -> {
                fx = rx; fy = ry
            }

            // 正向 (x,y) -> (帧高 - y, x)；解出逆变换
            ViewRotation.Deg90 -> {
                fx = ry; fy = eff.width - rx
            }

            ViewRotation.Deg180 -> {
                fx = eff.width - rx; fy = eff.height - ry
            }

            ViewRotation.Deg270 -> {
                fx = eff.height - ry; fy = rx
            }
        }
        if (fx < -EDGE_TOLERANCE || fy < -EDGE_TOLERANCE ||
            fx > frame.width + EDGE_TOLERANCE || fy > frame.height + EDGE_TOLERANCE
        ) {
            return null
        }
        return FramePoint(
            x = (fx / frame.width).coerceIn(0f, 1f),
            y = (fy / frame.height).coerceIn(0f, 1f)
        )
    }

    /**
     * 帧归一化坐标 → 视口像素，[viewportToFrame] 的严格逆。
     *
     * T4 把 AF 框画在用户点的位置、或把相机回传的焦点框叠到监画面上时走这里。
     * 尺寸非法时返回 [Offset.Zero]（此时画面上没有任何内容可定位）。
     */
    fun frameToViewport(point: FramePoint, frame: Size, viewport: Size): Offset {
        val s = fitScale(frame, viewport)
        if (s <= 0f) return Offset.Zero
        val eff = effectiveFrameSize(frame)
        val k = s * effectiveZoom
        val center = centerInViewport(frame, viewport)
        val fx = point.x * frame.width
        val fy = point.y * frame.height
        val rx0: Float
        val ry0: Float
        when (rotation) {
            ViewRotation.Deg0 -> {
                rx0 = fx; ry0 = fy
            }

            ViewRotation.Deg90 -> {
                rx0 = eff.width - fy; ry0 = fx
            }

            ViewRotation.Deg180 -> {
                rx0 = eff.width - fx; ry0 = eff.height - fy
            }

            ViewRotation.Deg270 -> {
                rx0 = fy; ry0 = eff.height - fx
            }
        }
        val rx = if (mirrored) eff.width - rx0 else rx0
        return Offset(
            center.x + (rx - eff.width / 2f) * k,
            center.y + (ry0 - eff.height / 2f) * k
        )
    }

    /**
     * 以视口上的 [anchor] 为不动点缩放。
     *
     * 「双指捏在哪里，画面就以那里为中心放大」是监看手势的基本预期：只按中心放大会让
     * 感兴趣的位置跑掉，用户得反复拖回来。这里保证 anchor 下的帧坐标在缩放前后重合，
     * 且与 [viewportToFrame] 共用同一套中心/夹取逻辑，不会各自漂移。
     *
     * 回到 [MIN_ZOOM] 时内容不再大于视口，平移余量归零 → 自动居中，
     * 于是「放大后拖偏 → 缩回 1× → 再放大」不会从上一个偏移开始。
     */
    fun zoomedBy(factor: Float, anchor: Offset, frame: Size, viewport: Size): ViewportTransform {
        val s = fitScale(frame, viewport)
        if (s <= 0f || !factor.isFinite() || factor <= 0f) return this
        val oldZoom = effectiveZoom
        val newZoom = (oldZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // 已到上下限：整体保持不动，不产生「捏了半天画面在抖」的观感
        if (newZoom == oldZoom) return this
        val ratio = newZoom / oldZoom
        val center = centerInViewport(frame, viewport)
        val desired = anchor - (anchor - center) * ratio
        // 夹取必须按**新**缩放后的内容尺寸做：按旧尺寸夹完再改 zoom，
        // 余量就不对了（放大后画面被推出视口、露出一条空边）。
        val newContent = contentSizeAt(frame, viewport, newZoom)
        return copy(zoom = newZoom, pan = panFor(desired, newContent, viewport))
    }

    /** 拖动平移（夹取由 [withViewportCenter] 负责） */
    fun draggedBy(delta: Offset, frame: Size, viewport: Size): ViewportTransform {
        if (fitScale(frame, viewport) <= 0f) return this
        if (!delta.x.isFinite() || !delta.y.isFinite() || delta == Offset.Zero) return this
        return withViewportCenter(centerInViewport(frame, viewport) + delta, frame, viewport)
    }

    /**
     * 把画面中心移到 [desired]，并按可平移余量夹取。
     *
     * 余量是「画面边沿不离开视口」；内容小于视口时余量为 0，即锁死居中——
     * 不允许把画面整个拖出屏幕外。
     */
    fun withViewportCenter(desired: Offset, frame: Size, viewport: Size): ViewportTransform {
        val content = contentSize(frame, viewport)
        if (content.width <= 0f || content.height <= 0f) return this
        return copy(pan = panFor(desired, content, viewport))
    }

    /** 恒等变换：进工作台时的初值，也是「重置视图」的目标 */
    val isIdentity: Boolean
        get() = rotation == ViewRotation.Deg0 && !mirrored &&
            effectiveZoom == MIN_ZOOM && pan == Offset.Zero

    private fun centerFor(content: Size, viewport: Size): Offset {
        val slack = slackOf(content, viewport)
        return Offset(
            viewport.width / 2f + pan.x.coerceIn(-slack.x, slack.x),
            viewport.height / 2f + pan.y.coerceIn(-slack.y, slack.y)
        )
    }

    /**
     * 指定缩放下的内容尺寸。
     *
     * `zoomedBy` 需要它：夹取必须按**新**缩放算出的余量做，而那一刻 `zoom` 还没提交，
     * 不能直接用 [contentSize]。
     */
    private fun contentSizeAt(frame: Size, viewport: Size, zoomAt: Float): Size {
        val s = fitScale(frame, viewport)
        if (s <= 0f) return Size.Zero
        val eff = effectiveFrameSize(frame)
        val k = s * (if (!zoomAt.isFinite()) MIN_ZOOM else zoomAt.coerceIn(MIN_ZOOM, MAX_ZOOM))
        return Size(eff.width * k, eff.height * k)
    }

    /** 期望中心 → 夹取后的平移量；脏值（NaN/无穷）一律回退为不偏移，而不是让 NaN 扩散 */
    private fun panFor(desiredCenter: Offset, content: Size, viewport: Size): Offset {
        if (!desiredCenter.x.isFinite() || !desiredCenter.y.isFinite()) return Offset.Zero
        val slack = slackOf(content, viewport)
        val target = desiredCenter - Offset(viewport.width / 2f, viewport.height / 2f)
        return Offset(
            target.x.coerceIn(-slack.x, slack.x),
            target.y.coerceIn(-slack.y, slack.y)
        )
    }

    private fun slackOf(content: Size, viewport: Size): Offset = Offset(
        ((content.width - viewport.width) / 2f).coerceAtLeast(0f),
        ((content.height - viewport.height) / 2f).coerceAtLeast(0f)
    )

    companion object {
        /** 显示级放大上下限。960×640 的预览信号放大到 6× 以上只剩马赛克，给更大值没有意义 */
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f

        /** 命中测试允许的像素误差：边缘半像素内算命中，避免「看得见点不中」 */
        private const val EDGE_TOLERANCE = 0.5f

        val IDENTITY = ViewportTransform()
    }
}
