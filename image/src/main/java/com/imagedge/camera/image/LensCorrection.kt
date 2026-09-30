package com.imagedge.camera.image

import kotlin.math.abs
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
}

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
