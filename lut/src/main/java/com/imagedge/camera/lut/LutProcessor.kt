package com.imagedge.camera.lut

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : LUT 处理抽象（参考 Lut2Photo：Vulkan GPU + 纯 Kotlin CPU 回退 + 智能选择）
 *     version: 1.0
 * </pre>
 */

/**
 * LUT 处理器接口
 * M3 落地：VulkanLutProcessor（NDK 计算着色器）+ CpuLutProcessor（纯 Kotlin 三线性插值）
 */
interface LutProcessor {

    /**
     * 应用 LUT 到图像像素
     * @param pixels 输入 RGBA8 像素数组
     * @param width 图像宽
     * @param height 图像高
     * @param lutData LUT 数据（3D 查找表，FloatArray）
     * @param lutSize LUT 边长（33）
     * @param strength 效果强度 0-100
     * @return 处理后的 RGBA8 像素数组
     */
    suspend fun apply(
        pixels: ByteArray,
        width: Int,
        height: Int,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int
    ): ByteArray
}
