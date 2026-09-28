package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销 = 不可变快照列表上移动一个游标，不是命令模式。
 * （形状取自 Gallery2 的 HistoryManager + androidx 的 UndoManager。）
 *
 * 这里钉得住的是游标两端的界限、截断重做尾、上限淘汰不动当前项、同值不重复入档。
 * 参考实现那两处坑——共享可变快照、无上限的位图缓存——类型拦不住（条目类型 `T` 由调用方给，
 * 本类只存引用），所以它们不是下面的用例，而是给下一个使用者的约束：放进历史的必须是纯值。
 */
class HistoryListTest {

    @Test
    fun `a fresh history is undoable back to its first entry but not past it`() {
        val history = HistoryList<String>(capacity = 8).record("a").record("b")

        assertEquals("b", history.current)
        assertTrue(history.canUndo)
        assertEquals("a", history.undo().current)
        assertFalse("最旧一条之后不能再退", history.undo().undo().canUndo)
        assertEquals("退到头是停在最旧那条，不是越过它", "a", history.undo().undo().current)
    }

    @Test
    fun `redo returns exactly what undo took away`() {
        val history = HistoryList<String>().record("a").record("b").record("c")
        val undone = history.undo().undo()

        assertEquals("a", undone.current)
        assertTrue(undone.canRedo)
        assertEquals("b", undone.redo().current)
        assertEquals("c", undone.redo().redo().current)
        assertFalse(undone.redo().redo().canRedo)
    }

    @Test
    fun `recording after undo drops the redo tail`() {
        val history = HistoryList<String>().record("a").record("b").record("c")
            .undo()          // 停在 b
            .record("d")

        assertEquals("d", history.current)
        assertFalse("退回去之后新记录必须截断重做尾", history.canRedo)
        assertEquals("截断后只剩 a b d", 3, history.size)
    }

    @Test
    fun `capacity evicts the oldest entry, never the current one`() {
        var history = HistoryList<Int>(capacity = 3)
        repeat(5) { history = history.record(it) }

        assertEquals("上限生效", 3, history.size)
        assertEquals(4, history.current)
        // 淘汰后游标重新锚在保留列表的末尾，canUndo 只看游标自己的位置（不拿列表长度去减），
        // 所以被淘汰掉的 0、1 就是回不去——这正是「上限」的含义，游标也不会被指到列表外面
        assertTrue(history.canUndo)
        assertEquals(3, history.undo().current)
        assertEquals(2, history.undo().undo().current)
        assertFalse("退到本轮保留的最旧一条为止", history.undo().undo().undo().canUndo)
    }

    @Test
    fun `recording an equal item is a no-op so a slider nudge does not eat slots`() {
        val history = HistoryList<String>().record("a").record("a")

        assertEquals("同值重复不入历史", 1, history.size)
        assertFalse(history.canUndo)
    }

    @Test
    fun `an empty history answers without throwing`() {
        val history = HistoryList<String>()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.current)
        assertEquals(history, history.undo())
        assertEquals(history, history.redo())
    }
}
