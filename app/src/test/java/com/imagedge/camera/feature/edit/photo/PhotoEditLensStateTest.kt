package com.imagedge.camera.feature.edit.photo

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.HistoryList
import com.imagedge.camera.image.LensCorrectionParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜头校正**不进配方、不进历史**，而它影响预览与导出。这份不对称的代价必须被钉住，
 * 否则它会退化成一句注释里的「理由」而不是代码里的事实。
 *
 * 两条要钉的：
 * - [PhotoEditState.hasEdits] 算上了它。不算的话，只调了镜头校正的会话里「重置」是灰的、
 *   「未存盘」标记不亮，而导出的照片确实变形了——界面说没改，成品说改了。
 * - [resetStateOf] 清掉了它。不清就是「重置」之后预览仍然变形，而按钮刚把界面标回没改动。
 *
 * 这两条都无法从配方推出：`lens` 在配方里根本没有对应物，而只看配方的重置测试会照绿。
 */
class PhotoEditLensStateTest {

    private fun stateWithLens() = PhotoEditState(lens = LensCorrectionParams(k1 = LensCorrectionParams.K1_LIMIT))

    @Test
    fun `a fresh session has no unsaved edits`() {
        assertFalse(PhotoEditState().hasEdits)
    }

    @Test
    fun `lens alone counts as an unsaved edit`() {
        assertTrue("只调了镜头校正也必须算「有改动」", stateWithLens().hasEdits)
    }

    @Test
    fun `reset clears the lens correction`() {
        val reset = resetStateOf(stateWithLens())
        assertEquals("重置必须把镜头校正清回恒等", LensCorrectionParams(), reset.lens)
        assertTrue("清完之后不该再有未存盘改动", reset.lens.isIdentity)
    }

    @Test
    fun `reset clears the recipe edits too`() {
        val edited = PhotoEditState(
            recipe = EditRecipe.EMPTY.with(EditStep.Rotate(90f)),
            lens = LensCorrectionParams(k1 = LensCorrectionParams.K1_LIMIT),
        )
        val reset = resetStateOf(edited)
        assertEquals("重置必须把配方清空", EditRecipe.EMPTY, reset.recipe)
        assertFalse(reset.hasEdits)
    }

    @Test
    fun `reset is itself one undoable step`() {
        // 重置走 record 而不是直接换配方，否则用户按了撤销、回不到「改之前」，
        // 而那正是这个按钮在界面上承诺的（canUndo 决定撤销键亮不亮）。
        // current 是**当前**那格（重置之后），退一步才拿回重置之前那份配方
        val edited = EditRecipe.EMPTY.with(EditStep.Rotate(90f))
        val reset = resetStateOf(
            PhotoEditState(recipe = edited, history = seededHistoryOf(edited))
        )

        assertTrue("重置之后撤销键必须是亮的", reset.history.canUndo)
        assertEquals("重置后当前那格就是空配方", EditRecipe.EMPTY, reset.history.current)
        assertEquals(
            "撤销应当退回重置之前那一格配方",
            edited,
            reset.history.undo().current,
        )
    }

    @Test
    fun `an untouched session needs no history`() {
        val fresh = PhotoEditState(history = HistoryList<EditRecipe>())
        assertFalse(resetStateOf(fresh).history.canUndo)
    }
}