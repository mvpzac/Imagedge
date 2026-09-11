package com.imagedge.camera.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 编辑管线：把一串 [EditStep] 应用到图片上。
 *
 * **非破坏性**：管线本身只是一份「配方」，[render] 每次都从原图重新生成，
 * 因此调整可以随意增删（撤销 / 回退）、序列化成预设、或整套套用到别的照片（批处理）。
 *
 * 性能策略：
 * - 颜色类步骤（亮度/对比度/饱和度/色温）合成**一个** ColorMatrix，
 *   通过 Canvas + ColorFilter 一次绘制完成，不逐像素运算
 * - 几何类步骤先执行，缩小后续处理的画布
 */
class ImagePipeline(val steps: List<EditStep>) {

    /**
     * 渲染完整结果。
     * @param source 原图（不会被修改）
     * @return 新的位图；调用方负责在不再使用时回收
     */
    suspend fun render(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        renderInternal(source)
    }

    /**
     * 只应用几何步骤（拉直 / 旋转 / 翻转 / 裁剪），不做颜色处理。
     *
     * 裁剪界面的「所见即所选」依赖它：裁剪框是画在**未裁剪**的画面上，
     * 因此裁剪模式需要一张「已拉直+旋转+翻转但未裁剪」的底图。
     *
     * @return 新的位图；与原图相同（无几何步骤）时直接返回原图，调用方需按引用判断是否回收
     */
    suspend fun renderGeometry(source: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        applyGeometry(source)
    }

    /**
     * 渲染预览（降采样后渲染，用于编辑界面的实时反馈）。
     *
     * 大图（如 6000×4000）每帧都全量渲染会明显卡顿，
     * 预览走降采样，确认后再用 [render] 出全尺寸结果。
     */
    suspend fun renderPreview(source: Bitmap, maxLongEdge: Int = 1080): Bitmap =
        withContext(Dispatchers.Default) {
            val scale = (maxLongEdge.toFloat() / maxOf(source.width, source.height))
                .coerceAtMost(1f)
            val preview = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                source
            }
            try {
                renderInternal(preview)
            } finally {
                if (preview !== source) preview.recycle()
            }
        }

    /** 返回追加一步后的新管线（原管线不变，符合非破坏性） */
    fun withStep(step: EditStep): ImagePipeline = ImagePipeline(steps + step)

    /** 返回替换同类步骤后的新管线（同类调整只保留最后一个，避免叠加失控） */
    fun replacingSameKind(step: EditStep): ImagePipeline {
        val filtered = steps.filterNot { it::class == step::class }
        return ImagePipeline(filtered + step)
    }

    /** 是否没有任何编辑步骤 */
    fun isEmpty(): Boolean = steps.isEmpty()

    // ── 内部实现 ───────────────────────────────────────────────────────────

    private fun renderInternal(source: Bitmap): Bitmap {
        var bmp = applyGeometry(source)
        val matrix = buildColorMatrix()
        if (matrix != null) {
            val painted = applyColor(bmp, matrix)
            if (painted !== bmp && bmp !== source) bmp.recycle()
            bmp = painted
        }
        return bmp
    }

    /**
     * 几何：**拉直 → 旋转 → 翻转 → 裁剪**（顺序固定，保证结果可预期）。
     *
     * 裁剪放最后：用户在裁剪界面框选的是「旋转后的画面」，若先裁剪再旋转，
     * 框选区域与实际输出会错位。对应地，UI 在旋转/翻转时必须同步变换裁剪框
     * （见 [Geometry.rotate90] / [Geometry.flipHorizontal]）。
     */
    private fun applyGeometry(source: Bitmap): Bitmap {
        var bmp = source
        var owned = false // bmp 是否为本次调用内部创建的副本（决定回收责任）

        /** 用新位图替换当前位图，并回收「本函数自己产生的」旧副本（绝不回收调用方的原图） */
        fun replace(next: Bitmap) {
            if (owned && bmp !== source) runCatching { bmp.recycle() }
            bmp = next
            owned = next !== source
        }

        // ① 拉直：旋转小角度后裁掉四角空白（缩放围绕中心，保持长宽比）
        val straighten = steps.filterIsInstance<EditStep.Straighten>()
            .sumOf { Geometry.normalizeSmallAngle(it.degrees).toDouble() }.toFloat()
        if (kotlin.math.abs(straighten) > 0.05f) {
            replace(straightenBitmap(bmp, straighten))
        }

        // ② 旋转（90° 的整数倍）
        val degrees = steps.filterIsInstance<EditStep.Rotate>().sumOf { it.degrees.toDouble() }
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized > 0.5) {
            replace(transform(bmp, Matrix().apply { postRotate(normalized.toFloat()) }))
        }

        // ③ 翻转（可能同时左右 + 上下，等价于 180° 旋转，但语义分开更直观）
        for (flip in steps.filterIsInstance<EditStep.Flip>()) {
            val matrix = Matrix().apply {
                if (flip.horizontal) postScale(-1f, 1f) else postScale(1f, -1f)
            }
            replace(transform(bmp, matrix))
        }

        // ④ 裁剪（最后一步，坐标为当前画面的归一化值）
        val crop = steps.filterIsInstance<EditStep.Crop>().lastOrNull()
        if (crop != null && !crop.rect.isFull) {
            replace(cropBitmap(bmp, crop.rect.sanitized()))
        }
        return bmp
    }

    /** 旋转/翻转：返回新位图（矩阵变换后按可见内容扩张画布），**不回收输入** */
    private fun transform(src: Bitmap, matrix: Matrix): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)

    /** 裁剪：返回新位图，**不回收输入** */
    private fun cropBitmap(src: Bitmap, rect: NormRect): Bitmap {
        val x = (rect.left * src.width).toInt().coerceIn(0, src.width - 1)
        val y = (rect.top * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (rect.width * src.width).toInt().coerceIn(1, src.width - x)
        val h = (rect.height * src.height).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** 拉直：绕中心旋转小角度，再居中裁掉旋转产生的空白角；**不回收输入**，内部中间产物自行回收 */
    private fun straightenBitmap(source: Bitmap, degrees: Float): Bitmap {
        val w = source.width
        val h = source.height
        val matrix = Matrix().apply {
            postRotate(degrees, w / 2f, h / 2f)
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, w, h, matrix, true)
        val scale = Geometry.straightenScale(w, h, degrees)
        val cw = (w * scale).toInt().coerceAtLeast(1)
        val ch = (h * scale).toInt().coerceAtLeast(1)
        if (cw >= w && ch >= h) return rotated
        val left = (w - cw) / 2
        val top = (h - ch) / 2
        val cropped = Bitmap.createBitmap(rotated, left, top, cw, ch)
        if (cropped !== rotated) rotated.recycle()
        return cropped
    }

    /**
     * 合成颜色矩阵：把多个颜色步骤相乘为一个矩阵。
     * @return null 表示没有颜色调整，无需处理
     */
    private fun buildColorMatrix(): ColorMatrix? {
        var result: ColorMatrix? = null
        for (step in steps) {
            val m = when (step) {
                is EditStep.Brightness -> brightness(step.value)
                is EditStep.Contrast -> contrast(step.value)
                is EditStep.Saturation -> saturation(step.value)
                is EditStep.Temperature -> temperature(step.value)
                else -> null
            } ?: continue
            if (result == null) {
                result = m
            } else {
                result.postConcat(m)
            }
        }
        return result
    }

    private fun applyColor(source: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(
            source.width,
            source.height,
            source.config ?: Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    // ── 单步矩阵 ───────────────────────────────────────────────────────────

    /**
     * 亮度：三通道整体平移（ColorMatrix 每行第 5 个元素即偏移项）。
     * ±128 的幅度足够覆盖常用区间，又不会一步到底。
     */
    private fun brightness(value: Float): ColorMatrix {
        val delta = value.coerceIn(-1f, 1f) * 128f
        return ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, delta,
                0f, 1f, 0f, 0f, delta,
                0f, 0f, 1f, 0f, delta,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** 对比度：围绕中灰(128)做缩放 —— out = (in - 128) * scale + 128 */
    private fun contrast(value: Float): ColorMatrix {
        val scale = (1f + value.coerceIn(-1f, 1f)).coerceIn(0f, 2f)
        val offset = 128f * (1f - scale)
        return ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** 饱和度：复用系统实现（0 = 灰度，1 = 原始，2 = 加倍） */
    private fun saturation(value: Float): ColorMatrix =
        ColorMatrix().apply {
            setSaturation((1f + value.coerceIn(-1f, 1f)).coerceIn(0f, 2f))
        }

    /** 色温：红蓝通道反向偏移（暖 = 红升蓝降）。±15% 是观感与失真的平衡点 */
    private fun temperature(value: Float): ColorMatrix {
        val amount = value.coerceIn(-1f, 1f) * 0.15f
        return ColorMatrix(
            floatArrayOf(
                1f + amount, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - amount, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }
}
