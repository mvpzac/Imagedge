package com.imagedge.camera.feature.edit.photo

import com.imagedge.camera.lut.KeyAxis
import com.imagedge.camera.lut.RangeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「把滑条上的值变成一个合法的区间键」。
 *
 * 这层存在的唯一理由是**不许抛**：[RangeKey.of] 在羽化低于下限、`from > to`、
 * 上拐点越界时都会抛 IllegalArgumentException，而这三个条件用户用滑条**随手就能拖出来**
 * （把「终点」拖过「起点」，或者在亮部把羽化拉到很大）。一个会因为滑到某个位置就崩的编辑器，
 * 比一个会自己收敛的编辑器差得多。
 *
 * 这里的每一条都是**滑条能到达的**输入，不是构造出来的边角。
 */
class SelectiveDraftTest {

    @Test
    fun `an ordinary range passes through untouched`() {
        val key = rangeKeyOf(KeyAxis.LUMA, 0.35f, 0.75f, 0.1f, inverted = false)

        assertEquals(0.35f, key.from, 1e-5f)
        assertEquals(0.75f, key.to, 1e-5f)
        assertEquals(0.1f, key.feather, 1e-5f)
    }

    @Test
    fun `dragging the end past the start repairs it instead of throwing`() {
        // 起点 0.9、终点 0.2：用户把两条滑条交叉拖了。RangeKey.of 在这里会抛
        val key = rangeKeyOf(KeyAxis.LUMA, 0.9f, 0.2f, 0.1f, inverted = false)

        assertTrue("终点必须被拉回起点之前", key.from <= key.to)
    }

    @Test
    fun `the upper knot never leaves the axis range`() {
        // 亮部：上拐点锁在 1 以内。终点 1.0 + 羽化 0.1 = 1.1，RangeKey.of 会抛
        val key = rangeKeyOf(KeyAxis.LUMA, 0.5f, 1f, 0.1f, inverted = false)

        assertTrue("上拐点 ${key.to + key.feather} 超出了 1", key.to + key.feather <= 1f + 1e-5f)
    }

    @Test
    fun `a wide feather in the highlights still leaves a usable interval`() {
        // 羽化拖到最大而区间也靠上：两者相加会顶穿 1，收敛之后必须**还有区间**，
        // 而不是塌成一个点（from == to 时那段区间是空的，用户会看到「选了等于没选」）
        val key = rangeKeyOf(KeyAxis.LUMA, 0.95f, 1f, 0.3f, inverted = false)

        assertTrue("区间不能塌成一个点", key.to > key.from)
        assertTrue(key.to + key.feather <= 1f + 1e-5f)
    }

    @Test
    fun `a hue range is allowed to cross the red seam`() {
        // 色相的上限是 2 而不是 1：终点 1.15 + 羽化 0.1 = 1.25 > 1 在这里合法，
        // 它正是「只键红色」要跨过 h=0 接缝的那一格
        val key = rangeKeyOf(KeyAxis.HUE, 0.9f, 1.15f, 0.1f, inverted = false)

        assertEquals(1.15f, key.to, 1e-5f)
    }

    @Test
    fun `a hue range dragged past the cap is still clamped rather than thrown`() {
        val key = rangeKeyOf(KeyAxis.HUE, 0f, 3f, 0.1f, inverted = false)

        assertTrue("色相上拐点 ${key.to + key.feather} 超出了 2", key.to + key.feather <= 2f + 1e-5f)
    }

    @Test
    fun `a zero feather is lifted to the minimum rather than rejected`() {
        // 滑条能拖到 0，而 0 会让 GLSL 的 smoothstep(e0, e0, x) 除零
        val key = rangeKeyOf(KeyAxis.LUMA, 0.3f, 0.6f, 0f, inverted = false)

        assertEquals(RangeKey.MIN_FEATHER, key.feather, 0f)
    }

    @Test
    fun `every combination the sliders can reach produces a constructible key`() {
        // 穷举而不是举例：滑条能到达的是**连续区间**里的任意点，
        // 所以这里扫 5×5×4×2 = 200 组。RangeKey.of 抛的话这里就是红的
        for (axis in KeyAxis.entries) {
            for (fromI in 0..4) {
                for (toI in 0..4) {
                    for (featherI in 0..3) {
                        for (inverted in listOf(false, true)) {
                            val key = rangeKeyOf(
                                axis,
                                fromI * 0.5f,
                                toI * 0.5f,
                                featherI * 0.1f,
                                inverted
                            )
                            // 收敛之后每一组都必须还是一个**非空**的区间：
                            // 塌成一个点的话用户看到的是「选了等于没选」
                            assertTrue(
                                "${axis.name} $fromI/$toI/$featherI → 区间塌了",
                                key.to > key.from
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `each axis has its own starting range`() {
        // 换轴时若把亮度那条 0.4..0.6 原样带过去，色相那一格会变成「只键黄色」——
        // 合法、但几乎肯定不是用户点「色相」时想要的
        val luma = defaultRangeOf(KeyAxis.LUMA)
        val hue = defaultRangeOf(KeyAxis.HUE)
        val sat = defaultRangeOf(KeyAxis.SATURATION)

        assertTrue("亮度的默认区间应在中调", luma.first < 0.6f)
        assertTrue("色相的默认区间应跨过红端接缝", hue.second > 1f)
        assertTrue("饱和度的默认区间应在中高", sat.first > 0f)
    }
}
