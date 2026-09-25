package com.imagedge.camera.navigation

import com.imagedge.camera.data.transfer.TransferBarContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 底部条位互斥验收（批次 C）：三条绝不叠加、无任务不弹条、子页不浮条
 * </pre>
 */
class BottomLayoutTest {

    private fun bar(active: Int = 0, unviewedFailures: Int = 0) = TransferBarContent(
        batchId = 1L,
        activeCount = active,
        unviewedFailureCount = unviewedFailures
    )

    @Test
    fun `nav alone when nothing is transferring`() {
        val layout = bottomLayoutOf(BottomSlotOwner.Navigation, miniBar = null, onTabPage = true)

        assertTrue(layout.showNav)
        assertFalse("没有任务也没有未看的失败，就不该有第二条", layout.showMiniBar)
    }

    @Test
    fun `mini bar rides above the nav while a transfer is running`() {
        // 设计 §8.3：其他 Tab 是「TransferMiniBar ＋ 导航」两层，不是二选一
        val layout = bottomLayoutOf(BottomSlotOwner.Navigation, bar(active = 2), onTabPage = true)

        assertTrue(layout.showNav)
        assertTrue(layout.showMiniBar)
    }

    @Test
    fun `selection mode clears both so three bars never stack`() {
        // 这一条就是「导航 + 任务条 + 保存按钮」三层叠加的防线：
        // 页面占位时根上不画任何东西，选择条自己独占底部
        val layout = bottomLayoutOf(
            BottomSlotOwner.SelectionBar,
            bar(active = 3, unviewedFailures = 1),
            onTabPage = true
        )

        assertFalse(layout.showNav)
        assertFalse(layout.showMiniBar)
    }

    @Test
    fun `sub pages get no floating bar because nothing reserves their space`() {
        // 传输页、编辑器、大图页都在此列：浮一条上去就是压住最后一行
        val layout = bottomLayoutOf(BottomSlotOwner.Navigation, bar(active = 1), onTabPage = false)

        assertEquals(BottomLayout.Empty, layout)
    }

    @Test
    fun `a page only gets its own claim back, never someone else's`() {
        val host = BottomSlotHost()
        host.claim(BottomSlotOwner.SelectionBar)
        // 另一个持有者来交还时不能把别人的位置清掉（离页顺序不保证）
        host.release(BottomSlotOwner.Navigation)
        assertEquals(BottomSlotOwner.SelectionBar, host.owner.value)

        host.release(BottomSlotOwner.SelectionBar)
        assertEquals(BottomSlotOwner.Navigation, host.owner.value)
    }
}
