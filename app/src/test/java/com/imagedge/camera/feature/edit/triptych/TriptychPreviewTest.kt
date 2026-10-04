package com.imagedge.camera.feature.edit.triptych

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 常驻预览的两条纯算术——重建期间画哪一张、这一块占多高
 * </pre>
 */
class TriptychPreviewTest {

    /** 典型竖屏的内容宽：360dp 的屏减去 `EditorFrame` 左右各 16dp（`Spacing.L`） */
    private val trackDp = 328f

    // ── 留帧：拖拽期间不许什么都不画 ────────────────────────────

    /**
     * `onDrag` 每移动一个像素就是一次 `setSpec` → `invalidatePreview`，而它**同步把
     * [LiveTriptychViewModel.UiState.previewBitmap] 置空**。旧版组件在这一刻什么都不画，
     * 整块只剩一枚 22dp 的菊花——「预览在拖拽期间全黑」与「下面上跳约 300dp」是同一件事
     * （320 − 22 = 298dp，那 320 由下面那组高度测试逐档算过）。
     *
     * 现在 [previewToShow] 在三档上各钉一条，**顺序也算**：
     * - 新帧回来了就画新的（留帧绝不能把旧结果说成当前结果——输出从来不能错）；
     * - 新帧没到而 `loading` 为真 → 画上一帧；
     * - 新帧没到而**构建已停** → `null`：ready 分支里能落进「没有帧也没在重建」的只有
     *   `startPreviewBuild` 收尾那次 `getOrNull()`（拼图抛异常），这时挂着上一帧
     *   就是在把旧画面说成当前结果。
     */
    @Test
    fun `重建期间继续显示上一帧而构建停了就不画`() {
        assertEquals("new", previewToShow("new", "old", loading = true))
        assertEquals("new", previewToShow("new", "old", loading = false))
        assertEquals("old", previewToShow(null, "old", loading = true))
        assertNull(previewToShow<String>(null, "old", loading = false))
        assertNull(previewToShow<String>(null, null, loading = true))
    }

    // ── 高度：预留必须与「有没有画面」无关 ──────────────────────

    /**
     * 满宽高度 = 轨道宽 × 画布高 ÷ 画布宽，超过预算就按预算收。
     *
     * 三个数都是**算出来的**：328 × 3240 ÷ 1920 = 553.5 → 收到 320；
     * 328 × 960 ÷ 1920 = 164（没收住）；100 × 1152 ÷ 1920 = 60。
     * 后两条是防「把 minOf 摘掉」和「把比例写反」的——只钉 553.5→320 那一条的话，
     * 有人把函数改成「恒返回预算」它也是绿的。
     */
    @Test
    fun `预留高度按画布比例折算并被预算收住`() {
        assertEquals(320f, previewReservedHeightDp(trackDp, CellSize(1920, 3240)), 0.001f)
        assertEquals(164f, previewReservedHeightDp(trackDp, CellSize(1920, 960)), 0.001f)
        assertEquals(60f, previewReservedHeightDp(100f, CellSize(1920, 1152)), 0.001f)
    }

    /**
     * 八个「比例 × 画质」组合在典型竖屏上**全部顶到 320dp 的预算**，
     * 所以那一屏的预留高度是一个常数：拖拽期间不再有 298dp 的塌-弹。
     *
     * 这不是巧合也不是手抄——`triptychCanvasSize` 给的画布都是竖排的
     * （高/宽 ≥ 1.6875，最小的就是 16:9 档的 3240÷1920），而 328 × 1.6875 = 553.5 > 320。
     * 期望值让函数自己算满宽高度再与预算取小，比的是「结果恰等于预算」这个事实本身。
     */
    @Test
    fun `八个档位组合在典型竖屏上都顶到高度预算`() {
        for (aspect in Aspect.entries) {
            for (quality in Quality.entries) {
                val canvas = triptychCanvasSize(aspect, quality)
                val uncapped = trackDp * canvas.height / canvas.width
                assertTrue(
                    "${aspect.name}/${quality.name} 画布 $canvas 的满宽高度 $uncapped 应超过预算",
                    uncapped > 320f,
                )
                assertEquals(
                    "${aspect.name}/${quality.name}",
                    320f,
                    previewReservedHeightDp(trackDp, canvas),
                    0.001f,
                )
            }
        }
    }

    /** 轨道越宽预留越高，到预算为止——宽度变了高度必须跟着变，否则就是硬编码 */
    @Test
    fun `预留高度随轨道宽单调增加直到上限`() {
        val canvas = CellSize(1920, 960) // 高:宽 = 0.5，永远够不着预算，用来看单调性
        val low = previewReservedHeightDp(100f, canvas)
        val high = previewReservedHeightDp(200f, canvas)
        assertEquals(50f, low, 0.001f)
        assertEquals(100f, high, 0.001f)
        assertTrue(low < high)
        // 顶到预算后不再增长
        assertEquals(320f, previewReservedHeightDp(4000f, CellSize(1920, 3240)), 0.001f)
        assertEquals(320f, previewReservedHeightDp(8000f, CellSize(1920, 3240)), 0.001f)
    }

    /**
     * 退化输入不许产出 NaN、负高度或无限高：`maxWidth` 在未约束父级里就是 `Dp.Infinity`，
     * 而 `Dp.Unspecified.value` 是 `NaN`（`ClipFilmstrip` 那边踩过同一课，见
     * `filmstripHandleCount` 的注释）。无限宽收成预算、NaN 与非正宽收成 0。
     */
    @Test
    fun `退化宽度与退化画布都不产出非法高度`() {
        assertEquals(0f, previewReservedHeightDp(Float.NaN, CellSize(1920, 3240)), 0f)
        assertEquals(0f, previewReservedHeightDp(0f, CellSize(1920, 3240)), 0f)
        assertEquals(0f, previewReservedHeightDp(-328f, CellSize(1920, 3240)), 0f)
        assertEquals(320f, previewReservedHeightDp(Float.POSITIVE_INFINITY, CellSize(1920, 3240)), 0f)
        assertEquals(0f, previewReservedHeightDp(trackDp, CellSize(0, 3240)), 0f)
        assertEquals(0f, previewReservedHeightDp(trackDp, CellSize(1920, 0)), 0f)
        assertEquals(0f, previewReservedHeightDp(trackDp, CellSize(-1920, -3240)), 0f)
    }
}
