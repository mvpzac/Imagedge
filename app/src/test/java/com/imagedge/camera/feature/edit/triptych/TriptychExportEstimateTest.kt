package com.imagedge.camera.feature.edit.triptych

import com.imagedge.camera.feature.edit.clip.ClipMath
import com.imagedge.camera.feature.edit.clip.ClipSpec
import com.imagedge.camera.feature.edit.triptych.Quality.P1080
import com.imagedge.camera.feature.edit.triptych.Quality.P720
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 三拼的画布尺寸与体积预估——两者都是导出前用户唯一能看到的数字
 * </pre>
 */
class TriptychExportEstimateTest {

    /**
     * 成品 = 每格纵向堆叠，格宽 × (格高 × 格数)。
     *
     * 期望值写成 [CellSize] 而不是 `1920 to 3240` 这样的 `Pair`：两者是互不相干的
     * 类型，JUnit 走 `assertEquals(Object, Object)`，编译能过而运行时恒不相等——
     * 那样一条断言钉不住任何东西（`LiveTriptychAspectLabelTest` 里有同样的记录）。
     *
     * 27:16 三格堆出 1080×1920，是唯一能直发 Story/Reels 的一档；另外三档见下一条。
     */
    @Test
    fun `成品高度是格高乘以格数`() {
        assertEquals(CellSize(1920, 3240), triptychCanvasSize(Aspect.R16_9, P1080))
        assertEquals(CellSize(1080, 1920), triptychCanvasSize(Aspect.R27_16, P1080))
        assertEquals(CellSize(1080, 4050), triptychCanvasSize(Aspect.R4_5, P1080))
    }

    /**
     * 四档在 P1080 下的成品尺寸全列一遍。
     *
     * 1080p 下 `cellSize` 的缩放系数恒为 1（各档参考短边都 ≤ 1080 的上限），
     * 所以这里列的就是 `refW × (refH × 3)`。
     *
     * **1080×3240 出现两次但不是同一档**：R16_9 是 1920×3240，R1_1 才是 1080×3240。
     * 两条高度相同、宽度差一倍——错写成 R1_1 的宽度会让 16:9 的成品被压成竖构图，
     * 而这类错误在肉眼扫一眼的 UI 断言里最容易漏。
     */
    @Test
    fun `P1080 下四档的成品尺寸各不相同`() {
        assertEquals(CellSize(1080, 3240), triptychCanvasSize(Aspect.R1_1, P1080))
        assertEquals(CellSize(1920, 1080), Aspect.R16_9.cellSize(P1080))
        assertEquals(CellSize(1080, 1080), Aspect.R1_1.cellSize(P1080))
        assertEquals(CellSize(1080, 1350), Aspect.R4_5.cellSize(P1080))
        assertEquals(CellSize(1080, 640), Aspect.R27_16.cellSize(P1080))
    }

    /** 画布宽度逐格等于该档的格子宽度，三格只乘高不乘宽 */
    @Test
    fun `成品宽度就是格子宽度`() {
        for (aspect in Aspect.entries) {
            for (q in Quality.entries) {
                val cell = aspect.cellSize(q)
                assertEquals(
                    "${aspect.name}/${q.name} 成品宽度应等于格子宽度",
                    cell.width,
                    triptychCanvasSize(aspect, q).width
                )
            }
        }
    }

    /** 三格的像素必须完全一致，否则拼接处会有可见接缝 */
    @Test
    fun `每格的像素由同一个 cellSize 推导，各档一致`() {
        for (aspect in Aspect.entries) {
            for (q in Quality.entries) {
                val cell = aspect.cellSize(q)
                assertEquals("${aspect.name}/${q.name} 宽必须是偶数", 0, cell.width % 2)
                assertEquals("${aspect.name}/${q.name} 高必须是偶数", 0, cell.height % 2)
            }
        }
    }

    /**
     * 码率取自 [Quality.bitrate]，所以低档位必然更小——**但只在有片段时成立**。
     *
     * 空输入下视频部分是 0，两档都只剩那 3MiB 的静态拼图常数，于是**恒相等**。
     * 把这条断言挪到空列表上它会立刻红，而红的原因不是代码错了，是前提没了。
     */
    @Test
    fun `720p 的预估体积小于 1080p`() {
        val durations = listOf(1500L, 2000L, 1200L)
        assertTrue(
            estimateTriptychBytes(durations, P720) < estimateTriptychBytes(durations, P1080)
        )
        // 反过来也钉住那个前提本身：空输入下两档完全相等，谁要把它改成不等就得先解释
        assertEquals(
            estimateTriptychBytes(emptyList(), P720),
            estimateTriptychBytes(emptyList(), P1080)
        )
    }

    @Test
    fun `体积随时长单调增加`() {
        val short = estimateTriptychBytes(listOf(1000L, 1000L, 1000L), P1080)
        val long = estimateTriptychBytes(listOf(3000L, 3000L, 3000L), P1080)
        assertTrue("$long 应大于 $short", long > short)
    }

    /** 空输入不能返回 0——那会让界面显示「0.0 MB」，像是没生成内容 */
    @Test
    fun `没有片段时仍给出拼图那一份的体积`() {
        assertTrue(estimateTriptychBytes(emptyList(), P1080) > 0L)
        // 精确到那个常数本身：3L * 1024 * 1024，不是 3_000_000
        assertEquals(3L * 1024 * 1024, estimateTriptychBytes(emptyList(), P1080))
    }

    /** 负时长不能把预估拉成负数（`coerceAtLeast(0L)` 就在函数里，钉住它没被摘掉） */
    @Test
    fun `负时长按零计`() {
        assertEquals(3L * 1024 * 1024, estimateTriptychBytes(listOf(-5_000L), P1080))
    }

    // ── 槽位的初始选段：短素材那一支必须有名字 ────────────────────

    /**
     * 素材 ≥ [ClipMath.MIN_CLIP_MS] 时，初始选段是「从头取整个素材，最多到
     * [ClipMath.MAX_CLIP_MS]」，也就是 `clampStart(0, …)` + `clampEnd(…)` 的结果。
     *
     * 10s 素材取不到 10s：上限由 `clampEnd` 的 `startMs + MAX_CLIP_MS` 承担，
     * 与导出侧 `VideoTrimmer.trim` 的逐字同一条，所以初始态就不是一个导出时会
     * 悄悄改短的选段。
     */
    @Test
    fun `素材够长时初始选段是整段且不超过上限`() {
        // 期望值直接用 ClipSpec（data class，四字段齐全），不用 Pair 或自定义
        // 壳类去比——那两种写法编译都过、运行时恒不相等
        assertEquals(ClipSpec(0L, 3_000L, 0L), initialClipSpec(3_000L))
        assertEquals(ClipSpec(0L, ClipMath.MAX_CLIP_MS, 0L), initialClipSpec(10_000L))
        assertEquals(ClipSpec(0L, ClipMath.MIN_CLIP_MS, 0L), initialClipSpec(1_500L))
    }

    /**
     * 素材短于 [ClipMath.MIN_CLIP_MS] 时**不存在合法选段**，两个钳制塌陷到素材两端，
     * 于是初始选段 = 整个素材，且 `durationMs < MIN_CLIP_MS`。
     *
     * 这是 [ClipMath] 的退化区间（零长度但**有序**），不是「最短片段」被绕过。
     * 钉在这里是为了让「短素材拿到多短的片段」成为一条可证伪的事实，
     * 而不是某天有人把它悄悄改成 0 或悄悄补成 1500。
     */
    @Test
    fun `素材短于最短片段时长时初始选段退化为整段素材`() {
        val spec = initialClipSpec(1_000L)
        assertEquals(0L, spec.startMs)
        assertEquals(1_000L, spec.endMs)
        assertEquals(1_000L, spec.durationMs)
        assertTrue(spec.durationMs < ClipMath.MIN_CLIP_MS)

        // 时长 0 的元数据也不能产出倒置区间（0 也不小于下限，退化到 0）
        val empty = initialClipSpec(0L)
        assertEquals(0L, empty.startMs)
        assertEquals(0L, empty.endMs)
        assertTrue(empty.endMs >= empty.startMs)
    }
}