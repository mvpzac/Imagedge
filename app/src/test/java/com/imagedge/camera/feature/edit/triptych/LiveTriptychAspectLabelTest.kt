package com.imagedge.camera.feature.edit.triptych

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 三拼档位标签的算术，比例×画质推导出的格子像素，以及偶数护栏 evenFloor
 * </pre>
 */
class LiveTriptychAspectLabelTest {

    /**
     * 成品 = 每格纵向堆叠 3 格（见 `buildTriptychBitmap` 的 `cellH * slots.size`）。
     *
     * 这条钉的是**标签与画布一致**，不是标签好不好看。曾经有过一次判断是
     * 「每格 16:9 堆三格就是 9:16」，实算是 16:27——差 5%，而 9:16 是 Story/Reels
     * 的硬规格，差这一点就得二次裁切，那一档于是并不能直接用。
     */
    @Test
    fun `label states the cell ratio and the stacked canvas ratio together`() {
        assertEquals("每格 16:9 → 整体 16:27", Aspect.R16_9.label(3))
        assertEquals("每格 1:1 → 整体 1:3", Aspect.R1_1.label(3))
        assertEquals("每格 4:5 → 整体 4:15", Aspect.R4_5.label(3))
        assertEquals("每格 27:16 → 整体 9:16", Aspect.R27_16.label(3))
    }

    @Test
    fun `the story cell is the only one whose stacked canvas is exactly 9 by 16`() {
        val stories = Aspect.entries.filter { it.ratio / 3f == 9f / 16f }
        assertEquals(
            "必须有且只有一档能直接产出 9:16 成品，否则「发故事」这一档是虚的",
            listOf(Aspect.R27_16),
            stories
        )
    }

    // ── 比例 × 画质 → 格子像素 ──────────────────────────────────

    // 期望值写成 CellSize 而不是「1920 to 1080」这样的对子：Pair 与 CellSize 是
    // 互不相干的两种类型，JUnit 走的是 assertEquals(Object, Object)，
    // 编译能过、运行时恒不相等——那样的断言钉不住任何东西
    @Test
    fun `1080p 档位下每格的像素与规格一致`() {
        assertEquals(CellSize(1920, 1080), Aspect.R16_9.cellSize(Quality.P1080))
        assertEquals(CellSize(1080, 1080), Aspect.R1_1.cellSize(Quality.P1080))
        assertEquals(CellSize(1080, 1350), Aspect.R4_5.cellSize(Quality.P1080))
        assertEquals(CellSize(1080, 640), Aspect.R27_16.cellSize(Quality.P1080))
    }

    @Test
    fun `720p 档位等比缩小且不放大`() {
        assertEquals(CellSize(1280, 720), Aspect.R16_9.cellSize(Quality.P720))
        assertEquals(CellSize(720, 720), Aspect.R1_1.cellSize(Quality.P720))
        assertEquals(CellSize(720, 900), Aspect.R4_5.cellSize(Quality.P720))
        // 27:16 的短边是 640，本来就小于 720 的上限，所以**不缩**
        assertEquals(CellSize(1080, 640), Aspect.R27_16.cellSize(Quality.P720))
    }

    /**
     * 这一条钉的是**当前这 8 个输出的取值事实**：4 个比例 × 2 个画质，两条边都是偶数。
     *
     * 它**不覆盖 `evenFloor`**——经 [cellSize] 这条路根本触发不了取偶（现有参考尺寸与
     * 缩放结果本来就全是偶数），把 `evenFloor` 整个删掉这条照样绿。
     * `evenFloor` 本身由下面 `evenFloor 把奇数向下取到偶数` 那条直接喂奇数来钉。
     *
     * 留下这条仍有价值：它会在「有人改坏参考尺寸 / 加了新档位导致某个输出变成奇数」时报警。
     */
    @Test
    fun `现有八个档位组合的输出两条边都是偶数`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val size = aspect.cellSize(quality)
                assertEquals("${aspect.name}/${quality.name} 宽必须是偶数", 0, size.width % 2)
                assertEquals("${aspect.name}/${quality.name} 高必须是偶数", 0, size.height % 2)
            }
        }
    }

    // ── evenFloor：奇数护栏本身 ──────────────────────────────────
    //
    // 直接调 internal 的 evenFloor，而不是绕道 cellSize——8 个现有组合都是偶数，
    // 绕道进去测不到任何东西（那就是这条测试被提出来的原因）。
    //
    // 输入一律取**任意奇数**，不取「720 × 27/16 = 1215」那个数：1215 只在**放大**规则
    // （短边硬拉到 720）下才出现，而现行规则是 `min(1f, cap / refShort)`，27:16 在
    // 720p 下 shortSide=640 < cap → scale 取 1，根本不缩，1215 不可达。把它写进测试
    // 会让下一个读者以为那是真实场景。

    @Test
    fun `evenFloor 把奇数向下取到偶数`() {
        assertEquals(1200, evenFloor(1201))
        assertEquals(1080, evenFloor(1081))
        assertEquals(640, evenFloor(641))
        assertEquals(2, evenFloor(3))
        // 偶数原样返回，不做任何上取
        assertEquals(1080, evenFloor(1080))
        assertEquals(640, evenFloor(640))
        // 定义域边界：1 落到 0。函数没有下限保护（没加 coerceAtLeast(2)），
        // 这里把**当前行为**钉住，免得有人以为它已经保底 2
        assertEquals(0, evenFloor(1))
    }

    @Test
    fun `evenFloor 对非负输入恒为非负偶数且不超过输入`() {
        // 0..2001 覆盖奇偶两端，并跨过 640 / 1080 / 1201 这些真实会出现的量级
        for (v in 0..2001) {
            val got = evenFloor(v)
            assertTrue("evenFloor($v)=$got 不是偶数", got % 2 == 0)
            assertTrue("evenFloor($v)=$got 出现负数", got >= 0)
            assertTrue("evenFloor($v)=$got 比输入还大", got <= v)
        }
    }

    @Test
    fun `短边不超过该档位的上限`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val size = aspect.cellSize(quality)
                assertTrue(
                    "${aspect.name}/${quality.name} 短边 ${minOf(size.width, size.height)} 超过 ${quality.shortSideCap}",
                    minOf(size.width, size.height) <= quality.shortSideCap
                )
            }
        }
    }

    @Test
    fun `推导出的格子比例与档位声明的比例一致`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val size = aspect.cellSize(quality)
                assertEquals(
                    "${aspect.name}/${quality.name} 取偶后比例会有微小偏差，但不得偏差超过 1%",
                    aspect.ratio.toDouble(),
                    size.width.toDouble() / size.height,
                    aspect.ratio * 0.01
                )
            }
        }
    }
}