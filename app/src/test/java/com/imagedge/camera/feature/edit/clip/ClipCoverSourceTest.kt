package com.imagedge.camera.feature.edit.clip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 封面来源的裁定——coverMs 到「这一格画哪一路」那一步，纯函数故可在 JVM 上钉
 * </pre>
 */
class ClipCoverSourceTest {

    /**
     * `null` 与 `0L` 必须是两路，这是 [ClipSpec.coverMs] 可空的全部理由。
     *
     * 曾经 `coverMs == 0L` 兼作「未重选封面」的哨兵，而三拼候选条带最左那张的时刻
     * 恰好就是 0（`loadCoverThumbs` 抽帧用 `duration * i / count`，`i = 0`）——
     * 点它会被读成一次「恢复原图」：标签翻回去、恢复入口消失、画面换成静态图。
     * 现在两者在类型层面就分开了：`null` → 静态图，`0L` → 视频里的第 0 帧。
     *
     * 注意 `Frame` 里那个值**不等于** 0L：0 落在选段 `[1000, 3000]` 之外，
     * 被 [ClipMath.effectiveCoverMs] 收到下限 1000。那正是应当的样子——
     * 用户点的是第 0 帧，而取帧方拿到的是收口后的那个时刻（下面第 2 条单独钉收口）。
     */
    @Test
    fun `未选封面走静态图而 0 毫秒走抽帧`() {
        val clip = ClipSpec(startMs = 1000L, endMs = 3000L, coverMs = null)
        assertEquals(CoverSource.Still, coverSourceFor(clip))
        // 同一个选段，只把 coverMs 换成 0L：另一路，且不是 Still
        assertEquals(CoverSource.Frame(1000L), coverSourceFor(clip.copy(coverMs = 0L)))
    }

    /**
     * 越出选段的封面时刻**必须先收口再交给取帧方**。
     *
     * 这一条钉的就是 [coverSourceFor] 里那句 `ClipMath.effectiveCoverMs`：把它摘掉、
     * 改成把 `coverMs` 原样塞进 `Frame`，本条当场红（`Frame(9000)` 对不上 `Frame(3000)`）。
     * 那个失败不好看也不是误报：预览与导出共用同一个 `buildTriptychBitmap`，
     * 收口漏了会**两边一起**用选段外的那一帧——用户在屏幕上从头到尾没见过它，
     * 却在相册里拿到它。
     *
     * 三条断言把 `coerceIn` 的三个分支各钉一次：上界外、下界外、界内不动。
     */
    @Test
    fun `越出选段的封面被收口进选段而不是原样送出`() {
        assertEquals(CoverSource.Frame(3000L), coverSourceFor(ClipSpec(1000L, 3000L, 9000L)))
        assertEquals(CoverSource.Frame(1000L), coverSourceFor(ClipSpec(1000L, 3000L, 500L)))
        assertEquals(CoverSource.Frame(2000L), coverSourceFor(ClipSpec(1000L, 3000L, 2000L)))
    }

    /**
     * 区间倒置（`startMs > endMs`）时结果恒为 `startMs` 而**不抛异常**。
     *
     * 靠的是 `effectiveCoverMs` 里那个 `endMs.coerceAtLeast(startMs)`：没有它
     * `coerceIn(3000, 1000)` 会抛 IllegalArgumentException。倒置区间在本项目是**可达**的
     * ——[ClipSpec] 不校验区间（它的 `durationMs` 的 KDoc 明说空区间与倒置都退化成 0），
     * 而这一层是预览与导出共同的入口，它不能崩。
     */
    @Test
    fun `倒置区间收到起手里而不是抛异常`() {
        assertEquals(CoverSource.Frame(3000L), coverSourceFor(ClipSpec(3000L, 1000L, 2000L)))
        assertEquals(CoverSource.Frame(3000L), coverSourceFor(ClipSpec(3000L, 1000L, 9999L)))
    }

    /**
     * `null` 那一支不碰任何数值：选段怎么退化都不会把它读成 `Frame`。
     *
     * 与 [CoverSource.Still] 的定义对齐——「未重选」只看 `coverMs` 是不是 `null`，
     * 不看选段长什么样。否则零长选段会多出一条没人预期的第三路。
     */
    @Test
    fun `未选封面在任何选段下都是静态图`() {
        assertEquals(CoverSource.Still, coverSourceFor(ClipSpec(0L, 0L, null)))
        assertEquals(CoverSource.Still, coverSourceFor(ClipSpec(3000L, 1000L, null)))
    }
}
