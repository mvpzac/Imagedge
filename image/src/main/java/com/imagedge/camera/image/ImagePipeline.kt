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
 * - 几何类步骤（裁剪/旋转）先执行，缩小后续处理的画布
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

    /** 几何：先裁剪再旋转（顺序固定，保证结果可预期） */
    private fun applyGeometry(source: Bitmap): Bitmap {
        var bmp = source

        val crop = steps.filterIsInstance<EditStep.Crop>().lastOrNull()
        if (crop != null) {
            val rect = crop.rect
            if (rect.width() < 0.999f || rect.height() < 0.999f) {
                val x = (rect.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                val y = (rect.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                val w = ((rect.right - rect.left) * bmp.width).toInt()
                    .coerceIn(1, bmp.width - x)
                val h = ((rect.bottom - rect.top) * bmp.height).toInt()
                    .coerceIn(1, bmp.height - y)
                bmp = Bitmap.createBitmap(bmp, x, y, w, h)
            }
        }

        val degrees = steps.filterIsInstance<EditStep.Rotate>().sumOf { it.degrees.toDouble() }
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized > 0.5) {
            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }
        return bmp
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
