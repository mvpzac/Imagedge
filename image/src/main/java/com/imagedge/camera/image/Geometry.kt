package com.imagedge.camera.image

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 归一化矩形（0..1 相对当前画面）。
 *
 * 用纯数据类而不是 `android.graphics.RectF`：几何换算（旋转/翻转/比例约束）是纯数学，
 * 放在纯 Kotlin 类型上就能直接写 JVM 单测，不必引入 Robolectric。
 */
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 是否为全图（不裁剪） */
    val isFull: Boolean get() = left <= 0.0005f && top <= 0.0005f && right >= 0.9995f && bottom >= 0.9995f

    /** 收进 0..1 且保证有最小面积，避免出现零宽/负宽矩形 */
    fun sanitized(): NormRect {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        return NormRect(minOf(l, r - MIN_SIZE), minOf(t, b - MIN_SIZE), maxOf(r, l + MIN_SIZE), maxOf(b, t + MIN_SIZE))
    }

    companion object {
        /** 最小裁剪边长（相对值）：防止用户把框拖成一条线后无法再拖回来 */
        const val MIN_SIZE = 0.08f

        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

/**
 * 几何换算（纯函数，可单测）。
 *
 * 管线顺序固定为 **拉直 → 90° 旋转 → 翻转 → 裁剪**：裁剪放在最后，
 * 用户在裁剪界面看到的画面与框选区域才能严格对应。
 * 因此旋转/翻转画面后，必须把裁剪框一起变换，否则框会「漂」到别的内容上。
 */
object Geometry {

    /**
     * 拉直角度对应的「自动裁角」缩放比。
     *
     * 旋转后的画面四角会露出空白，取旋转后画面内**最大的同比例矩形**缩放比即可裁掉黑角。
     * 推导：旋转 θ 后，同比例 (w×h) 矩形能放下的最大尺寸为
     *   w' = w / (cosθ + sinθ·h/w)，h' = h / (cosθ + sinθ·w/h)
     * 取两者缩放的较小值。90° 的整数倍不做缩放（视为纯旋转）。
     *
     * @return 0..1 的缩放比；无旋转时返回 1
     */
    fun straightenScale(width: Int, height: Int, degrees: Float): Float {
        if (width <= 0 || height <= 0) return 1f
        val a = abs(normalizeSmallAngle(degrees)) * (Math.PI / 180.0)
        if (a < 1e-4) return 1f
        val cosA = cos(a)
        val sinA = sin(a)
        val w = width.toDouble()
        val h = height.toDouble()
        val scaleW = w / (cosA * w + sinA * h)
        val scaleH = h / (sinA * w + cosA * h)
        return minOf(scaleW, scaleH).toFloat().coerceIn(0.2f, 1f)
    }

    /** 拉直角度归一到 -45..45（拉直只处理小角度，90° 的倍数交给旋转按钮） */
    fun normalizeSmallAngle(degrees: Float): Float {
        var d = degrees % 90f
        if (d > 45f) d -= 90f
        if (d < -45f) d += 90f
        return d
    }

    /** 顺时针 90° 旋转 [turns] 次后，裁剪框在新画面中的位置 */
    fun rotate90(rect: NormRect, turns: Int): NormRect {
        var r = rect.sanitized()
        val n = ((turns % 4) + 4) % 4
        repeat(n) {
            // 顺时针：原 (x, y) → 新 (1 - y, x)
            r = NormRect(
                left = 1f - r.bottom,
                top = r.left,
                right = 1f - r.top,
                bottom = r.right,
            )
        }
        return r
    }

    /** 左右翻转后裁剪框的位置 */
    fun flipHorizontal(rect: NormRect): NormRect {
        val r = rect.sanitized()
        return NormRect(1f - r.right, r.top, 1f - r.left, r.bottom)
    }

    /** 上下翻转后裁剪框的位置 */
    fun flipVertical(rect: NormRect): NormRect {
        val r = rect.sanitized()
        return NormRect(r.left, 1f - r.bottom, r.right, 1f - r.top)
    }

    /**
     * 把裁剪框调整到指定比例（围绕中心收缩，保证不越界）。
     *
     * @param imageAspect 当前画面的宽高比（宽/高），比例是按**像素**说的，
     *   而归一化坐标是 0..1 的方形空间，两者必须换算，否则 16:9 会裁出 1:1 的观感。
     * @param targetAspect 目标比例（宽/高）；null = 自由比例
     */
    fun fitAspect(rect: NormRect, imageAspect: Float, targetAspect: Float?): NormRect {
        val r = rect.sanitized()
        if (targetAspect == null || imageAspect <= 0f) return r
        // 归一化空间里的目标比例 = 像素比例 / 画面宽高比
        val normTarget = targetAspect / imageAspect
        var w = r.width
        var h = r.height
        if (w / h > normTarget) w = h * normTarget else h = w / normTarget
        // 越界则以中心收缩到能放下的最大尺寸
        w = w.coerceAtMost(1f)
        h = h.coerceAtMost(1f)
        if (w / h > normTarget) w = h * normTarget else h = w / normTarget

        var left = r.centerX - w / 2f
        var top = r.centerY - h / 2f
        left = left.coerceIn(0f, 1f - w)
        top = top.coerceIn(0f, 1f - h)
        return NormRect(left, top, left + w, top + h)
    }

    /** 目标比例下能占满画面的最大矩形（切换比例预设时用） */
    fun maxRectForAspect(imageAspect: Float, targetAspect: Float?): NormRect {
        if (targetAspect == null || imageAspect <= 0f) return NormRect.FULL
        val normTarget = targetAspect / imageAspect
        return if (normTarget >= 1f) {
            // 目标比画面更宽：以宽度为准，高度按比例缩
            val h = (1f / normTarget).coerceIn(NormRect.MIN_SIZE, 1f)
            val top = (1f - h) / 2f
            NormRect(0f, top, 1f, top + h)
        } else {
            val w = normTarget.coerceIn(NormRect.MIN_SIZE, 1f)
            val left = (1f - w) / 2f
            NormRect(left, 0f, left + w, 1f)
        }
    }

    /** 裁剪框的四个角（拖动时按对角固定） */
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * 拖动某个角后的新裁剪框。
     *
     * 锁定比例时以**对角为锚点**按比例缩放（而不是重新居中），拖动手感才自然；
     * 自由比例时两个边独立跟随手指，并各自夹在 [NormRect.MIN_SIZE] 与画布边界内。
     *
     * @param normTargetAspect 目标比例在**归一化空间**中的值（= 像素比例 / 画面宽高比）；
     *   null = 自由比例
     */
    fun resizeCrop(
        rect: NormRect,
        corner: Corner,
        deltaX: Float,
        deltaY: Float,
        normTargetAspect: Float?,
    ): NormRect {
        val r = rect.sanitized()
        val right = corner == Corner.TOP_RIGHT || corner == Corner.BOTTOM_RIGHT
        val bottom = corner == Corner.BOTTOM_LEFT || corner == Corner.BOTTOM_RIGHT
        val minSize = NormRect.MIN_SIZE

        if (normTargetAspect == null || normTargetAspect <= 0f) {
            var l = r.left
            var t = r.top
            var rr = r.right
            var b = r.bottom
            if (right) rr = (rr + deltaX).coerceIn(l + minSize, 1f)
            else l = (l + deltaX).coerceIn(0f, rr - minSize)
            if (bottom) b = (b + deltaY).coerceIn(t + minSize, 1f)
            else t = (t + deltaY).coerceIn(0f, b - minSize)
            return NormRect(l, t, rr, b)
        }

        val k = normTargetAspect
        val anchorX = if (right) r.left else r.right
        val anchorY = if (bottom) r.top else r.bottom
        val dragX = (if (right) r.right else r.left) + deltaX
        val dragY = (if (bottom) r.bottom else r.top) + deltaY

        // 先按手指位移取「宽/高都放得下」的尺寸
        var w = abs(dragX - anchorX)
        var h = abs(dragY - anchorY)
        if (h * k < w) w = h * k else h = w / k

        // 再受画布边界约束（锚点固定，只能向拖动方向延伸）
        val maxW = if (right) 1f - anchorX else anchorX
        val maxH = if (bottom) 1f - anchorY else anchorY
        if (h > maxH) {
            h = maxH
            w = h * k
        }
        if (w > maxW) {
            w = maxW
            h = w / k
        }
        // 最小尺寸（比例保持）
        if (w < minSize || h < minSize) {
            if (normTargetAspect >= 1f) {
                w = minSize
                h = w / k
            } else {
                h = minSize
                w = h * k
            }
        }
        val left = if (right) anchorX else anchorX - w
        val top = if (bottom) anchorY else anchorY - h
        return NormRect(left, top, left + w, top + h).sanitized()
    }

    /** 平移裁剪框（整体拖动），自动夹在画布内 */
    fun moveCrop(rect: NormRect, deltaX: Float, deltaY: Float): NormRect {
        val r = rect.sanitized()
        val left = (r.left + deltaX).coerceIn(0f, 1f - r.width)
        val top = (r.top + deltaY).coerceIn(0f, 1f - r.height)
        return NormRect(left, top, left + r.width, top + r.height)
    }
}
