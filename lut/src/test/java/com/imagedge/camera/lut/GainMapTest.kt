package com.imagedge.camera.lut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增益图数学。一个 Ultra HDR 文件 = 基础图 + 增益图，而增益图每个像素只存**一个数**：
 * 「这里比基础图亮多少（或暗多少）」的对数比值。所以这层要钉的是四件事——
 * 往返不丢信息、超出范围的两端确实被钳住、强度 0 真的是「不加增益」而不是「弱一点」、
 * 以及存进 8 位 JPEG 之后还剩多少精度。
 *
 * 口径见 [GainMap]：一律线性光，复用 [SrgbTransfer]，不引入第二次 EOTF。
 */
class GainMapTest {

    /** 编码域的中灰；解码成线性光约 0.214 */
    private val midGrey = 0.5f
    private val midGreyLinear = SrgbTransfer.decode(midGrey)

    @Test
    fun `no gain sits exactly in the middle of the range`() {
        // ratio = 1 落在对数区间 [0.25, 4] 的正中 → 0.5。写死这条是因为
        // 「不加增益 = 灰增益图」是整条链的地基：把它挪偏一点点，
        // 后面每一次导出都会带着一点点偏色，而没人看得出偏在哪
        assertEquals(0.5f, GainMap.encode(midGrey, midGreyLinear), 1e-6f)
    }

    @Test
    fun `the encoded value round trips back to the ratio it came from`() {
        // 期望值是 `encode` 真正用的那个比率——分子分母都带 [GainMap.OFFSET]，
        // 所以它并不等于我随手写下的 ratio（0.3 × 0.214 那一位的差别就是 1e-4 的效果）。
        // 写成 `assertEquals(ratio, …)` 会把这条钉成一个假的恒等式：
        // 实现里哪天把 OFFSET 去掉，测试会红，而那未必是坏事
        for (ratio in listOf(0.3f, 0.75f, 1f, 1.5f, 2.5f, 3.9f)) {
            val hdr = midGreyLinear * ratio
            val expected = (hdr + GainMap.OFFSET) / (midGreyLinear + GainMap.OFFSET)
            val value = GainMap.encode(midGrey, hdr)
            assertEquals(expected, GainMap.valueToRatio(value), 1e-5f)
        }
    }

    @Test
    fun `the offset keeps a pitch black pixel pair finite`() {
        // 基础图纯黑、HDR 也想要纯黑：分子分母都是 OFFSET，倍率正好 1，**不需要增益**。
        // 这里的风险是去掉 OFFSET 后变成 0/0 得到 NaN，而 NaN 写进增益图
        // 在 HDR 屏上是一块永远修不好的坏点。值取 0.5（不加增益）而不是 0：
        // 0 意味着「把这块压到最暗」，那是把已经正确的黑又压了一次
        val value = GainMap.encode(0f, 0f)

        assertEquals(true, value.isFinite())
        assertEquals(0.5f, value, 1e-6f)
    }

    @Test
    fun `ratios beyond the clamp collapse to the two ends`() {
        // 高光推过头会被 MAX 截住、阴影压过头被 MIN 截住。没有这条钳位，
        // 一次编辑失误就会在 HDR 屏上炸出一块纯白或纯黑，而 SDR 端完全看不出问题
        val tooBright = GainMap.encode(midGrey, 1.0f)
        val tooDark = GainMap.encode(midGrey, 0.0f)

        assertEquals(1f, tooBright, 0f)
        assertEquals(0f, tooDark, 0f)
    }

    @Test
    fun `zero strength means no gain whatever the hdr ratio is`() {
        // 用户把强度拉到 0 期待的是「回到原图」，不是「弱一点的 HDR」。
        // 折成 0.95 倍增益的照片，看起来和原图一样，发出去也确实一样——
        // 那是这个开关最坏的一种坏：按了等于没按，但文件里多了一张图
        assertEquals(0.5f, GainMap.encode(midGrey, midGreyLinear * 4f, strength = 0f), 1e-6f)
        assertEquals(0.5f, GainMap.encode(midGrey, midGreyLinear * 0.25f, strength = 0f), 1e-6f)
    }

    @Test
    fun `full strength is the identity on the encode`() {
        val hdr = midGreyLinear * 2.5f
        assertEquals(
            GainMap.encode(midGrey, hdr),
            GainMap.encode(midGrey, hdr, strength = 1f),
            0f
        )
    }

    @Test
    fun `strength above one is clamped to full rather than amplifying past the range`() {
        // 强度滑条的取值范围是 0..1。留一个「>1 就更强」的口子，
        // 增益图的值会被推到 1 以外，而解码端只认 0..1——文件里存的就是个越界的数
        val hdr = midGreyLinear * 3f
        assertEquals(
            GainMap.encode(midGrey, hdr, strength = 1f),
            GainMap.encode(midGrey, hdr, strength = 5f),
            0f
        )
    }

    @Test
    fun `reconstruct inverts encode when the ratio was not clamped`() {
        val hdr = midGreyLinear * 1.87f
        val value = GainMap.encode(midGrey, hdr)
        assertEquals(hdr, GainMap.reconstruct(midGrey, value), 1e-3f)
    }

    @Test
    fun `a ratio surviving an eight bit gain map is still within about one percent`() {
        // 增益图存进 8 位：一个值步长 = ln(MAX/MIN)/255 = ln16/255 ≈ 0.0109（对数域），
        // 换算回倍率是 e^0.0109 ≈ 1.011，也就是最差 ±1.1%。这条钉住的是
        // 「写进 JPEG 再读回来会偏多少」——它决定了 8 位够不够，也决定了
        // 以后要不要把增益图提到 16 位。没有它，精度这件事就只能靠感觉
        var worst = 0f
        for (ratio in listOf(0.3f, 0.5f, 1f, 1.8f, 3f, 3.9f)) {
            val byte = GainMap.toByte(GainMap.ratioToValue(ratio))
            val back = GainMap.valueToRatio((byte.toInt() and 0xFF) / 255f)
            worst = maxOf(worst, kotlin.math.abs(back - ratio) / ratio)
        }
        assertTrue("8 位往返最差相对误差 $worst，超过 1.5%", worst <= 0.015f)
    }

    @Test
    fun `the gain map byte is the value scaled to the full eight bit range`() {
        assertEquals(255, GainMap.toByte(1f).toInt() and 0xFF)
        assertEquals(0, GainMap.toByte(0f).toInt() and 0xFF)
        // 0.5 是「不加增益」，也就是灰增益图必须是 128 而不是 127：
        // 取整写错一位，导出的每一张「没开 HDR」的照片都会带着一点点提亮
        assertEquals(128, GainMap.toByte(0.5f).toInt() and 0xFF)
    }
}
