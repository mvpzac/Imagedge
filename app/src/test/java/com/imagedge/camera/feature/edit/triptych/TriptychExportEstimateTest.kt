package com.imagedge.camera.feature.edit.triptych

import com.imagedge.camera.feature.edit.clip.ClipMath
import com.imagedge.camera.feature.edit.clip.ClipSpec
import com.imagedge.camera.feature.edit.triptych.Quality.P1080
import com.imagedge.camera.feature.edit.triptych.Quality.P720
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(CellSize(1080, 3240), triptychCanvasSize(Aspect.R1_1, P1080))
        assertEquals(CellSize(1080, 4050), triptychCanvasSize(Aspect.R4_5, P1080))
        assertEquals(CellSize(1080, 1920), triptychCanvasSize(Aspect.R27_16, P1080))
    }

    /**
     * 四档的成品尺寸**两两不同**，两个画质档下都成立。
     *
     * 上一版这里列的是四档的 `cellSize` 取值——那是 `LiveTriptychAspectLabelTest` 的
     * `1080p 档位下每格的像素与规格一致` / `720p 档位等比缩小且不放大` 已经钉过的东西，
     * 而名字承诺的「各不相同」它一条也没断言到：有人把 1:1 的参考尺寸抄成 4:5 的，
     * 列期望常量那种写法照样绿（它比的是各自的常量，不是两两关系）。
     *
     * 1080p 下缩放系数恒为 1（各档参考短边都 ≤ 1080），四档成品就是
     * `refW × (refH × 3)`：1920×3240 / 1080×3240 / 1080×4050 / 1080×1920。
     * **高度 3240 出现两次但不是同一档**（R1_1 与 R16_9，宽度差一倍），
     * 所以去重比的是整对尺寸，不能只看高。720p 下四档是 1280×2160 / 720×2160 /
     * 720×2700 / 1080×1920——27:16 不缩（短边 640 低于上限），于是它仍是 1080×1920。
     */
    @Test
    fun `四档的成品尺寸两两不同`() {
        for (quality in Quality.entries) {
            val canvases = Aspect.entries.map { triptychCanvasSize(it, quality) }
            assertEquals(
                "${quality.name} 下四档成品尺寸必须有 4 个互不相同的值，实际是 $canvases",
                Aspect.entries.size,
                canvases.distinct().size,
            )
        }
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

    /**
     * 画布就是**同一个 [CellSize] 纵向重复 N 格**，不多不少——三格之间没有缝。
     *
     * 名字说的就是这里断言的：`格数为 1 的画布 == cellSize` 本身（两个函数必须给
     * 同一个矩形，否则「每格」与「画布」是两套尺寸），且堆 N 格只把高乘 N、宽不动。
     *
     * 上一版这里写的是奇偶断言，那是 `LiveTriptychAspectLabelTest` 的
     * `现有八个档位组合的输出两条边都是偶数` 已经钉过的同一件事，
     * 而「由同一个 cellSize 推导」它一个字也没断到。
     *
     * 「宽不随格数变」与「高按整数倍」这两半合起来才是竖排无缝的定义：
     * 谁往拼接里塞一条 padding，这里会红，而它在肉眼扫 UI 时最容易看漏。
     */
    @Test
    fun `每格的像素由同一个 cellSize 推导，各档一致`() {
        for (aspect in Aspect.entries) {
            for (q in Quality.entries) {
                val cell = aspect.cellSize(q)
                val one = triptychCanvasSize(aspect, q, cells = 1)
                assertEquals("${aspect.name}/${q.name} 一格时的画布就是 cellSize", cell, one)
                for (cells in 2..3) {
                    val stacked = triptychCanvasSize(aspect, q, cells)
                    assertEquals(
                        "${aspect.name}/${q.name} 堆 $cells 格：宽不该跟着格数走",
                        one.width, stacked.width,
                    )
                    assertEquals(
                        "${aspect.name}/${q.name} 堆 $cells 格：高是一格的高 × 格数",
                        one.height * cells, stacked.height,
                    )
                }
            }
        }
    }

    /**
     * 预估按**像素**推导，不按 [Quality.bitrate] 推导——理由见 `estimateTriptychBytes`
     * 的 KDoc（码率根本没传到编码器，`trimVideo` 没有 `bitrate` 参数，Task 7 才加）。
     *
     * 于是「低档更小」只在**该档两档尺寸不同**时成立：
     * 16:9 / 1:1 / 4:5 在 720p 下格子真的变小，27:16 两档都是 1080×640（短边 640
     * 低于 720 的上限，`cellSize` 只缩不放），它的两档预估**必然相等**。
     * 那不是漏了一条，是那个数此刻唯一诚实的回答：换档没改掉任何一帧的像素，
     * 就不该宣称体积变了。旧的按 6Mbps/12Mbps 推导的写法在这里会红——
     * 它报的是一个编码器从未被告知的值。
     */
    @Test
    fun `尺寸变小的档位预估才变小而 27 比 16 两档相等`() {
        val durations = listOf(1500L, 2000L, 1200L)
        for (aspect in listOf(Aspect.R16_9, Aspect.R1_1, Aspect.R4_5)) {
            assertTrue(
                "${aspect.name} 的 720p 预估应小于 1080p",
                estimateTriptychBytes(durations, aspect, P720) <
                    estimateTriptychBytes(durations, aspect, P1080),
            )
        }
        // 两档像素相同 → 预估精确相等（走的是同一条 Float 算式，可比精确值）
        assertEquals(
            estimateTriptychBytes(durations, Aspect.R27_16, P720),
            estimateTriptychBytes(durations, Aspect.R27_16, P1080),
        )
        // 空输入下两档恒相等：视频部分是 0，只剩那个 3MiB 常数。
        // 前提本身也得钉住——把上面那条的断言挪到空列表上它会立刻红，
        // 而红的原因不是代码错了，是前提没了
        assertEquals(
            estimateTriptychBytes(emptyList(), Aspect.R16_9, P720),
            estimateTriptychBytes(emptyList(), Aspect.R16_9, P1080),
        )
    }

    @Test
    fun `体积随时长单调增加`() {
        val short = estimateTriptychBytes(listOf(1000L, 1000L, 1000L), Aspect.R16_9, P1080)
        val long = estimateTriptychBytes(listOf(3000L, 3000L, 3000L), Aspect.R16_9, P1080)
        assertTrue("$long 应大于 $short", long > short)
    }

    /** 空输入不能返回 0——那会让界面显示「0.0 MB」，像是没生成内容 */
    @Test
    fun `没有片段时仍给出拼图那一份的体积`() {
        assertTrue(estimateTriptychBytes(emptyList(), Aspect.R16_9, P1080) > 0L)
        // 精确到那个常数本身：3L * 1024 * 1024，不是 3_000_000
        assertEquals(3L * 1024 * 1024, estimateTriptychBytes(emptyList(), Aspect.R16_9, P1080))
    }

    /** 负时长不能把预估拉成负数（`coerceAtLeast(0L)` 就在函数里，钉住它没被摘掉） */
    @Test
    fun `负时长按零计`() {
        assertEquals(
            3L * 1024 * 1024,
            estimateTriptychBytes(listOf(-5_000L), Aspect.R16_9, P1080)
        )
    }

    // ── 槽位的初始选段：短素材那一支必须有名字 ────────────────────

    /**
     * 素材 ≥ [ClipMath.MIN_CLIP_MS] 时，初始选段是「从头取整个素材，最多到
     * [ClipMath.MAX_CLIP_MS]」，也就是 `clampStart(0, …)` + `clampEnd(…)` 的结果。
     *
     * 10s 素材取不到 10s：上限由 `clampEnd` 的 `startMs + MAX_CLIP_MS` 承担，
     * 与导出侧 `VideoTrimmer.trim` 的逐字同一条，所以初始态就不是一个导出时会
     * 悄悄改短的选段。
     *
     * **`coverMs` 那一位是 `null`，不是 `0L`**：0 是三拼候选条带最左那张的合法时刻，
     * 拿它当「未重选」的哨兵，点第一张候选帧就会被读成一次「恢复原图」。
     * 初始态因此写成 `ClipSpec(0, 3000, null)`——那一格画面走原始静态图。
     */
    @Test
    fun `素材够长时初始选段是整段且不超过上限`() {
        // 期望值直接用 ClipSpec（data class，四字段齐全），不用 Pair 或自定义
        // 壳类去比——那两种写法编译都过、运行时恒不相等
        assertEquals(ClipSpec(0L, 3_000L, null), initialClipSpec(3_000L))
        assertEquals(ClipSpec(0L, ClipMath.MAX_CLIP_MS, null), initialClipSpec(10_000L))
        assertEquals(ClipSpec(0L, ClipMath.MIN_CLIP_MS, null), initialClipSpec(1_500L))
        assertNull(initialClipSpec(3_000L).coverMs)
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
        // 退化选段也不改封面那一支：仍是「未重选」，不是替用户选了第 0 帧
        assertNull(spec.coverMs)

        // 时长 0 的元数据也不能产出倒置区间（0 也不小于下限，退化到 0）
        val empty = initialClipSpec(0L)
        assertEquals(0L, empty.startMs)
        assertEquals(0L, empty.endMs)
        assertTrue(empty.endMs >= empty.startMs)
        assertNull(empty.coverMs)
    }
}
