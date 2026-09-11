package com.imagedge.camera.lut

import android.graphics.Bitmap

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
     * 是否支持 [applyToBitmap] 直通路径（GPU 实现为 true）。
     * 调用方据此决定「要不要多做一次 Bitmap↔ByteArray 的来回拷贝」。
     */
    val supportsBitmapPath: Boolean get() = false

    /**
     * 直接处理位图（GPU 实现的快路径）。
     *
     * 为什么需要它：ByteArray 接口要求调用方先把 Bitmap 拆成 IntArray 再转成 RGBA 字节数组，
     * 处理完还要反过来拼回 Bitmap——1080p 图光这两趟 CPU 拷贝就要几十毫秒，
     * 足以吃掉 GPU 的收益。直通路径把「上传纹理 → 渲染 → 读回位图」留给实现方。
     *
     * 默认实现返回 null（不支持）——调用方回退到 [apply]。
     *
     * @param lutData 3D LUT 数据；`lutSize < 2`（或数据不足）表示**只做基础调色**
     * @return 处理后的新位图（调用方负责回收），失败或不支持时返回 null
     */
    suspend fun applyToBitmap(
        source: Bitmap,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int,
        adjust: ColorAdjust = ColorAdjust.NONE,
    ): Bitmap? = null

    /**
     * 应用 LUT + 基础调色到图像像素（推荐入口）。
     *
     * 调色（曝光/对比度/色温/饱和度）在 LUT **之前**应用：先修正曝光与白平衡，
     * 再套风格化 LUT，是修图流程的正确顺序（反过来会把 LUT 的影调映射打乱）。
     *
     * @param pixels 输入 RGBA8 像素数组
     * @param width 图像宽
     * @param height 图像高
     * @param lutData LUT 数据（3D 查找表，FloatArray）
     * @param lutSize LUT 边长（33）
     * @param strength 效果强度 0-100
     * @param adjust 基础调色参数；[ColorAdjust.NONE] 表示不调色
     * @return 处理后的 RGBA8 像素数组
     */
    suspend fun apply(
        pixels: ByteArray,
        width: Int,
        height: Int,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int,
        adjust: ColorAdjust = ColorAdjust.NONE,
    ): ByteArray

    /**
     * 只做基础调色、不套 LUT 的便捷入口（LUT 参数传空表）。
     * 实现方无需单独处理：内部等价于 `apply(..., lutSize = 0, ...)`。
     */
    suspend fun applyAdjustOnly(
        pixels: ByteArray,
        width: Int,
        height: Int,
        adjust: ColorAdjust,
    ): ByteArray = apply(pixels, width, height, EMPTY_LUT, 0, 100, adjust)

    companion object {
        /** 空 LUT：与 [CpuLutProcessor] 的退化 LUT 分支约定一致（size < 2 视为恒等） */
        val EMPTY_LUT = FloatArray(0)
    }
}
