package com.imagedge.camera.feature.edit.triptych

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 异步结果按身份写回——「按下标写回」那一次竞态在这里能证伪
 * </pre>
 */
class TriptychSlotIdentityTest {

    /**
     * 替身格子。被测函数 [updatedBySlotId] 是泛型的，而生产那一头的
     * `LiveTriptychViewModel.TriptychSlot` 揣着 `android.net.Uri`——本模块没有 Robolectric，
     * `android.jar` 里 `Uri.parse` 是「not mocked」，JVM 单测根本造不出一格。
     * 泛型不是为了抽象，是为了把「异步结果落在哪一格上」这件事从 Android 依赖里摘出来。
     */
    private data class Cell(
        val id: Long,
        val thumbs: List<Long> = emptyList(),
        val loading: Boolean = false,
    )

    private val idOf: (Cell) -> Long = Cell::id

    /** 与 ViewModel 的 `swapSlots` 同一条算术：换位置、不换身份 */
    private fun swapped(items: List<Cell>, i: Int, j: Int): List<Cell> =
        items.toMutableList().apply { val tmp = this[j]; this[j] = this[i]; this[i] = tmp }

    /**
     * 缺陷本体：抽帧挂在 IO 线程上要挂起才回，其间用户点一次「上移」。
     *
     * 三格身份 0/1/2 与它们的初始下标**恰好相同**（[LiveTriptychViewModel] 按解析顺序发号），
     * 所以「按发起那一刻的下标写回」在实现上就等价于把 `slotId` 当位置用——
     * 这一条把两者区分开的正是这次交换：写回必须落在**身份 1 那一格**，
     * 而交换之后它站在下标 0 上。
     *
     * 按下标写回的后果有两半，两条都断言了：错的那一格拿到不属于它的九帧，
     * 而发起那一格停在 `loading = true` 且 `thumbs` 为空——Screen 的 `FilmstripArea`
     * 先查 loading、`loadCoverThumbs` 又在 loading 时早退，那一格从此永久转圈。
     */
    @Test
    fun `在途的写回按身份落位，中途交换过顺序也不串格`() {
        val frames = listOf(0L, 333L, 666L, 999L, 1332L, 1665L, 1998L, 2331L, 2664L)
        val slots = listOf(Cell(id = 0L), Cell(id = 1L), Cell(id = 2L))

        // 1) 第 2 格（发起时在下标 1、身份也是 1）开始装载
        val started = updatedBySlotId(slots, idOf, itemId = 1L) { it.copy(loading = true) }
        assertEquals(listOf(Cell(0L), Cell(1L, loading = true), Cell(2L)), started)

        // 2) 抽帧还在路上，用户点了「上移」：身份 1 那一格挪到了下标 0
        val moved = swapped(started, 1, 0)
        assertEquals(listOf(Cell(1L, loading = true), Cell(0L), Cell(2L)), moved)
        assertEquals(0, moved.indexOfFirst { it.id == 1L })

        // 3) 九帧回来。按身份写回
        val done = updatedBySlotId(moved, idOf, itemId = 1L) {
            it.copy(thumbs = frames, loading = false)
        }

        // 落在身份 1 那一格上，而它此刻在下标 0
        val landed = done.single { it.id == 1L }
        assertEquals(frames, landed.thumbs)
        assertFalse(landed.loading)
        // 另外两格一个字没变：谁的 thumbs 也没被冒领
        assertEquals(Cell(0L), done.single { it.id == 0L })
        assertEquals(Cell(2L), done.single { it.id == 2L })
        // 没有任何一格留在转圈态——这一条就是「永久 spinner」的反面
        assertFalse(done.any { it.loading })
    }

    /**
     * 身份找不到时**原样返回**：旧批次的在途结果不能写进新批次。
     *
     * `startOver` 不清 `nextSlotId`（发号单调、永不复用），所以交换之后又被整批清掉的
     * 那一格再也找不到同名身份——这时正确行为是什么也不改，而不是「就近写给别人」，
     * 也不是抛（列表已经空了，按下标写回那一路会走到 `mapIndexed` 的空列表上，
     * 什么都不写但也什么都不说）。
     */
    @Test
    fun `身份找不到时一个格子也不改`() {
        val slots = listOf(Cell(id = 0L), Cell(id = 1L))
        val after = updatedBySlotId(slots, idOf, itemId = 7L) {
            it.copy(thumbs = listOf(9L), loading = false)
        }
        assertSame(slots, after)
        assertTrueNoThumbs(after)

        val empty = updatedBySlotId(emptyList(), idOf, itemId = 0L) { it.copy(loading = true) }
        assertEquals(0, empty.size)
    }

    /** 命中多个同身份时只改第一个：发号唯一，这条是给将来防身用的 */
    @Test
    fun `同一身份只写一处`() {
        val slots = listOf(Cell(id = 3L), Cell(id = 3L))
        val after = updatedBySlotId(slots, idOf, itemId = 3L) { it.copy(loading = true) }
        assertEquals(1, after.count { it.loading })
    }

    private fun assertTrueNoThumbs(cells: List<Cell>) {
        assertFalse(cells.any { it.thumbs.isNotEmpty() || it.loading })
    }
}
