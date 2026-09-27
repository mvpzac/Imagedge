package com.imagedge.camera.image

/**
 * 编辑步骤 —— 几何类编辑的原子单位。
 *
 * 只记录「做了什么」，不改动像素：[ImagePipeline.renderGeometry] 每次从输入重新生成，
 * 所以步骤可以增删、换一张图重放。
 *
 * **这里刻意不再有亮度/对比度/饱和度/色温四个变体。** 它们曾存在，并由
 * ImagePipeline 里一份 sRGB 空间的 ColorMatrix 实现解释；那份实现没有任何调用方，
 * 而真正的调色走 :lut（线性光、CPU 与 GPU 共用一套换算）。留着四个无人解释的数据类，
 * 比不写更容易出错——下一个接手的人会以为配方已经能承载调色。
 *
 * 也别把「配方可以序列化 / 可以做预设与批处理」当成既成事实：**目前没有配方编码器**，
 * 编辑状态仍然散在 PhotoEditViewModel 的字段里，因此也没有撤销。要让这几件事成立，
 * 需要的是让本类型真正承载调色（Color(ColorAdjust) + Lut(key, strength)），
 * 而不是再加一份颜色实现。
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

    companion object {
        /** 全图（不裁剪） */
        val FULL_CROP = NormRect.FULL
    }
}
