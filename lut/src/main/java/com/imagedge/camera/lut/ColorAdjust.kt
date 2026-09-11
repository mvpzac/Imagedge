package com.imagedge.camera.lut

/**
 * 基础调色参数（在 LUT 之前应用）。
 *
 * 取值范围统一为 **-100..100 的滑条值，0 = 不变**，与 UI 滑条一一对应；
 * 换算到实际数学量在 [CpuLutProcessor] 内完成——这样 UI/状态层不需要知道
 * 「±100 等于几档曝光」这类实现细节。
 *
 * 处理顺序（与主流修图软件一致）：曝光 → 对比度 → 色温 → 饱和度 → LUT → 强度混合。
 * 前三项都是**逐通道**运算，可在处理器里折叠成 3×256 的查表；
 * 饱和度需要跨通道，单独一步；LUT 是三维查表，最后按强度与原图混合。
 */
data class ColorAdjust(
    /** 曝光补偿：±100 ≈ ±1.6 档 */
    val exposure: Int = 0,
    /** 对比度：以中灰为轴，±100 ≈ ±65% */
    val contrast: Int = 0,
    /** 饱和度：-100 = 完全去色（黑白） */
    val saturation: Int = 0,
    /** 色温：正值偏暖（加红减蓝），负值偏冷 */
    val temperature: Int = 0,
) {
    /** 是否恒等（全部为 0）——恒等时处理器可走零开销快路径 */
    val isIdentity: Boolean
        get() = exposure == 0 && contrast == 0 && saturation == 0 && temperature == 0

    companion object {
        val NONE = ColorAdjust()

        /** 滑条值归一到 -1f..1f */
        internal fun norm(v: Int): Float = v.coerceIn(-100, 100) / 100f
    }
}
