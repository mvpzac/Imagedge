package com.imagedge.camera.image

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 一次镜头校正的参数。
 *
 * @param k1 Brown–Conrady 三项径向畸变的一阶系数（lensfun 的 `poly3`）。
 *   **k1 > 0 = 桶形**（广角把直线掰成鼓，存下来的图边缘被压紧，校正要往更外面取样）；
 *   k1 < 0 = 枕形。本项目**不**带逐镜头的数据库，所以这两个数由用户给，
 *   不是实测值——见 [LensCorrection] 顶上的说明。
 * @param tca 横向色差：R 与 B 相对绿通道的径向缩放强度。它是**逐通道**的，
 *   绿通道是基准不动，R 与 B 往相反方向走。
 */
data class LensCorrectionParams(
    val k1: Float = 0f,
    val tca: Float = 0f,
) {
    /** 两个系数都是 0 时整条校正链是一次恒等映射，不该为它付一次全图重采样 */
    val isIdentity: Boolean get() = k1 == 0f && tca == 0f

    /** 把参数收敛到条带采样能承受的范围内。见 [K1_LIMIT] 为什么是这个数 */
    fun coerceIntoRange(): LensCorrectionParams = LensCorrectionParams(
        // coerceIn 的两次比较对 NaN 都是 false，NaN 会原样穿过去；而下游的 isFinite 守卫
        // 会把非有限数当成「这一格不校正」，于是 isIdentity 为假的一趟全图重采样
        // 跑完了、结果与原图逐像素相同——用户只看到一次慢了几分钟的导出。
        if (k1.isFinite()) k1.coerceIn(-K1_LIMIT, K1_LIMIT) else 0f,
        if (tca.isFinite()) tca.coerceIn(0f, TCA_LIMIT) else 0f,
    )

    companion object {
        /**
         * k1 的**上限**，0.02。
         *
         * 依据是**滑条分辨率**与真实镜头的量级，不是内存：真实定焦镜头的 k1 多在
         * 0.001..0.03，0.02 已经覆盖绝大多数，而 0.001 的步长用 ±0.02 只需要 41 格
         * 就能覆盖，用 ±0.1 则要 201 格——同样一格 0.001，前者把滑条分得更细。
         *
         * 内存**不是**这个上限的理由：条带缓冲按精确上界算（见 [LensCorrection.bandSlack]），
         * 21.3 MP 上 k1 = 0.02 约 7 MB，k1 = 0.1 也只有约 12 MB。
         *
         * 这个上限**由 [coerceIntoRange] 兜住**，而不只是滑条的两端——`LensCorrectionParams`
         * 是公开可构造的 data class，一次直接构造就能把它顶回去，而那条路径不经过滑条。
         */
        const val K1_LIMIT = 0.02f

        /** k1 滑条的刻度格数：0..40 ↔ -0.02..+0.02（步长 0.001） */
        const val K1_STEPS = 40

        /** 色差上限：0.004。真镜头的横向色差在这个量级 */
        const val TCA_LIMIT = 0.004f

        /** 色差滑条的刻度格数：0..100 ↔ 0..0.004 */
        const val TCA_STEPS = 100
    }
}

/** k1 滑条刻度 → k1 */
fun lensK1Of(step: Int): Float =
    (step - LensCorrectionParams.K1_STEPS / 2) / 1000f

/** k1 → k1 滑条刻度 */
// 两个方向都用**四舍五入**而不是截断：7/1000f 在 float32 下再乘回 1000f 是 6.9999995，
// `toInt()` 把它截成 6，于是滑条读回的值比用户拖到的少一格——表现是「松手之后数字自己动了一下」
fun lensK1StepOf(k1: Float): Int =
    Math.round(k1 * 1000f) + LensCorrectionParams.K1_STEPS / 2

/** 色差滑条刻度 → 色差强度 */
fun lensTcaOf(step: Int): Float = step * LensCorrectionParams.TCA_LIMIT / LensCorrectionParams.TCA_STEPS

/** 色差强度 → 色差滑条刻度 */
fun lensTcaStepOf(tca: Float): Int =
    Math.round(tca * LensCorrectionParams.TCA_STEPS / LensCorrectionParams.TCA_LIMIT)

/**
 * 镜头畸变与横向色差校正：**把校正后画面上的一个像素，映射回畸变图里的取样坐标**。
 *
 * ## 为什么是「逆」而不是正向
 *
 * 镜头的正向模型（场景 → 照片）没有闭式反函数，而我们要的恰恰是反方向：校正后的画面上
 * 这个像素的内容，原本在畸变图的哪里。所以这里实现的是**牛顿迭代解**
 *
 * ```
 * Rd = Ru · (1 − k1 + k1·Ru²)        求 Ru，给定 Rd
 * ```
 *
 * 收敛判据是**相对步长** `|Δ| <= 1e-6 · |Ru|`，不是绝对残差。绝对残差在 float 下对小 k1
 * 永远达不到 1e-5：真实镜头的 k1 量级是 0.001，而把方程通除 k1 化成首一形式之后系数放大到
 * 926，float 的舍入噪声本身就有 5.5e-5——于是每个像素都耗尽迭代预算、坐标原样返回。
 * 上游 lensfun 就栽在这里（它有 0.6% 的反向数据库坐标因此超差，最多 6 px）。
 * 所以这里的方程**不除 k1**：根相同，系数量级在 1 附近，判据又是无标度的。
 *
 * ## 归一化约定
 *
 * 半径按 **min(半宽, 半高)** 归一，与 lensfun 的畸变/色差一致。
 * 同一套文档里**暗角用的是半对角线**——两个约定并存，是这套库里最容易搞错的地方；
 * 本项目不做暗角，所以只需要记住这一个。
 *
 * ## 系数从哪来（重要）
 *
 * **本仓库不附带任何镜头数据库。** 逐镜头的 k1 要么来自 lensfun 的库（许可需单独确认）、
 * 要么来自厂商实测值。凭空给一个「通用 k1」比不给更坏：偏一点点不会崩、不会很明显，
 * 它只是把一种畸变修成另一种，而用户分辨不出来。所以 [k1] 是**用户给的量**，
 * 界面上必须说清它是 k1 而不是「校正强度」。
 *
 * 出处与约定：模型移植自 lensfun 0.3.4（LGPL-3.0）的 `mod-coord.cpp` / `mod-subpix.cpp`，
 * 参见 [LensSerious](https://github.com/aurelienpierreeng/LensSerious)（LGPL-3.0，自称与
 * liblensfun 做过 parity）。本文件是照着模型重写的 Kotlin，不是它的代码移植。
 */
object LensCorrection {

    private const val NEWTON_STEPS = 6
    private const val NEWTON_RTOL = 1e-6f

    /**
     * 归一化半径：以画面中心为原点，按 **min(半宽, 半高)** 缩放。
     *
     * 用 min 而不是对角线或半宽，是跟着 lensfun 的畸变约定；用错的话系数会差一个
     * 固定的倍数——表现为「边缘还是歪的，但怎么调都不对」。
     */
    fun normalisedRadius(x: Float, y: Float, width: Int, height: Int): Float {
        val half = normalisationScale(width, height)
        if (half <= 0f) return 0f
        val cx = x - (width - 1) / 2f
        val cy = y - (height - 1) / 2f
        return sqrt(cx * cx + cy * cy) / half
    }

    /**
     * 归一化尺度，**整个文件只有这一处**。它曾是两处各写一遍 `minOf(width, height) / 2f`，
     * 而测试只钉得住其中一处——实测把取样路径上那处改成按半宽、按半对角线，全部用例照绿。
     * 记错约定的表现不会崩：它只是让所有系数差一个固定的倍数，边缘还是歪的、怎么调都不对，
     * 所以它必须只有一处。
     */
    private fun normalisationScale(width: Int, height: Int): Float = minOf(width, height) / 2f

    /**
     * 牛顿迭代解出取样倍率 `Ru / Rd`。
     *
     * 返回 1.0 表示**这一格不校正**：迭代不收敛、落进负半径、或输入不是有限数。
     * 这三种都让「不校正」比「返回一个可疑坐标」安全——后者会取到画面外的像素。
     */
    fun undistortFactor(rd: Float, k1: Float): Float {
        if (rd == 0f || k1 == 0f) return 1f
        if (!rd.isFinite() || !k1.isFinite()) return 1f

        val oneMinusK = 1f - k1
        var ru = rd
        var converged = false
        for (step in 0 until NEWTON_STEPS) {
            val f = k1 * ru * ru * ru + oneMinusK * ru - rd
            val fp = 3f * k1 * ru * ru + oneMinusK
            if (fp == 0f || !fp.isFinite()) break
            val d = f / fp
            ru -= d
            if (!ru.isFinite()) break
            if (abs(d) <= NEWTON_RTOL * abs(ru)) {
                converged = true
                break
            }
        }
        if (!converged || ru < 0f) return 1f
        val factor = ru / rd
        // 这一行**没有测试能到达**（实测删掉它，全部用例照绿）——它挡的是 rd 为非规格化数、
        // 而 ru 收敛到 1 附近的组合，那不是像素半径会发生的事。
        // 留着是因为它的输出是**取样坐标的倍率**：坏一次取样的代价是永久的坏点，
        // 而一个分支的代价是一次比较。别的守卫都有测试盯着，只有这一行靠的是理由而不是钉子
        return if (factor.isFinite()) factor else 1f
    }

    /**
     * 横向色差的逐通道径向缩放。
     *
     * **绿通道（[channel] = 1）恒为 1.0**，R 与 B 往相反方向走。缩放随半径增长：
     * 色差在画面中心几乎没有，在边缘最明显——写成常数因子会让整张图均匀偏色，
     * 那不是色差，那是白平衡错了。
     *
     * @param r 归一化半径
     * @param strength [LensCorrectionParams.tca]
     * @param channel 0 = R，1 = G，2 = B
     */
    fun tcaScale(r: Float, strength: Float, channel: Int): Float {
        if (strength == 0f || !strength.isFinite() || !r.isFinite()) return 1f
        if (channel == 1) return 1f
        val sign = if (channel == 0) 1f else -1f
        val scale = 1f + sign * strength * r * r
        return if (scale.isFinite() && scale > 0f) scale else 1f
    }

    /**
     * 一个像素的**三个通道**取样偏移，一次算完。
     *
     * 存在的唯一理由是速度：[sourceX] / [sourceY] 每个都自带一次 [undistortFactor]
     * （内含 `ln` 与 6 步牛顿迭代），而**畸变对三个通道是同一个值**——它只依赖
     * 半径与 k1，不含 tca。按通道各算一遍的话，21.3 MP 上这一项被做 6 次、
     * 其中 5 次结果完全相同。真机上 21 MP + k1=0.02 的导出因此要约 4 分钟。
     *
     * 输出写进调用方给的数组（长度各 3，索引 0=R 1=G 2=B），**不在这里分配**——
     * 每像素 new 一次数组在两千万像素上就是两千万次分配。
     */
    fun offsetsOf(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        p: LensCorrectionParams,
        dx: FloatArray,
        dy: FloatArray,
    ) {
        val cx = x - (width - 1) / 2f
        val cy = y - (height - 1) / 2f
        val half = minOf(width, height) / 2f
        if (half <= 0f) {
            dx[0] = cx; dx[1] = cx; dx[2] = cx
            dy[0] = cy; dy[1] = cy; dy[2] = cy
            return
        }
        val rd = sqrt(cx * cx + cy * cy) / half
        // 畸变：三个通道共用，算一次
        val kd = undistortFactor(rd, p.k1)
        // 色差：只有这里分通道，而绿通道的 tcaScale 恒为 1
        for (ch in 0..2) {
            val k = kd * tcaScale(rd, p.tca, ch)
            dx[ch] = cx * k
            dy[ch] = cy * k
        }
    }

    /**
     * 条带式重采样要往每条带上下各多读多少行，才够覆盖所有取样点。
     *
     * 位移是 `|cy · (k − 1)|`，而 `rd` 只决定 `k`、`|cy|` 只决定乘多少，所以上界拆成
     * `max|cy| × max|k − 1|` 两项分别求。这**不是**把两项凑在一起：实测 21 MP 的 4:3 与 16:9
     * 上只松 1~2%，而超宽画幅松到 1.5 倍，仍远好过按经验系数估。
     *
     * 为什么上界不能估松：取样那侧把越界的行号钳到边缘行，于是估松了**不会报错**，
     * 出来的是一条糊带；估紧了则缓冲白占内存。两头都不能偏。
     *
     * 曾经那版用 `(|k1| + |tca|) · 对角线` 这个线性式估：它对 4:3 够、对 16:9 不够。
     * 两个原因叠加——真实位移随 `rd²` 涨而那个式子只按 `rd` 算，且它乘的是**短边**的一半，
     * 而 `|cy|` 能到**长边**的一半。竖幅与超宽幅因此全线欠估。
     */
    fun bandSlack(width: Int, height: Int, p: LensCorrectionParams): Int {
        if (width <= 0 || height <= 0 || p.isIdentity) return 0
        val half = normalisationScale(width, height)
        if (half <= 0f) return 0
        val rdMax = hypot((width - 1).toFloat(), (height - 1).toFloat()) / (2 * half)
        return ceil((height - 1) / 2f * worstChannelDeviation(rdMax, p)).toInt() + 2
    }

    /**
     * 半径 `rd` 上、三个通道里取样因子偏离 1 的最大值。
     *
     * `max|k − 1|` 沿 `rd` **不是单调的**：`k1 < 0` 时牛顿迭代在某个半径之外不再收敛，
     * 因子回落回 1，于是最坏点的半径既不是 0 也不是画面对角。所以它只能在区间上求最大值。
     *
     * 粗网格定位 + 一次局部细化：约 2000 次 [undistortFactor]，相对每像素一次可以忽略。
     * 网格只能给出「不小于真值」的**近似**上界，因此它由 `BandSlackTest` 的逐像素对拍钉住——
     * 那条测试里的六种画幅（含竖幅与超宽）都能让一个更松的界现形。
     */
    private fun worstChannelDeviation(rdMax: Float, p: LensCorrectionParams): Float {
        val coarse = 1024
        val step = rdMax / coarse
        var best = 0f
        var bestIndex = 0
        for (i in 0..coarse) {
            val v = deviationAt(i * step, p)
            if (v > best) {
                best = v
                bestIndex = i
            }
        }
        val lo = maxOf(0f, (bestIndex - 1) * step)
        val hi = minOf(rdMax, (bestIndex + 1) * step)
        val fine = (hi - lo) / 1024f
        for (j in 0..1024) {
            val v = deviationAt(lo + j * fine, p)
            if (v > best) best = v
        }
        return best
    }

    private fun deviationAt(rd: Float, p: LensCorrectionParams): Float {
        var worst = 0f
        for (ch in 0..2) {
            val d = abs(undistortFactor(rd, p.k1) * tcaScale(rd, p.tca, ch) - 1f)
            if (d > worst) worst = d
        }
        return worst
    }

    /** 校正后画面的这个像素，R 通道该去哪里取样 */
    fun sourceX(x: Float, y: Float, width: Int, height: Int, p: LensCorrectionParams, channel: Int): Float =
        centred(x, y, width, height, p, channel, axisIsX = true)

    /** 校正后画面的这个像素，B 通道该去哪里取样（色差只动 R 与 B） */
    fun sourceY(x: Float, y: Float, width: Int, height: Int, p: LensCorrectionParams, channel: Int): Float =
        centred(x, y, width, height, p, channel, axisIsX = false)

    private fun centred(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        p: LensCorrectionParams,
        channel: Int,
        axisIsX: Boolean,
    ): Float {
        val cx = x - (width - 1) / 2f
        val cy = y - (height - 1) / 2f
        val half = normalisationScale(width, height)
        if (half <= 0f) return if (axisIsX) x else y

        val rd = sqrt(cx * cx + cy * cy) / half
        // 顺序照 lensfun：坐标链（畸变）先，色差作为其后的**逐通道**阶段
        val k = undistortFactor(rd, p.k1) * tcaScale(rd, p.tca, channel)
        val moved = if (axisIsX) cx * k else cy * k
        return (if (axisIsX) (width - 1) / 2f else (height - 1) / 2f) + moved
    }
}
