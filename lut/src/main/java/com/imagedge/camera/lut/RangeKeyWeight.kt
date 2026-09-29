package com.imagedge.camera.lut

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 键控轴。序号即预设线格式里的整数：改顺序等于改格式版本 */
enum class KeyAxis { LUMA, HUE, SATURATION }

/**
 * 一个区间键：梯形权重。vkdt（BSD-2）`src/pipe/modules/mask` 的同形参数，
 * 但把四个拐点收敛成三个用户量：平台两边 [from]/[to] 与两侧缓变宽度 [feather]
 * （x0 = from - feather，x3 = to + feather）。
 *
 * [inverted] 把平台内外互换。权重只取 0/1 之间的连续值，不做部分权重的第二种语义。
 */
data class RangeKey(
    val axis: KeyAxis,
    val from: Float,
    val to: Float,
    val feather: Float,
    val inverted: Boolean = false,
) {
    val x0: Float get() = from - feather
    val x3: Float get() = to + feather

    companion object {
        /**
         * GLSL 的 `smoothstep(e0, e1, x)` 在 `e0 == e1` 时是除零、行为未定义。
         * 与其在 CPU 与 GPU 两侧各写一个守卫去对齐未定义行为，不如让退化区间构造不出来。
         */
        const val MIN_FEATHER = 0.01f

        fun of(
            axis: KeyAxis,
            from: Float,
            to: Float,
            feather: Float,
            inverted: Boolean = false,
        ): RangeKey {
            require(from.isFinite() && to.isFinite() && feather.isFinite()) { "区间参数必须有限" }
            require(from <= to) { "区间必须 from <= to，实际 $from..$to" }
            require(feather >= MIN_FEATHER) { "羽化宽度必须 >= $MIN_FEATHER，实际 $feather" }
            require(from >= 0f) { "下拐点不能为负，实际 $from" }
            val upper = to + feather
            val cap = if (axis == KeyAxis.HUE) 2f else 1f
            require(upper <= cap) { "上拐点（to + feather = $upper）超出 $cap" }
            return RangeKey(axis, from, to, feather, inverted)
        }
    }
}

/** 局部调整只暴露三个轴：曝光 / 对比度 / 饱和度 */
data class SelectiveAdjust(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
) {
    val isIdentity: Boolean get() = exposure == 0 && contrast == 0 && saturation == 0
}

/** 交给处理器的一对：键 + 局部调整。成对出现，避免两个可空参数各走各的 */
data class SelectiveSpec(val key: RangeKey, val adjust: SelectiveAdjust)

/**
 * 键控数学的**唯一 Kotlin 定义**。CpuLutProcessor 调它；GpuLutProcessor 的 GLSL 是它的手写镜像，
 * 同值由仪器化测试钉住（容差 1 LSB，吸收 GLSL `log2`/fma 的末位差异）。
 *
 * 所有输入是**线性光** RGB。键控里刻意不做第二次传递函数：每多一次 EOTF/OETF，
 * CPU 与 GPU 就多一处可能分家的面。
 */
object RangeKeyWeight {

    const val LUMA_LOG_FLOOR = -12f
    const val LUMA_LOG_SPAN = 20f

    // Rec.709 线性亮度权重用 [SrgbTransfer] 那三个常量，不在这里另写一份：
    // 对比度支点与饱和度已经在用它们，键控再抄一份就是第三个口径。

    /** 标准三次 smoothstep；e1 > e0 由 [RangeKey.MIN_FEATHER] 保证 */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun trapezoid(x: Float, x0: Float, x1: Float, x2: Float, x3: Float): Float =
        smoothstep(x0, x1, x) * (1f - smoothstep(x2, x3, x))

    /** max == min 时 h 取 0（与 vkdt / 通行实现一致）；s 在 max == 0 时取 0 */
    fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray =
        FloatArray(3).also { rgbToHsvInto(it, r, g, b) }

    fun rgbToHsvInto(out: FloatArray, r: Float, g: Float, b: Float) {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d <= 0f -> 0f
            mx == r -> (((g - b) / d) % 6f + 6f) % 6f / 6f
            mx == g -> ((b - r) / d + 2f) / 6f
            else -> ((r - g) / d + 4f) / 6f
        }
        val s = if (mx <= 0f) 0f else d / mx
        out[0] = h; out[1] = s; out[2] = mx
    }

    /**
     * 键控量 x。[scratch] 是调用方的 3-float 缓冲：HUE/SAT 要过 rgbToHsv，
     * 逐像素新建 FloatArray 在 1080p 上是每帧两百万次分配。测试可以不传（走默认值），
     * CPU 循环必须传自己的。
     */
    fun keyQuantity(
        axis: KeyAxis,
        r: Float,
        g: Float,
        b: Float,
        scratch: FloatArray = FloatArray(3),
    ): Float = when (axis) {
        KeyAxis.LUMA -> {
            val l = SrgbTransfer.LUMA_R * r + SrgbTransfer.LUMA_G * g + SrgbTransfer.LUMA_B * b
            // 地板在 Kotlin 侧其实**看不出来**（log2(0) = -Infinity，coerceIn 同样压到 0），
            // 它是为 GLSL 镜像准备的：着色器里 log2(0.0) 未定义、max(NaN, x) 逐驱动不同。
            // 钉住它的是仪器化同值测试（输入含全黑行），不是 JVM 用例。
            ((log2(max(l, 2f.pow(LUMA_LOG_FLOOR))) - LUMA_LOG_FLOOR) / LUMA_LOG_SPAN).coerceIn(0f, 1f)
        }
        KeyAxis.HUE -> { rgbToHsvInto(scratch, r, g, b); scratch[0] }
        KeyAxis.SATURATION -> { rgbToHsvInto(scratch, r, g, b); scratch[1] }
    }

    /**
     * 区间权重 w ∈ [0,1]。
     *
     * 色相模式无条件多评一次 `x + 1` 的梯形再取 max：区间允许 to > 1（跨红端接缝）时靠它补上环的另一半；
     * 亮度/饱和度模式因上拐点锁在 1 内，第二个梯形支撑集落在 x < 0、恒为 0。
     * **两种模式都无条件求值**（分支只依赖 uniform 的 axis，不依赖像素），让 CPU 与 GPU 执行结构相同。
     */
    fun weight(
        key: RangeKey,
        r: Float,
        g: Float,
        b: Float,
        scratch: FloatArray = FloatArray(3),
    ): Float {
        val x = keyQuantity(key.axis, r, g, b, scratch)
        var v = trapezoid(x, key.x0, key.from, key.to, key.x3)
        if (key.axis == KeyAxis.HUE) {
            v = max(v, trapezoid(x + 1f, key.x0, key.from, key.to, key.x3))
            // 纯灰没有色相：不属于任何色相区间（否则「只推红色」会点亮灰天）
            if (max(r, max(g, b)) == min(r, min(g, b))) v = 0f
        }
        return if (key.inverted) 1f - v else v
    }

    // ── 局部三轴的线性光数学：与 buildChannelTable 的逐通道操作同式，公式只留这一份 ──

    fun selectiveGain(exposure: Int): Float = 2f.pow(exposure.coerceIn(-100, 100) * (1.6f / 100f))
    fun selectiveContrastF(contrast: Int): Float = 1f + contrast.coerceIn(-100, 100) * (0.65f / 100f)
    fun selectiveSatF(saturation: Int): Float = 1f + saturation.coerceIn(-100, 100) * (1.5f / 100f)

    fun gainContrastLinear(lin: Float, gain: Float, contrastF: Float): Float =
        ((lin * gain - SrgbTransfer.CONTRAST_PIVOT) * contrastF + SrgbTransfer.CONTRAST_PIVOT)
            .coerceAtLeast(0f)

    /** 饱和度在**线性光**上做，与全局路径同侧；v 是调用方的 3-float 缓冲，就地改 */
    fun saturateLinear(v: FloatArray, satF: Float) {
        val l = SrgbTransfer.LUMA_R * v[0] + SrgbTransfer.LUMA_G * v[1] + SrgbTransfer.LUMA_B * v[2]
        v[0] = (l + (v[0] - l) * satF).coerceAtLeast(0f)
        v[1] = (l + (v[1] - l) * satF).coerceAtLeast(0f)
        v[2] = (l + (v[2] - l) * satF).coerceAtLeast(0f)
    }
}
