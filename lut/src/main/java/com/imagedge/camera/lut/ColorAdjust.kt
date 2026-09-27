package com.imagedge.camera.lut

/**
 * 基础调色参数（在 LUT 之前应用）。
 *
 * 取值范围统一为 **-100..100 的滑条值，0 = 不变**，与 UI 滑条一一对应；
 * 换算到实际数学量在 [CpuLutProcessor] 内完成——这样 UI/状态层不需要知道
 * 「±100 等于几档曝光」这类实现细节。
 *
 * 处理顺序（与主流修图软件一致）：**增益（曝光 × 色温 × 色调）→ 高光/阴影分区 → 对比度
 * → 饱和度 → LUT → 强度混合**。除饱和度外都是逐通道运算，可折叠成 3×256 的查表。
 *
 * 三条增益在数学上是同一次乘法，谁先摆、谁先算都不影响结果；但**增益/分区/对比度这三段
 * 之间的先后是会变的**——对比度是绕 18% 灰的仿射，先缩放后旋转 ≠ 先旋转后缩放。
 * 界面滑条的顺序必须按这一段来排，否则「同样拉满，先动哪个」会给出不同的照片，
 * 而界面上没有任何地方说明这件事。
 *
 * 全部运算在**线性光**下进行（见 [SrgbTransfer]）——分区恢复尤其依赖这一点：
 * 在编码空间里算出的 (1-v) 权重不是「暗部有多少」，暗部会被系统性高估。
 */
data class ColorAdjust(
    /** 曝光补偿：±100 ≈ ±1.6 档 */
    val exposure: Int = 0,
    /** 对比度：以 18% 灰（线性）为轴，±100 ≈ ±65% */
    val contrast: Int = 0,
    /** 饱和度：-100 = 完全去色（黑白） */
    val saturation: Int = 0,
    /** 色温：正值偏暖（加红减蓝），负值偏冷。只动 R/B */
    val temperature: Int = 0,
    /** 色调：正值偏绿，负值偏品红。只动 G——荧光灯与部分 LED 下的偏色在这一轴上，色温调不回来 */
    val tint: Int = 0,
    /** 阴影：正值提亮暗部，负值压暗暗部。权重 (1-v)⁴，几乎不碰高光 */
    val shadows: Int = 0,
    /** 高光：负值压暗亮部（找回高光），正值提亮。权重 v⁴，几乎不碰暗部 */
    val highlights: Int = 0,
) {
    /** 是否恒等（全部为 0）——恒等时处理器可走零开销快路径 */
    val isIdentity: Boolean
        get() = exposure == 0 && contrast == 0 && saturation == 0 && temperature == 0 &&
            tint == 0 && shadows == 0 && highlights == 0

    companion object {
        val NONE = ColorAdjust()

        /** 滑条值归一到 -1f..1f */
        internal fun norm(v: Int): Float = v.coerceIn(-100, 100) / 100f
    }
}
