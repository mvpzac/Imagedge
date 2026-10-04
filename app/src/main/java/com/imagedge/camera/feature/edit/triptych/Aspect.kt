package com.imagedge.camera.feature.edit.triptych

import kotlin.math.roundToInt

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 三拼的画布档位——比例与画质两个正交维度，及其到像素的推导
 * </pre>
 */

/**
 * 导出画质档位：短边上限 + 码率。数值**借用** ClearCut `model/ExportConfig.kt` 的表，非本项目推导。
 *
 * 两个维度的接线状态**不一样**，别把它们混成一句「已接线」：
 * - [shortSideCap] 已生效（Task 4）：经 [cellSize] 决定三段视频的转码目标尺寸与
 *   拼图画布尺寸，切档会改产物分辨率。
 * - [bitrate] **至今没有任何读取方**：全仓 `bitrate` 只命中下面这行声明和注释，
 *   没有一处代码读它。`MotionPhotoComposer.trimVideo` 的签名里没有 `bitrate` 参数
 *   （Task 7 才加），编码器走 Media3 自己的默认值。所以切到 P720 **只降分辨率、
 *   不降码率**，别把 `bitrate` 当成已经生效的设置读；`estimateTriptychBytes` 也正因为
 *   如此按像素推导、不按它推导。
 */
enum class Quality(val shortSideCap: Int, val bitrate: Int) {
    P720(720, 6_000_000),
    P1080(1080, 12_000_000);

    val label: String get() = "${shortSideCap}p"
}

/**
 * 每格的转码归一尺寸，也就是**成品里一格的像素**。
 *
 * 由 [cellSize] 推导。生产读取方是 `LiveTriptychViewModel` 里的四处：
 * `export()` 把 `width`/`height` 交给 `trimVideo` 的 `targetW`/`targetH`、
 * `buildTriptychBitmap` 用它定画布、[triptychCanvasSize] 与 `estimateTriptychBytes`
 * 从它推导。**四处必须同一个值**——分家的那一侧就是「屏幕上那格」与「相册里那格」
 * 是两个尺寸（Task 4 之前正是这个状态：导出直接抄 `refW`/`refH`）。
 */
data class CellSize(val width: Int, val height: Int)

/**
 * 三拼的画布档位（**每一格**的形状，不是成品）。
 *
 * 形状与尺寸正交：[ratio] 管裁切窗口（`cropToAspect`/`cropFractions` 都吃它），
 * [refW]/[refH] 是 **1080p 档下的参考尺寸**，[cellSize] 拿它与 [Quality] 一起
 * 推导出目标转码尺寸。
 * 原实现把 `1920, 1080` 直接当成输出尺寸，于是「换一档画质」只能改形状定义——
 * 两者焊在一起就再也插不进中间档。参考尺寸与输出尺寸分开后，
 * `refW/refH` 是**这个档位长什么样**，输出尺寸才是画质决定的。
 *
 * [cellSize] 已经接到导出与拼图上（Task 4）：换 [Quality] 会同时改掉三段视频的转码
 * 目标尺寸与拼图画布尺寸。[refW]/[refH] 因此**只是参考尺寸**，导出侧读的是 `cellSize`
 * 的结果而不是这两个数——`refW`/`refH` 如今在 `Aspect` 之外没有读取方（只有
 * [label] 与 [cellSize] 自己用）。
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
     *
     * 比例取自 `refW`/`refH`（即**参考尺寸**），不是 [ratio] 字段——两者今天相等，
     * 但若哪天有人只改了 `ratio`，标签会跟参考尺寸走。用 [cells] 参数而非写死 3，
     * 是因为「成品 = 每格竖堆 N 格」才是 `buildTriptychBitmap` 的实际做法。
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
 * 参考尺寸 × 画质 → 目标转码尺寸。
 *
 * 规则只有一句：**等比缩到短边不超过档位上限，且只缩不放**——
 * `scale = min(1f, cap / refShort)`。上限是天花板，不是目标值：27:16 的参考短边
 * 是 640，本来就低于 720p 的上限，硬拉到 720 会把它**放大**，而放大换不出画质，
 * 只会让文件变大。所以 27:16 在 720p 下仍然是 1080×640。
 *
 * 缩放结果两条边各自经 [evenFloor] 向下取整到偶数。
 *
 * **这是给将来新增档位准备的无条件护栏，不是对现有任何一档的修复。**
 * 按上面这条规则，现有 4 个比例 × 2 个画质共 8 个组合在取偶之前就**已经全是偶数**
 * （1920×1080 / 1080×1080 / 1080×1350 / 1080×640，以及 1280×720 / 720×720 /
 * 720×900 / 1080×640），`evenFloor` 一次都没有生效。它值得留着，是因为
 * H.264 的 yuv420 硬性要求偶数宽高，而 `refW`/`refH` 是**手写常量**：将来加一档、
 * 或者把某个参考尺寸改成奇数，算出来的值就会是奇数，Media3 Transformer 会在编码
 * 那一步失败或静默拒绝，而报错离病因很远。
 * 护栏本身由 `evenFloor` 的直接测试钉住（`LiveTriptychAspectLabelTest`），
 * 「8 个现有输出都是偶数」是另一回事、由另一条测试钉。
 *
 * 生产读取方有四处（Task 4 已接线），名单在 [CellSize] 的 KDoc 里：导出的转码目标尺寸、
 * 拼图画布、[triptychCanvasSize]、体积预估。**四处都从这一句推导**，别在调用方再写一份
 * 「短边上限怎么缩」——那一份额就是下一个 720p 分家事故。
 */
fun Aspect.cellSize(quality: Quality): CellSize {
    val refShort = minOf(refW, refH)
    val scale = minOf(1f, quality.shortSideCap.toFloat() / refShort)
    return CellSize(
        evenFloor((refW * scale).roundToInt()),
        evenFloor((refH * scale).roundToInt()),
    )
}

/**
 * 向下取整到偶数。这是 H.264 yuv420 的硬性要求，不是风格选择。
 *
 * 定义域是**非负数**（`cellSize` 的输入恒为正：正整数参考尺寸 × 正比例系数）。
 * 负数不在契约内，也不需要防：`v % 2` 在 Kotlin 里对负数取负余，本函数不做钳制。
 *
 * 没有下限保护：`evenFloor(1) == 0`。当前枚举表最小边是 640，够不着；
 * 若将来加入极小参考尺寸，`cellSize` 会返回 0 边（Media3 直接拒绝）。
 * 这是已知的待办，不在这里悄悄加 `coerceAtLeast(2)` 改变行为。
 *
 * `internal` 是为了让测试能直接喂奇数进去——8 个现有组合全是偶数，
 * 经 [cellSize] 这条路**根本触发不了这个函数**。
 */
internal fun evenFloor(v: Int): Int = v - (v % 2)