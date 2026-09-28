package com.imagedge.camera.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销 = 不可变快照列表上移动一个游标，不是命令模式。
 * （形状取自 Gallery2 的 HistoryManager + androidx 的 UndoManager。）
 *
 * 这里钉得住的是游标两端的界限、截断重做尾、上限淘汰不动当前项、同值不重复入档、
 * 同值与截断的先后（同值提交连尾巴都不动）、可空条目类型的第一条、构造即校验的游标范围与正上限。
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
    fun `recording the current value keeps the redo tail alive`() {
        // 去重判在截断之前：与当前同值的提交算「这一步没发生」，不是「改过历史」，
        // 所以尾巴上被撤销掉的 c 不该跟着一起丢。
        val history = HistoryList<String>().record("a").record("b").record("c")
            .undo()          // 停在 b，尾巴上还有 c
            .record("b")

        assertEquals("同值提交不得截断重做尾", 3, history.size)
        assertTrue("尾巴上的 c 必须还点得动", history.canRedo)
        assertEquals("c", history.redo().current)
    }

    @Test
    fun `the first null entry of a nullable history is recorded, not deduped away`() {
        // 空历史的 current 与「当前项恰好是 null」在 current 上读起来一模一样，
        // 去重只看 current 的话，可空条目类型的第一条永远记不进去且一声不响。
        val history = HistoryList<String?>().record(null)

        assertEquals("第一条 null 必须真的入档", 1, history.size)
        assertNull(history.current)   // 这一句两种情况下都是 null，分辨得了的是下面两条
        // 入档之后同值才该被去重；换个值就接着记——这两条一起证明游标确实落在第 0 项
        assertEquals(1, history.record(null).size)
        assertEquals(2, history.record("x").size)
    }

    @Test
    fun `constructor refuses a cursor past the end of the entries`() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryList<String>(capacity = 8, entries = listOf("a"), cursor = 5)
        }
    }

    @Test
    fun `constructor refuses a cursor before the empty slot`() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryList<String>(capacity = 8, entries = listOf("a"), cursor = -2)
        }
    }

    @Test
    fun `constructor refuses a cursor into an empty entry list`() {
        // 空列表的合法游标只有 -1，这是「空历史就是 cursor = -1」那条不变量的另一面：
        // 方法们全按游标在列表内来写，越界形状不该靠注释提醒调用方别去构造。
        assertThrows(IllegalArgumentException::class.java) {
            HistoryList<String>(capacity = 8, entries = emptyList(), cursor = 0)
        }
    }

    @Test
    fun `constructor refuses a capacity that cannot hold one entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            HistoryList<String>(capacity = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryList<String>(capacity = -1)
        }
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
