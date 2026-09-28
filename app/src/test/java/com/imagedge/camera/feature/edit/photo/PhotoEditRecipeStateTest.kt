package com.imagedge.camera.feature.edit.photo

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.HistoryList
import com.imagedge.camera.image.NormRect
import com.imagedge.camera.image.strengthOrDefault
import com.imagedge.camera.lut.ColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「UI 那组编辑字段变成派生属性」这件事的等价性风险都在这，分三层：
 * - **折叠**：selectedKey / strength / adjust / quarterTurns / flipHorizontal / flipVertical /
 *   straighten 与两个 has*，字段名、默认值与边界（度数超出圈数归一到 0..3、两个翻转方向各读
 *   各的、没选滤镜也要记住强度）必须与 alpha08 直接读那几个字段一致；
 * - **转发**：[PhotoEditState] 上那九个同名属性真的转发折叠结果，并把自己那份 live crop 一起
 *   交给折叠（alpha08 的 hasGeometryEdits 里写着「!crop.isFull」）；
 * - **写入**：同一槽位的第二次写是替换不是叠加（滤镜与调色各一份），旋转度数在那**一份** rotate
 *   槽位上累加，关掉翻转只删那一个方向，换照片时历史里已经播下「刚载入」那一格。
 *
 * `rotate()` / `toggleFlip*()` / `loadPicked()` 都挂在 ViewModel 上（构造要 Context，重渲染走
 * viewModelScope，而 :app 的测试依赖只有 junit4，没有 mockk 与 Robolectric），JVM 里起不来，
 * 所以把它们的纯算术与纯构造抽成 [rotatedRecipe] / [toggledFlip] / [seededHistoryOf] 由这里钉。
 * 这三处都属于「写错了界面不报错、只是行为不对」：90° 不累加就是按了没反应，按类型删翻转会把
 * 另一个方向一起清掉，历史没播种则第一次改动根本退不回去。
 * （strength 的**输入**钳制在 `setStrength` 里，那要 ViewModel 实例，不在本文件钉。）
 *
 * 这里**钉不住**的三件事：滑条松手才提交（onValueChangeFinished 的接线）、撤销/重做入口的
 * enabled、在途渲染不采纳过期配方（协程与互斥的时序，JVM 上模拟不出来）——这三条只能靠代码
 * 审查与真机验收，不假装测过。
 */
class PhotoEditRecipeStateTest {

    @Test
    fun `empty recipe reads as untouched`() {
        val fields = EditRecipe.EMPTY.fields()

        assertEquals(FILTER_NONE, fields.selectedKey)
        assertEquals(80, fields.strength)
        assertEquals(ColorAdjust.NONE, fields.adjust)
        assertEquals(0, fields.quarterTurns)
        assertFalse(fields.flipHorizontal)
        assertFalse(fields.hasEdits)
    }

    @Test
    fun `each geometry step reads back to the field it came from`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Rotate(270f))
            .with(EditStep.Flip(horizontal = true))
            .with(EditStep.Flip(horizontal = false))
            .with(EditStep.Straighten(-6.5f))

        val fields = recipe.fields()

        assertEquals(3, fields.quarterTurns)
        assertTrue(fields.flipHorizontal)
        assertTrue(fields.flipVertical)
        assertEquals(-6.5f, fields.straighten, 0f)
        assertTrue("几何改动也算编辑，离开时要确认", fields.hasEdits)
    }

    @Test
    fun `strength alone is not an edit but a filter with strength is`() {
        assertFalse(
            EditRecipe.EMPTY.with(EditStep.Lut(FILTER_NONE, 40)).fields().hasEdits
        )
        assertTrue(
            EditRecipe.EMPTY.with(EditStep.Lut("kodak2383", 0)).fields().hasEdits
        )
    }

    @Test
    fun `a nil colour step does not count as an edit`() {
        assertFalse(
            EditRecipe.EMPTY.with(EditStep.Color(ColorAdjust.NONE)).fields().hasEdits
        )
    }

    @Test
    fun `only the flipped direction folds back true`() {
        // 上面那条把两个方向一起开着，所以「两个字段读同一个布尔」这种写法在它下面照样是绿的。
        // 编辑器上是两枚独立的 chip、各写各的方向，折叠也必须各读各的，所以这里分开测。
        val onlyVertical = EditRecipe.EMPTY.with(EditStep.Flip(horizontal = false)).fields()
        assertFalse(onlyVertical.flipHorizontal)
        assertTrue(onlyVertical.flipVertical)

        val onlyHorizontal = EditRecipe.EMPTY.with(EditStep.Flip(horizontal = true)).fields()
        assertTrue(onlyHorizontal.flipHorizontal)
        assertFalse(onlyHorizontal.flipVertical)
    }

    @Test
    fun `the colour step folds back into the adjust the sliders read`() {
        val adjust = ColorAdjust(exposure = 40, saturation = -25)
        val fields = EditRecipe.EMPTY.with(EditStep.Color(adjust)).fields()

        assertEquals("滑条读的就是这份快照，丢了它界面会弹回 0", adjust, fields.adjust)
        assertTrue("调色也算没存盘的改动", fields.hasEdits)
    }

    @Test
    fun `the strength the user slid out survives with no filter chosen`() {
        // :image 的 EditRecipeFoldEquivalenceTest 钉的是 strengthOrDefault 本身；
        // 这里钉的是 fields() 真的走它——写成常量 80 的话每次松手都把强度弹回 80。
        assertEquals(37, EditRecipe.EMPTY.with(EditStep.Lut(FILTER_NONE, 37)).fields().strength)

        val chosen = EditRecipe.EMPTY.with(EditStep.Lut("kodak2383", 62)).fields()
        assertEquals(62, chosen.strength)
        assertEquals("选中的滤镜要原样读回，滤镜条的高亮只看它", "kodak2383", chosen.selectedKey)
    }

    @Test
    fun `rotate degrees outside one turn still read back as zero to three quarters`() {
        // EditRecipe 的构造只校验「身份唯一 + rank 不降」，度数范围不归它管，
        // 手写的列表（预设解码进来就是这个形状）完全可能带 450° 或 -90°。
        // 归一口径必须与 ImagePipeline 一致：450° 就是转了一格，-90° 就是三格。
        assertEquals(1, EditRecipe(listOf(EditStep.Rotate(450f))).fields().quarterTurns)
        assertEquals(3, EditRecipe(listOf(EditStep.Rotate(-90f))).fields().quarterTurns)
    }

    @Test
    fun `two clockwise turns accumulate into the single rotate slot instead of rewriting 90`() {
        val once = rotatedRecipe(EditRecipe.EMPTY, 1)
        val twice = rotatedRecipe(once, 1)

        assertEquals("必须只有一份 rotate", 1, twice.steps.size)
        assertEquals(
            "第二次转的是累加值，不是又把 90° 设了一遍",
            EditStep.Rotate(180f),
            twice.steps.single()
        )
        assertEquals(2, twice.fields().quarterTurns)
        assertEquals(1, rotatedRecipe(twice, -1).fields().quarterTurns)
    }

    @Test
    fun `a full turn leaves no rotate step behind`() {
        var recipe = EditRecipe.EMPTY
        repeat(4) { recipe = rotatedRecipe(recipe, 1) }

        assertEquals(emptyList<EditStep>(), recipe.steps)
        assertEquals(0, recipe.fields().quarterTurns)
        assertFalse("转满一圈等于没转，离开时不该弹确认", recipe.fields().hasGeometryEdits)
    }

    @Test
    fun `a second filter or colour write replaces the slot instead of stacking a stale one`() {
        val slid = EditRecipe.EMPTY.with(EditStep.Lut(FILTER_NONE, 37))
        // selectFilter 写进配方的就是这个式子：换新 key、把滑出来的强度搬过去
        val chosen = slid.with(EditStep.Lut("kodak2383", slid.strengthOrDefault(80)))

        assertEquals("滤镜槽位只有一份（identity 不含 key）", 1, chosen.steps.size)
        assertEquals("kodak2383", chosen.fields().selectedKey)
        assertEquals("强度不能因为换滤镜被重置回 80", 37, chosen.fields().strength)

        // 两次拖动之间曝光也变了，正是滑条在界面上的样子：后一份快照必须整个盖掉前一份
        val first = ColorAdjust(exposure = 40)
        val second = ColorAdjust(exposure = 55, contrast = -20)
        assertEquals(
            "第二次拖动必须盖掉第一次那份快照；读到陈旧那一份时滑条会弹回旧值",
            second,
            EditRecipe.EMPTY.with(EditStep.Color(first)).with(EditStep.Color(second)).fields().adjust
        )
    }

    @Test
    fun `state reads its edit fields out of the recipe plus its own live crop`() {
        val crop = NormRect(left = 0.1f, top = 0.2f, right = 0.8f, bottom = 0.9f)
        val state = PhotoEditState(
            recipe = EditRecipe.EMPTY
                .with(EditStep.Lut("kodak2383", 62))
                .with(EditStep.Color(ColorAdjust(exposure = 40)))
                .with(EditStep.Rotate(90f))
                .with(EditStep.Flip(horizontal = true)),
            crop = crop,
        )

        assertEquals("kodak2383", state.selectedKey)
        assertEquals(62, state.strength)
        assertEquals(ColorAdjust(exposure = 40), state.adjust)
        assertEquals(1, state.quarterTurns)
        assertTrue(state.flipHorizontal)
        assertFalse("另一个方向不许被牵连", state.flipVertical)

        // 裁剪框不进配方（它是用户正在拖的 live 值），但 alpha08 的 hasGeometryEdits 里写着
        // 「!crop.isFull」，所以 state 必须把自己那份 crop 一起交给折叠。
        // 下面那条 cropOnly 才是承重的：上面那份配方自己就有旋转与翻转，几何布尔本来就为真，
        // 写成 `fields()`（丢掉 crop）也照样绿——只有「除了拖框什么都没改」这份状态能认出它丢了。
        assertTrue("拖过裁剪框就算改过：重置按钮与离开确认都看这两个布尔", state.hasGeometryEdits)
        assertTrue(state.hasEdits)

        val cropOnly = PhotoEditState(crop = crop)
        assertTrue("只拖过裁剪框，配方是空的，也必须认出「改过」", cropOnly.hasGeometryEdits)
        assertTrue(cropOnly.hasEdits)
    }

    @Test
    fun `a strength-only state still reads as no edits`() {
        // 折叠侧由上面那条「strength alone is not an edit」钉着；这条钉的是 state 真的转发它。
        // EditorFrame 的 canReset 与 hasEdits 吃的就是 state.hasEdits：只滑过强度就亮起「重置」、
        // 离开时弹确认，都是 alpha08 没有的打扰。
        val state = PhotoEditState(recipe = EditRecipe.EMPTY.with(EditStep.Lut(FILTER_NONE, 25)))

        assertEquals(25, state.strength)
        assertFalse(state.hasEdits)
        assertFalse(state.hasGeometryEdits)
    }

    @Test
    fun `a fresh photo's history already holds the as-loaded recipe`() {
        val edited = EditRecipe.EMPTY.with(EditStep.Lut("kodak2383", 62))

        // 先证明「不播种」确实是个缺陷：undo() 不能越过第一条，所以空历史里改一次仍然退不动，
        // 用户最该有的一条撤销（回到刚载入的样子）根本不存在。这一句也说明下面那条断言不是空跑。
        assertFalse(
            "没播种的历史：第一次改动之后仍然 canUndo==false",
            HistoryList<EditRecipe>().record(edited).canUndo
        )

        val seeded = seededHistoryOf(EditRecipe.EMPTY)
        assertFalse("站在刚载入那一格本身，再往前就没了", seeded.canUndo)

        val afterEdit = seeded.record(edited)
        assertTrue("改动一格以后必须能退回刚载入", afterEdit.canUndo)
        assertEquals(EditRecipe.EMPTY, afterEdit.undo().current)
        assertTrue("退回之后还得能重做回来", afterEdit.undo().canRedo)
        assertEquals(edited, afterEdit.undo().redo().current)
    }

    @Test
    fun `turning one flip off leaves the other direction alone`() {
        // :image 那条 without(step) 用例钉的是原语；这条钉的是编辑器实际走的那一步。
        // 界面上是两枚独立的 chip（RotatePanel 的 edit_flip_h / edit_flip_v，各自 selected 读
        // 各自的方向），两道可以同时开着，所以「关掉左右」要是把上下一起清掉，用户看到的是
        // 自己按错了——而且没有任何地方会报错。
        val both = EditRecipe.EMPTY
            .with(EditStep.Flip(horizontal = true))
            .with(EditStep.Flip(horizontal = false))

        val horizontalOff = toggledFlip(both, horizontal = true)
        assertEquals(listOf(EditStep.Flip(horizontal = false)), horizontalOff.steps)
        assertTrue("剩下的那道必须还是上下翻转", horizontalOff.fields().flipVertical)
        assertFalse(horizontalOff.fields().flipHorizontal)

        val verticalOff = toggledFlip(both, horizontal = false)
        assertEquals(listOf(EditStep.Flip(horizontal = true)), verticalOff.steps)
        assertFalse(verticalOff.fields().flipVertical)
        assertTrue(verticalOff.fields().flipHorizontal)

        assertEquals(
            "再按一次是打开同一份槽位，不是新增第二道",
            both.steps.size,
            toggledFlip(horizontalOff, horizontal = true).steps.size
        )
    }
}
