package com.imagedge.camera.lut

import kotlin.math.exp
import kotlin.math.ln

/**
 * 增益图（gain map）的数学。一个 Ultra HDR 文件是**两张图**：
 * 基础图存正常看到的颜色，增益图每像素只存**一个数**——「这里比基础图亮多少（或暗多少）」。
 * 显示端把倍率套回基础图上，就得到了 HDR 屏上的那份画面；SDR 屏忽略增益图，
 * 于是同一个文件在两种屏幕上都有正确的样子。
 *
 * 存的是**对数域的归一化值** `value ∈ [0,1]`，实际倍率
 * `ratio = exp(value · ln(MAX/MIN) + ln(MIN))`，即 `MIN` 到 `MAX` 之间的等比刻度。
 * 取对数是因为动态范围天生是倍率的：线性刻度会把「暗部多提 0.5 倍」和
 * 「亮部多提 0.5 倍」当成同一件事，而它们在画面上完全不是一回事。
 *
 * **口径：一切输入都是线性光**，解码只走 [SrgbTransfer] 这一份。增益图里不再引入
 * 第二次 EOTF——每多一次传递函数，CPU 与 GPU、编辑器与导出之间就多一处能分家的地方。
 *
 * 出处与参考实现：格式与编码规则见 Android 官方 Ultra HDR 文档；
 * 数学参考 [bjzhou/PhotonCamera](https://github.com/bjzhou/PhotonCamera)（Apache-2.0）的
 * `hdr/RawGainmapMath.kt`，本文件的边界与强度定义是按本项目的口径重写的。
 */
object GainMap {

    /**
     * 允许的倍率范围，直接交给 `Gainmap.setRatioMin / setRatioMax`。
     * 上下不是对称的：高光提 4 倍仍有意义，暗部压到 0.25 倍以下
     * 只会把本就接近黑色的信息压成纯黑。
     *
     * ⚠️ **这里收的是普通倍率，不是 log2**。官方格式文档里有一处把
     * `ratioMin` 描述成 `log2(min_content_boost)`，而解码公式里还夹着
     * `capacityMin/capacityMax/maxDisplayBoost` 一层显示能力归一化——照那段文字
     * 去改这里的取值，会让 0.25 被当成 2^0.25 ≈ 1.19、4 被当成 2^4 = 16，
     * 画面亮到没法看，而**导出这一侧一次都不报错**（我按那段文字写过一次，
     * 差点把正确的实现改坏）。
     *
     * 实际契约的依据是能出片的那份代码：[bjzhou/PhotonCamera](https://github.com/bjzhou/PhotonCamera)（Apache-2.0）
     * `hdr/GpuReferenceGainmapProducer.kt` 与 `hdr/EstimatedSdrGainmapProducer.kt` 都是直接传倍率，
     * 它的仪器化用例 `Jpeg444ExportEncoderTest` 断言的也是 `ratioMin = 1f` / `ratioMax = 4f`。
     * 要改这里之前，先用真机导一张出来看，别只信文档的那一句。
     */
    const val MIN_GAIN_RATIO = 0.25f
    const val MAX_GAIN_RATIO = 4.0f

    /**
     * 分母上的下限。`hdrLinear` 可以合法地是 0，而基础图的暗部解码出来也接近 0；
     * 没有它就是一次 0/0 或者除以极小数，得到 ±Inf 或 NaN——而 NaN 写进增益图
     * 会在 HDR 屏上表现为一块永远修不好的坏点。
     */
    const val OFFSET = 1e-4f

    private val LOG_MIN = ln(MIN_GAIN_RATIO.toDouble()).toFloat()
    private val LOG_SPAN = ln((MAX_GAIN_RATIO / MIN_GAIN_RATIO).toDouble()).toFloat()

    /**
     * 倍率 → 增益图的值。`value = 0.5` 恰好是不加增益，这就是「强度 0 = 原图」成立的原因。
     *
     * [strength] 是用户那一档，0..1，在**对数域线性缩放**：`log(ratio') = strength · log(ratio)`。
     * 选对数域是为了让「强度 50%」在暗部和亮部一样重——线性域缩放会让暗部几乎没变化。
     * 超范围的输入被夹到 0..1：滑条只给 0..1，留一个 >1 的口子，
     * 就会写出一个解码端不认的值。
     */
    fun ratioToValue(ratio: Float, strength: Float = 1f): Float {
        val s = strength.coerceIn(0f, 1f)
        // 这里**不再**对 ratio 夹 [MIN, MAX]：末行的 coerceIn 已经把值饱和到 0..1，
        // 多夹一次是实测出来的冗余（删掉它，全部用例照绿）。留着的坏处是
        // 读代码的人以为钳位发生在这里，真去改这里的上下界时改不到任何东西
        val log = ln(ratio.toDouble()).toFloat() * s
        return ((log - LOG_MIN) / LOG_SPAN).coerceIn(0f, 1f)
    }

    /** [ratioToValue] 的逆。[value] 越界按边界算，不抛——解码的是文件，而文件不可信。 */
    fun valueToRatio(value: Float): Float =
        exp(value.coerceIn(0f, 1f) * LOG_SPAN + LOG_MIN).toFloat()

    /**
     * 一对像素 → 增益图的值。[sdrEncoded] 是**编码域**的 0..1（基础图上读出来的那个值），
     * [hdrLinear] 是同一处在**线性光**下想要的亮度。
     */
    fun encode(sdrEncoded: Float, hdrLinear: Float, strength: Float = 1f): Float {
        val sdrLinear = SrgbTransfer.decode(sdrEncoded)
        val ratio = (hdrLinear.coerceAtLeast(0f) + OFFSET) / (sdrLinear + OFFSET)
        return ratioToValue(ratio, strength)
    }

    /**
     * [encode] 的逆：给回编码域的基础值，算出该处的 HDR 线性亮度。
     *
     * 它有两个用处：一是把增益图的变化幅度读成数字（测试与调试）；
     * 二是将来要做「按增益图重新调基础图」这类操作时，唯一的正确算法就在这里。
     */
    fun reconstruct(sdrEncoded: Float, value: Float): Float =
        (SrgbTransfer.decode(sdrEncoded) + OFFSET) * valueToRatio(value) - OFFSET

    /**
     * 增益图的值 → 8 位。
     *
     * 0.5 取整成 **128** 而不是 127：`0.5·255 = 127.5`，Kotlin 的 `round` 是四舍五入到偶数吗——
     * 不是，`kotlin.math.round` 是「四舍五入且 0.5 向上」。取整写错一位，
     * 导出的每一张「没开 HDR」的照片都会带着一点点提亮，而那一点点在屏幕上没人看得出。
     */
    fun toByte(value: Float): Byte =
        kotlin.math.round(value.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte()
}
