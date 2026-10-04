package com.imagedge.camera.feature.edit.triptych

import kotlin.math.roundToInt

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 三拼的画布档位——比例与画质两个正交维度，及其到像素的推导
 * </pre>
 */

/** 导出画质。数值**借用** ClearCut `model/ExportConfig.kt` 的表，非本项目推导 */
enum class Quality(val shortSideCap: Int, val bitrate: Int) {
    P720(720, 6_000_000),
    P1080(1080, 12_000_000);

    val label: String get() = "${shortSideCap}p"
}

/** 每格的转码归一尺寸 */
data class CellSize(val width: Int, val height: Int)

/**
 * 三拼的画布档位（**每一格**的形状，不是成品）。
 *
 * 形状与尺寸正交：[ratio] 管裁切窗口，[refW]/[refH] 是 **1080p 档下的参考尺寸**，
 * [cellSize] 拿它与 [Quality] 相乘得出实际转码尺寸。
 * 原实现把 `1920, 1080` 直接当成输出尺寸，于是「换一档画质」只能改形状定义——
 * 两者焊在一起就再也插不进中间档。参考尺寸与输出尺寸分开后，
 * `refW/refH` 是**这个档位长什么样**，输出尺寸才是画质决定的。
 */
enum class Aspect(val ratio: Float, val refW: Int, val refH: Int) {
    R16_9(16f / 9f, 1920, 1080),
    R1_1(1f, 1080, 1080),
    R4_5(4f / 5f, 1080, 1350),

    /** 三格堆出 1080×1920——唯一一张不用二次裁切就能直接发 Story/Reels 的档位 */
    R27_16(27f / 16f, 1080, 640);

    /**
     * 档位标签：同时给出**每格**与**成品**的比例。
     *
     * 只标每格比例会误导：看到「16:9」的用户会以为那是竖屏故事档，
     * 而它三格堆叠后其实是 16:27；反过来看到「27:16」也没人想得到那是 9:16 成品。
     * 两个都写，用户不用心算。
     */
    fun label(cells: Int): String {
        fun reduce(w: Int, h: Int): String {
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val g = gcd(w, h).coerceAtLeast(1)
            return "${w / g}:${h / g}"
        }
        return "每格 ${reduce(refW, refH)} → 整体 ${reduce(refW, refH * cells)}"
    }
}

/**
 * 参考尺寸 × 画质 → 实际转码尺寸。
 *
 * 规则只有一句：**把参考尺寸等比缩到短边等于档位上限，且只缩不放。**
 * 「只缩不放」不是保守——27:16 的参考短边是 640，本来就低于 720p 的上限，
 * 拉到 720 会把它**放大**，而放大换不出画质，只会让文件变大。
 *
 * 缩放结果**两条边各自向下取整到偶数**。这一步不可省：
 * 720p 下 27:16 若按比例硬算是 `720 × 27/16 = 1215`（奇数），
 * 而 H.264 的 yuv420 要求偶数宽高，Media3 Transformer 会在那一步失败或静默拒绝。
 * 原实现写死 1080×640（偶数）所以从未暴露，一引入画质档位就会撞上。
 */
fun Aspect.cellSize(quality: Quality): CellSize {
    val refShort = minOf(refW, refH)
    val scale = minOf(1f, quality.shortSideCap.toFloat() / refShort)
    return CellSize(
        evenFloor((refW * scale).roundToInt()),
        evenFloor((refH * scale).roundToInt()),
    )
}

/** 向下取整到偶数。这是 H.264 yuv420 的硬性要求，不是风格选择 */
private fun evenFloor(v: Int): Int = v - (v % 2)