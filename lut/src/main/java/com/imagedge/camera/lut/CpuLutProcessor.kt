package com.imagedge.camera.lut

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : LUT CPU 处理器（M3 先行版：纯 Kotlin 三线性插值）。
 *              1080p 图约 1~2s；后续 VulkanLutProcessor（NDK 计算着色器）为 GPU 路径，
 *              处理器选择策略届时按图像规模/设备能力智能选择（参考 Lut2Photo）。
 *     version: 1.1 —— 增加基础调色（曝光/对比度/色温/饱和度），与 LUT 同一遍完成
 * </pre>
 */
class CpuLutProcessor : LutProcessor {

    override suspend fun apply(
        pixels: ByteArray,
        width: Int,
        height: Int,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int,
        adjust: ColorAdjust,
    ): ByteArray {
        val out = ByteArray(pixels.size)

        // 逐通道调色折叠成 3×256 查表：曝光/对比度/色温都只依赖单通道输入，
        // 每像素省下十余次浮点运算（1080p ≈ 200 万像素 × 每像素 3 通道）。
        val adjustActive = !adjust.isIdentity
        val table = if (adjustActive) buildChannelTable(adjust) else null

        // 退化 LUT（少于 2 个采样点）无法插值 —— 只做调色或原样返回。
        // 同时避免 maxIndex = 0 时 `coerceAtMost(maxIndex - 1)` 得到 -1
        // 造成 lutData 负索引越界崩溃。
        val hasLut = lutSize >= 2 && lutData.size >= lutSize * lutSize * lutSize * 3
        if (!hasLut && table == null) {
            System.arraycopy(pixels, 0, out, 0, pixels.size)
            return out
        }

        if (!hasLut) {
            applyAdjustOnlyInPlace(pixels, out, table!!, adjust)
            return out
        }

        val maxIndex = lutSize - 1
        val strengthF = strength.coerceIn(0, 100) / 100f
        val n = lutSize
        val n2 = n * n
        val saturationF = AdjustUniforms.of(adjust).saturation

        var p = 0
        while (p < pixels.size) {
            if (p and CANCELLATION_CHECK_MASK == 0) coroutineContext.ensureActive()
            // RGBA8 →（可选）逐通道调色 → 线性光 → 饱和度 → 编码回 sRGB
            var rf: Float
            var gf: Float
            var bf: Float
            if (table != null) {
                rf = table[pixels[p].toInt() and 0xFF]
                gf = table[256 + (pixels[p + 1].toInt() and 0xFF)]
                bf = table[512 + (pixels[p + 2].toInt() and 0xFF)]
            } else {
                rf = SrgbTransfer.decode(pixels[p].toInt() and 0xFF)
                gf = SrgbTransfer.decode(pixels[p + 1].toInt() and 0xFF)
                bf = SrgbTransfer.decode(pixels[p + 2].toInt() and 0xFF)
            }

            // 饱和度在**线性光**下围绕人眼亮度插值；编码域里算出来的不是亮度
            if (adjust.saturation != 0) {
                val luma = SrgbTransfer.LUMA_R * rf + SrgbTransfer.LUMA_G * gf + SrgbTransfer.LUMA_B * bf
                rf = luma + (rf - luma) * saturationF
                gf = luma + (gf - luma) * saturationF
                bf = luma + (bf - luma) * saturationF
            }
            // 回到 sRGB 编码：3D LUT 的输入域定义在这里，往下就必须一直是编码值
            val r = SrgbTransfer.encode(rf).coerceIn(0f, 1f)
            val g = SrgbTransfer.encode(gf).coerceIn(0f, 1f)
            val b = SrgbTransfer.encode(bf).coerceIn(0f, 1f)
            // 调色后的基准值（0..255）——强度混合的「0% 端」。
            // 必须用它而不是原始像素：否则 strength=0 会把刚调好的曝光/白平衡一起丢掉。
            val baseR = r * 255f
            val baseG = g * 255f
            val baseB = b * 255f

            // 三线性插值：8 个角点
            val fx = r * maxIndex
            val fy = g * maxIndex
            val fz = b * maxIndex
            val x0 = fx.toInt().coerceIn(0, maxIndex - 1)
            val y0 = fy.toInt().coerceIn(0, maxIndex - 1)
            val z0 = fz.toInt().coerceIn(0, maxIndex - 1)
            val tx = fx - x0
            val ty = fy - y0
            val tz = fz - z0

            // 8 个角点在 lutData 中的基址（每点 3 个连续 float = RGB）。
            // 原先这里在**每像素**的循环体内声明一个 Function4 lambda `idx` 并调用 24 次：
            // 1080p 意味着 200 万个临时 lambda 对象 + 约 5000 万次装箱虚调用，
            // 光 GC 抖动就让处理时间从"1~2 秒"膨胀到数十秒。改为预先算基址 + 直接数组访问。
            val i000 = (x0 + y0 * n + z0 * n2) * 3
            val i100 = (x0 + 1 + y0 * n + z0 * n2) * 3
            val i010 = (x0 + (y0 + 1) * n + z0 * n2) * 3
            val i110 = (x0 + 1 + (y0 + 1) * n + z0 * n2) * 3
            val i001 = (x0 + y0 * n + (z0 + 1) * n2) * 3
            val i101 = (x0 + 1 + y0 * n + (z0 + 1) * n2) * 3
            val i011 = (x0 + (y0 + 1) * n + (z0 + 1) * n2) * 3
            val i111 = (x0 + 1 + (y0 + 1) * n + (z0 + 1) * n2) * 3

            // 三通道（R/G/B）逐个插值，结果直接写回 out，省去中间变量
            var c = 0
            while (c < 3) {
                val c000 = lutData[i000 + c]; val c100 = lutData[i100 + c]
                val c010 = lutData[i010 + c]; val c110 = lutData[i110 + c]
                val c001 = lutData[i001 + c]; val c101 = lutData[i101 + c]
                val c011 = lutData[i011 + c]; val c111 = lutData[i111 + c]

                val c00 = c000 + (c100 - c000) * tx
                val c10 = c010 + (c110 - c010) * tx
                val c01 = c001 + (c101 - c001) * tx
                val c11 = c011 + (c111 - c011) * tx
                val c0 = c00 + (c10 - c00) * ty
                val c1 = c01 + (c11 - c01) * ty
                val v = c0 + (c1 - c0) * tz

                // 强度混合：strength=0 输出「已调色但未套 LUT」，strength=100 输出完整 LUT 结果
                val base = when (c) {
                    0 -> baseR
                    1 -> baseG
                    else -> baseB
                }
                out[p + c] = mixFloat(base, v * 255f, strengthF)
                c++
            }
            out[p + 3] = pixels[p + 3]
            p += 4
        }
        return out
    }

    /** 只做基础调色（无 LUT）的快路径：逐通道查表 + 线性光饱和度 */
    private suspend fun applyAdjustOnlyInPlace(
        pixels: ByteArray,
        out: ByteArray,
        table: FloatArray,
        adjust: ColorAdjust,
    ) {
        val saturationF = AdjustUniforms.of(adjust).saturation
        var p = 0
        while (p < pixels.size) {
            if (p and CANCELLATION_CHECK_MASK == 0) coroutineContext.ensureActive()
            var rf = table[pixels[p].toInt() and 0xFF]
            var gf = table[256 + (pixels[p + 1].toInt() and 0xFF)]
            var bf = table[512 + (pixels[p + 2].toInt() and 0xFF)]
            if (adjust.saturation != 0) {
                val luma = SrgbTransfer.LUMA_R * rf + SrgbTransfer.LUMA_G * gf + SrgbTransfer.LUMA_B * bf
                rf = luma + (rf - luma) * saturationF
                gf = luma + (gf - luma) * saturationF
                bf = luma + (bf - luma) * saturationF
            }
            out[p] = quantize(SrgbTransfer.encode(rf), dither = true)
            out[p + 1] = quantize(SrgbTransfer.encode(gf), dither = true)
            out[p + 2] = quantize(SrgbTransfer.encode(bf), dither = true)
            out[p + 3] = pixels[p + 3]
            p += 4
        }
    }

    /**
     * 逐通道 3×256 查表：解码到线性光 → 曝光（增益）→ 对比度（绕 18% 灰）→ 色温（RGB 增益）。
     *
     * 表里存的是**线性值**而不是 8 位整数：三步调色都是光量运算，在 gamma 编码值上做
     * 等于把暗部错杀（详见 [SrgbTransfer]）。存浮点也顺带消除了原先「查表阶段就量化到
     * 8 位、而 GPU 全程 float」导致的 CPU/GPU 偏差。
     * 饱和度是跨通道运算，不在这张表里，在每像素循环里于线性光下完成。
     */
    private fun buildChannelTable(adjust: ColorAdjust): FloatArray {
        val u = AdjustUniforms.of(adjust)
        val contrastF = u.contrast
        val pivot = SrgbTransfer.CONTRAST_PIVOT
        val table = FloatArray(768)
        for (channel in 0..2) {
            val gain = when (channel) {
                0 -> u.gainR
                1 -> u.gainG
                else -> u.gainB
            }
            val base = channel * 256
            for (v in 0..255) {
                val linear = SrgbTransfer.decode(v) * gain
                table[base + v] = (linear - pivot) * contrastF + pivot
            }
        }
        return table
    }

    private fun mixFloat(base: Float, transformed: Float, strength: Float): Byte {
        val v = base + (transformed - base) * strength
        return quantize(v / 255f, dither = strength > 0)
    }

    /**
     * 浮点 → 8 位。
     *
     * 3D LUT 的产物是平滑渐变，直接四舍五入到 8 位必然出色带：
     * 一整片天空会塌成同一个码值。[dither] 为真时加三角 PDF 抖动
     * （两个均匀随机数之差），把量化误差拆到相邻两个码值，台阶消失而均值不动。
     *
     * 抖动只在**真正产生了渐变**的路径上加：恒等与 strength=0 的直通必须逐位还原，
     * 两端（0 / 255）也不抖——满量程的像素就该是 0 或 255，抖下去反而是错的。
     */
    private fun quantize(unit: Float, dither: Boolean): Byte {
        val scaled = unit.coerceIn(0f, 1f) * 255f
        if (!dither || scaled <= 0f || scaled >= 255f) {
            return kotlin.math.round(scaled).toInt().coerceIn(0, 255).toByte()
        }
        val dithered = scaled + (nextNoise() - nextNoise())
        return kotlin.math.round(dithered).toInt().coerceIn(0, 255).toByte()
    }

    /** xorshift32：够快且分布均匀；抖动不需要密码学强度 */
    private var rngState: Int = 0x9E3779B9.toInt()

    private fun nextNoise(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return (x ushr 8) / 16777216f
    }

    private companion object {
        /** Check every 4096 pixels (RGBA = 16 KiB) without adding a modulo to the hot path. */
        const val CANCELLATION_CHECK_MASK = 0x3FFF
    }
}
