package com.imagedge.camera.image

import com.imagedge.camera.lut.ColorAdjust
import com.imagedge.camera.lut.RangeKey
import com.imagedge.camera.lut.SelectiveAdjust

/**
 * 编辑步骤 —— 一次编辑的原子单位（几何 / 调色 / 滤镜）。
 *
 * 只记录「做了什么」，不改动像素：[ImagePipeline.renderGeometry] 每次从输入重新生成，
 * 所以步骤可以增删、换一张图重放。
 *
 * **这里刻意不再有亮度/对比度/饱和度/色温四个变体。** 它们曾存在，并由
 * ImagePipeline 里一份 sRGB 空间的 ColorMatrix 实现解释；那份实现没有任何调用方，
 * 而真正的调色走 :lut（线性光、CPU 与 GPU 共用一套换算）。留着四个无人解释的数据类，
 * 比不写更容易出错——那时它们会让人以为配方已经能承载调色，可那四个字段谁也没有解释。
 * 如今承载调色的位置是下面的 `Color`（以及滤镜的 `Lut`），一律由 :lut 解释。
 *
 * 几何类步骤的顺序由 [ImagePipeline] 固定（拉直 → 旋转 → 翻转 → 裁剪），
 * 与「用户在裁剪界面看到的画面」保持一致。
 */
sealed interface EditStep {

    /**
     * 裁剪：归一化矩形（0..1，相对**拉直+旋转+翻转之后**的画面）。
     * 用归一化坐标而不是像素，同一份配方可套用到不同分辨率的照片。
     */
    data class Crop(val rect: NormRect) : EditStep

    /** 旋转：角度（度），正 = 顺时针；UI 上按 90° 递增 */
    data class Rotate(val degrees: Float) : EditStep

    /** 拉直：小角度校正（-45..45），旋转后自动裁掉四角空白 */
    data class Straighten(val degrees: Float) : EditStep

    /** 翻转：horizontal = true 左右镜像，false 上下镜像 */
    data class Flip(val horizontal: Boolean) : EditStep

    /**
     * 调色：一份 `:lut` 的 ColorAdjust 快照。
     *
     * 它必须交给 `:lut` 解释（CPU 与 GPU 共用 SrgbTransfer 那一套换算），
     * 本模块不实现任何像素运算——这里曾有一份 ColorMatrix 版本，正因为它在
     * sRGB **编码空间**里做算术（既有乘法也有三通道平移）而被删掉：反对的理由是色彩空间，
     * 不是「乘性」这两个字。
     */
    data class Color(val adjust: ColorAdjust) : EditStep

    /**
     * 局部调整：一个区间键 + 三个轴（曝光/对比度/饱和度）。
     *
     * 与 [Color] 是**两个身份**：全局一份、局部一份，由「同一身份至多一步」直接保证，
     * 不需要新规则，也没有开放多层叠加（那是本轮为 [Lut] 明确拒掉的事）。
     * 键控数学只有 `:lut` 的 RangeKeyWeight 一份，CPU 与 GPU 分别调它与镜像它。
     */
    data class Selective(val key: RangeKey, val adjust: SelectiveAdjust) : EditStep

    /**
     * 滤镜：资产 key + 强度。
     *
     * key 为编辑器的「原图」占位值时它仍然占位——那是**故意的**：强度是用户滑出来的值，
     * 今天即使没选滤镜也留在状态里，下次选滤镜要用同一个强度。把无滤镜折叠成「没有这一步」
     * 就会丢掉强度，行为与 alpha08 不一致。
     */
    data class Lut(val key: String, val strength: Int) : EditStep

    companion object {
        /** 全图（不裁剪） */
        val FULL_CROP = NormRect.FULL
    }
}

/**
 * 步骤身份：同身份即互相替换。
 *
 * `Lut` 刻意不带 key——两个不同滤镜只有一份能存在，这一轮不开放多层 LUT 叠加。
 * `Flip` 要带方向，因为水平与垂直今天可以并存。
 */
internal val EditStep.identity: String
    get() = when (this) {
        is EditStep.Straighten -> "straighten"
        is EditStep.Rotate -> "rotate"
        is EditStep.Flip -> "flip:$horizontal"
        is EditStep.Crop -> "crop"
        is EditStep.Color -> "color"
        is EditStep.Lut -> "lut"
        is EditStep.Selective -> "selective"
    }

/**
 * 规范顺序：几何 → 调色 → LUT。同 rank 内保持原相对次序（sortedBy 是稳定排序）。
 *
 * 公开是因为 `:app` 的预设要按它挑出「只存颜色与滤镜」的步骤；
 * internal 在 Kotlin 里是**按模块**算的，跨模块用不到。
 *
 * 几何四个类型逐条列成 0，不写 else：将来给 identity 加了新变体（那里没有 else，
 * 编译器会逼你补），却忘了在这里补 rank 的话，else 会把它悄悄归到 0、让它出现在
 * allSteps 里，再被 ImagePipeline 的逐类型 filterIsInstance 丢掉——用户做了一步却
 * 永远渲染不出来，且没有任何地方报错。这里也交给编译器兜底。
 */
val EditStep.rank: Int
    get() = when (this) {
        is EditStep.Straighten -> 0
        is EditStep.Rotate -> 0
        is EditStep.Flip -> 0
        is EditStep.Crop -> 0
        is EditStep.Color -> 1
        is EditStep.Selective -> 2
        is EditStep.Lut -> 3
    }
