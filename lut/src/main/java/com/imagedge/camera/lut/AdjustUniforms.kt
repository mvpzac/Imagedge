package com.imagedge.camera.lut

/**
 * 调色参数 → 计算量的换算（CPU 查表与 GPU uniform **共用同一份推导**）。
 *
 * 抽出来的意义：GPU 着色器里的曝光/对比度/色温/饱和度必须与 CPU 实现逐项一致，
 * 否则用户在同一台设备上切换「省电/GPU 回退」时会看到两个不同的结果。
 * 这里只做纯数学，两个实现都从这里取值，杜绝了两边公式漂移。
 *
 * 处理顺序（与 [LutProcessor.apply] 的约定一致）：
 * 逐通道增益（曝光 × 色温）→ 对比度（绕 0.5 中灰）→ 饱和度 → LUT → 强度混合。
 */
data class AdjustUniforms(
    /** 逐通道增益（曝光 × 色温） */
    val gainR: Float,
    val gainG: Float,
    val gainB: Float,
    /** 对比度系数：1 = 不变，0 = 全灰 */
    val contrast: Float,
    /** 饱和度系数：1 = 不变，0 = 全灰（去色），>1 更艳 */
    val saturation: Float,
) {
    companion object {
        /** 曝光 ±100 ≈ ±1.6 档 */
        private const val EXPOSURE_STOPS = 1.6f

        /** 色温对红/蓝通道的增益幅度 */
        private const val TEMPERATURE_AMOUNT = 0.12f

        /** 对比度 ±100 ≈ ±65% */
        private const val CONTRAST_AMOUNT = 0.65f

        /** 正向饱和度上限约 2.2 倍（浓艳但不溢出） */
        private const val SATURATION_BOOST = 1.2f

        val IDENTITY = AdjustUniforms(1f, 1f, 1f, 1f, 1f)

        fun of(adjust: ColorAdjust): AdjustUniforms {
            val exposureGain = Math.pow(2.0, (ColorAdjust.norm(adjust.exposure) * EXPOSURE_STOPS).toDouble()).toFloat()
            val tempF = ColorAdjust.norm(adjust.temperature)
            val s = ColorAdjust.norm(adjust.saturation)
            return AdjustUniforms(
                gainR = exposureGain * (1f + tempF * TEMPERATURE_AMOUNT),
                gainG = exposureGain,
                gainB = exposureGain * (1f - tempF * TEMPERATURE_AMOUNT),
                contrast = 1f + ColorAdjust.norm(adjust.contrast) * CONTRAST_AMOUNT,
                // -100 必须真的等于全灰（系数 0），正向最高约 2.2 倍
                saturation = if (s >= 0f) 1f + s * SATURATION_BOOST else 1f + s,
            )
        }
    }
}
