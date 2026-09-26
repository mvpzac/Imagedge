package com.imagedge.camera.image

import kotlin.math.abs

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 曝光辅助的分析核心（T2）：直方图 / 斑马纹 / 峰值密度，纯函数无 Android 依赖
 *     version: 1.0
 * </pre>
 */

/**
 * 取景帧的曝光分析。
 *
 * 三条规矩（差距文档 T2 的约束）：
 * 1. **算法不进 ViewModel**——这里只吃 `IntArray` 像素，不碰 Bitmap、不碰协程、不碰 GPU，
 *    所以能在 JVM 上用合成灰阶/色块直接验收；
 * 2. **先降采样再分析**：取景目标约 960×640、20fps，逐像素全帧分析在手机上一定拖预览。
 *    采样步长由 [sampleStride] 给出，长边降到约 320px；
 * 3. 结果只是**显示辅助**：预览的色域与伽马未知，所以这里不输出"准确 EV"，
 *    也不使用任何"专业校准"式表述。
 */
object ExposureAnalysis {

    /** 直方图桶数：亮度 0..255 各一桶 */
    const val BINS = 256

    /** 分析时把长边降到的像素数（T2 约束：首版长边约 320px） */
    const val ANALYSIS_LONG_EDGE = 320

    /** 斑马纹默认阈值：Rec.601 亮度 ≥ 此值算高光溢出 */
    const val HIGHLIGHT_THRESHOLD = 235

    /** 峰值对焦默认阈值：边缘梯度 ≥ 此值算合焦边缘 */
    const val PEAK_THRESHOLD = 48

    /**
     * Rec.601 亮度（整数版）。
     *
     * 不用 709：这里是给曝光辅助用的相对量，不是色彩管理，
     * 而 601 的整数系数在低端机上省掉一次浮点往返。
     */
    fun luma(red: Int, green: Int, blue: Int): Int =
        (red * 299 + green * 587 + blue * 114) / 1000

    /** ARGB 整数像素 → 亮度 */
    fun lumaOf(argb: Int): Int = luma(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF
    )

    /**
     * 把长边降到 [targetLongEdge] 所需的采样步长。
     *
     * 步长而不是缩放：缩放要另开一块位图（内存 + 一次遍历），
     * 而直方图/斑马纹这种统计量对"隔几个取一个"完全不敏感。
     */
    fun sampleStride(width: Int, height: Int, targetLongEdge: Int = ANALYSIS_LONG_EDGE): Int {
        val longEdge = maxOf(width, height)
        if (longEdge <= targetLongEdge || targetLongEdge <= 0) return 1
        return (longEdge + targetLongEdge - 1) / targetLongEdge
    }

    /**
     * 亮度直方图。[buckets] 每桶是命中该亮度的**采样像素数**，
     * 归一化要除以 [LumaHistogram.sampleCount]，不是除以总像素数。
     */
    fun histogram(pixels: IntArray, width: Int, height: Int, stride: Int = 1): LumaHistogram {
        val buckets = IntArray(BINS)
        val step = stride.coerceAtLeast(1)
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            val row = y * width
            while (x < width) {
                buckets[lumaOf(pixels[row + x])]++
                samples++
                x += step
            }
            y += step
        }
        return LumaHistogram(buckets, samples)
    }

    /** 高光占比：亮度 ≥ [threshold] 的采样像素比例。斑马纹就是它 + 一个开关阈值 */
    fun highlightRatio(
        pixels: IntArray,
        width: Int,
        height: Int,
        stride: Int = 1,
        threshold: Int = HIGHLIGHT_THRESHOLD
    ): Float {
        val step = stride.coerceAtLeast(1)
        var total = 0
        var over = 0
        var y = 0
        while (y < height) {
            var x = 0
            val row = y * width
            while (x < width) {
                if (lumaOf(pixels[row + x]) >= threshold) over++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else over.toFloat() / total.toFloat()
    }

    /**
     * 峰值对焦的边缘密度：每个采样点上一阶差分梯度的幅值，按 [width]/[stride] 行展开。
     *
     * 用梯度而不是拉普拉斯：合焦判定要看"边缘有多锐"，一阶差分对噪声更稳，
     * 而实时帧本身已经带相机侧降噪。返回值长度 = outWidth * outHeight。
     */
    fun peakDensity(
        pixels: IntArray,
        width: Int,
        height: Int,
        stride: Int = 1
    ): PeakMap {
        val step = stride.coerceAtLeast(1)
        val outWidth = (width + step - 1) / step
        val outHeight = (height + step - 1) / step
        val density = FloatArray(outWidth * outHeight)
        if (outWidth < 2 || outHeight < 2) return PeakMap(outWidth, outHeight, density)

        var oy = 0
        var y = 0
        while (y < height && oy < outHeight) {
            var ox = 0
            var x = 0
            while (x < width && ox < outWidth) {
                // 右邻与下邻取下一行/下一列的真实像素，越界就退回自己（边界不放大）
                val here = lumaOf(pixels[y * width + x])
                val right = lumaOf(pixels[y * width + (x + 1).coerceAtMost(width - 1)])
                val below = lumaOf(pixels[(y + 1).coerceAtMost(height - 1) * width + x])
                density[oy * outWidth + ox] = (abs(right - here) + abs(below - here)).toFloat()
                ox++
                x += step
            }
            oy++
            y += step
        }
        return PeakMap(outWidth, outHeight, density)
    }

    /** 密度图上超过阈值的点，用于决定"要不要显示峰值"以及叠加位置 */
    fun peaks(map: PeakMap, threshold: Int = PEAK_THRESHOLD): BooleanArray =
        BooleanArray(map.density.size) { index -> map.density[index] >= threshold }
}

/** 亮度直方图。[sampleCount] 是被采样到的像素数，不是帧的总像素数 */
data class LumaHistogram(val buckets: IntArray, val sampleCount: Int) {

    /** 归一化到 0..1（按最大桶），画柱子用 */
    fun normalized(): FloatArray {
        val peak = buckets.maxOrNull()?.takeIf { it > 0 }
            ?: return FloatArray(ExposureAnalysis.BINS)
        return FloatArray(ExposureAnalysis.BINS) { index -> buckets[index].toFloat() / peak }
    }

    /** 平均亮度（0..255）。空采样给 0，不给"中灰"——没数据不是灰屏 */
    fun meanLuma(): Float =
        if (sampleCount == 0) 0f
        else buckets.indices.sumOf { index -> index.toLong() * buckets[index] } / sampleCount.toFloat()

    override fun equals(other: Any?): Boolean =
        this === other || (other is LumaHistogram &&
            other.sampleCount == sampleCount && other.buckets.contentSameAs(buckets))

    override fun hashCode(): Int = 31 * buckets.contentHashCode() + sampleCount
}

/** 峰值密度图（降采样后的网格） */
data class PeakMap(val width: Int, val height: Int, val density: FloatArray)

private fun IntArray.contentSameAs(other: IntArray): Boolean {
    if (size != other.size) return false
    for (i in indices) if (this[i] != other[i]) return false
    return true
}

/**
 * 一次分析的结果。
 *
 * 只有统计量，没有任何"该怎么曝光"的建议：预览的色域与伽马未知（T2 约束），
 * 所以这些数字是**显示辅助**，不是测光表。
 */
data class ExposureStats(
    val histogram: LumaHistogram,
    /** 高光溢出占比 0..1（斑马纹的依据） */
    val highlightRatio: Float
) {
    /** 平均亮度 0..255；没有采样时为 0 */
    val meanLuma: Float get() = histogram.meanLuma()
}
