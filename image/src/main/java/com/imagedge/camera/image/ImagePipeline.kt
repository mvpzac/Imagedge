package com.imagedge.camera.image

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 几何编辑管线：把一串几何类 [EditStep] 应用到图片上。
 *
 * **非破坏性**：管线本身只是一份配方，[renderGeometry] 每次都从输入重新生成，
 * 因此步骤可以随意增删（撤销 / 回退）、序列化成预设、或整套套用到别的照片。
 *
 * **只管几何，不管颜色。** 调色与 LUT 一律走 :lut 的 LutProcessor：那里有线性光的
 * 正确实现，CPU 与 GPU 共用同一套换算（见 :lut 的 SrgbTransfer）。
 *
 * 这里曾另有一份基于 ColorMatrix 的调色实现：亮度是三通道**加法平移**、对比度绕
 * 编码 128 缩放、色温是 R/B 跷跷板——全是 sRGB 编码空间里的操作，正是 :lut 刚修掉的
 * 那个色彩空间错误的第二份副本。而它**没有任何调用方**（本类全仓只被以 renderGeometry
 * 方式使用），留着就等于对后来的人说「这里也有调色」。两份实现并存，迟早有人接上错的那份。
 */
class ImagePipeline(val steps: List<EditStep>) {

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

    // ── 内部实现 ───────────────────────────────────────────────────────────

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
}
