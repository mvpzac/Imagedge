package com.imagedge.camera.image

/**
 * 编辑步骤 —— 非破坏性编辑的原子单位。
 *
 * 只记录「做了什么」，不改动像素：
 * - 可序列化为编辑配方（预设 / 批处理复用）
 * - 可任意增删（撤销、回退、重编辑）
 * - 对多张照片套同一串步骤 = 批处理
 *
 * 参数取值统一为 **-1f..1f**（0 = 原始），便于 UI 直接绑定滑块；
 * 几何类步骤的顺序由 [ImagePipeline] 固定（拉直 → 旋转 → 翻转 → 裁剪），
 * 与「用户在裁剪界面看到的画面」保持一致。
 */
sealed interface EditStep {

    /** 亮度 / 曝光：-1 最暗，0 原始，1 最亮 */
    data class Brightness(val value: Float) : EditStep

    /** 对比度：-1 最低（灰），0 原始，1 最高 */
    data class Contrast(val value: Float) : EditStep

    /** 饱和度：-1 黑白，0 原始，1 最艳 */
    data class Saturation(val value: Float) : EditStep

    /** 色温 / 白平衡：-1 冷（偏蓝），0 原始，1 暖（偏黄） */
    data class Temperature(val value: Float) : EditStep

    /**
     * 裁剪：归一化矩形（0..1，相对**拉直+旋转+翻转之后**的画面）。
     * 用归一化坐标而不是像素，同一配方可套用到不同分辨率的照片。
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
