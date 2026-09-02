package com.imagedge.camera.image

import android.graphics.RectF

/**
 * 编辑步骤 —— 非破坏性编辑的原子单位。
 *
 * 只记录「做了什么」，不改动像素：
 * - 可序列化为编辑配方（预设 / 批处理复用）
 * - 可任意增删（撤销、回退、重编辑）
 * - 对多张照片套同一串步骤 = 批处理
 *
 * 参数取值统一为 **-1f..1f**（0 = 原始），便于 UI 直接绑定滑块。
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
     * 裁剪：归一化矩形（0..1 相对原图），避免与具体像素绑定。
     * 这样同一配方可以套用到不同分辨率的照片。
     */
    data class Crop(val rect: RectF) : EditStep

    /** 旋转：角度（度），通常为 ±90 / 180 */
    data class Rotate(val degrees: Float) : EditStep

    companion object {
        /** 全图（不裁剪） */
        val FULL_CROP = RectF(0f, 0f, 1f, 1f)
    }
}
