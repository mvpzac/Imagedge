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
        selective: SelectiveSpec?,
        maskMode: Boolean,
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
        // 局部调整与掩码模式也要走下面的循环，所以它们在场时这两条早退都得让路：
        // 否则「只开局部、不动全局、不套滤镜」会整块拷贝返回，局部调整静默消失
        if (!hasLut && table == null && selective == null && !maskMode) {
            System.arraycopy(pixels, 0, out, 0, pixels.size)
            return out
        }

        if (!hasLut && selective == null && !maskMode) {
            applyAdjustOnlyInPlace(pixels, out, table!!, adjust)
            return out
        }

        val maxIndex = lutSize - 1
        val strengthF = strength.coerceIn(0, 100) / 100f
        val n = lutSize
        val n2 = n * n
        val saturationF = AdjustUniforms.of(adjust).saturation
        // 局部键控与局部调整的中间值：循环外分配一次，逐像素新建 FloatArray 在 1080p 上
        // 是每帧两百万次分配
        val scratch = FloatArray(3)

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
            // 键控量在这个像素上测（pre-mix：全局调色之后、局部混合之前）——用户看到的画面就是
            // 他键的东西，且与 LUT 顺序无关。掩码模式读的也是这个 pre-mix 权重。
            val w = if (selective != null) RangeKeyWeight.weight(selective.key, rf, gf, bf, scratch) else 0f
            if (maskMode) {
                // 掩码预览：输出权重灰度，**不过 dither**（抖动是给渐变消色带的，
                // 而这里要的是权重本身的可读值）
                val g8 = quantize(w, dither = false)
                out[p] = g8
                out[p + 1] = g8
                out[p + 2] = g8
                out[p + 3] = pixels[p + 3]
                p += 4
                continue
            }
            if (w > 0f) {
                val gain = RangeKeyWeight.selectiveGain(selective!!.adjust.exposure)
                val cf = RangeKeyWeight.selectiveContrastF(selective.adjust.contrast)
                val sf = RangeKeyWeight.selectiveSatF(selective.adjust.saturation)
                scratch[0] = RangeKeyWeight.gainContrastLinear(rf, gain, cf)
                scratch[1] = RangeKeyWeight.gainContrastLinear(gf, gain, cf)
                scratch[2] = RangeKeyWeight.gainContrastLinear(bf, gain, cf)
                RangeKeyWeight.saturateLinear(scratch, sf)
                rf += (scratch[0] - rf) * w
                gf += (scratch[1] - gf) * w
                bf += (scratch[2] - bf) * w
            }
            if (!hasLut && table == null && w <= 0f) {
                // 没有 LUT、没有全局调色、这一像素又不在局部区间里 → 原样透传。
                // 不这么短路的话，8 位像素会白走一趟 sRGB 解码-编码 + 抖动，
                // 「未命中区与不施加时逐位相同」就不成立了（计划 §6.3 的底线）
                System.arraycopy(pixels, p, out, p, 4)
                p += 4
                continue
            }
                // 回到 sRGB 编码：3D LUT 的输入域定义在这里，往下就必须一直是编码值
            if (hasLut) {
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
            } else {
                // 没有 LUT：编码回去再量化。**抖动策略与 applyAdjustOnlyInPlace 一致（抖）**——
                // 那条快路径在有 selective 时走不到，这里是 no-LUT + selective 唯一的出口，
                // 两边不一致就会让「同一张图开不开局部」在暗部差几个级
                out[p] = quantize(SrgbTransfer.encode(rf), dither = true)
                out[p + 1] = quantize(SrgbTransfer.encode(gf), dither = true)
                out[p + 2] = quantize(SrgbTransfer.encode(bf), dither = true)
                out[p + 3] = pixels[p + 3]
            }
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
        val table = FloatArray(768)
        for (channel in 0..2) {
            val gain = when (channel) {
                0 -> u.gainR
                1 -> u.gainG
                else -> u.gainB
            }
            val base = channel * 256
            for (v in 0..255) {
                val gained = SrgbTransfer.decode(v) * gain
                val recovered = SrgbTransfer.recoverTone(gained, u.shadows, u.highlights)
                // 下限钳到 0，与着色器的 max(..., vec3(0.0)) 同一位置：加对比度后深暗部会算出
                // 负光量，两边都只能在输出前钳，区别在**钳之前还是之后**算亮度。留到后面算，
                // 同一张图在有没有 GPU 的机器上会掉进不同的灰——阴影最多差到 4 个级。
                // 公式本体在 RangeKeyWeight.gainContrastLinear：局部调整要跑同一条对比度曲线，
                // 这里抄一份就等于给 CPU 内部留两处可能漂移的算术。
                table[base + v] = RangeKeyWeight.gainContrastLinear(recovered, 1f, contrastF)
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
