package com.imagedge.camera.feature.edit.photo

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.Geometry
import com.imagedge.camera.image.HistoryList
import com.imagedge.camera.image.NormRect
import com.imagedge.camera.image.rank
import com.imagedge.camera.image.strengthOrDefault
import com.imagedge.camera.lut.ColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「UI 那组编辑字段变成派生属性」这件事的等价性风险都在这，分四层：
 * - **折叠**：selectedKey / strength / adjust / crop / quarterTurns / flipHorizontal /
 *   flipVertical / straighten 与两个 has*，字段名、默认值与边界（度数超出圈数归一到 0..3、
 *   两个翻转方向各读各的、没选滤镜也要记住强度、配方里没有 Crop 步骤就读回全图）必须与
 *   alpha08 直接读那几个字段一致；
 * - **转发**：[PhotoEditState] 上那八个同名属性真的转发折叠结果——**包括裁剪框**，它现在是
 *   配方里的 `EditStep.Crop`，不再是 state 自己那份独立 live 值（曾是的：撤销搬得动配方、
 *   搬不动框，于是「看到的框」与「导出的框」分家，见下面「undoing a rotate…」那条）；
 * - **写入**：同一槽位的第二次写是替换不是叠加（滤镜、调色、裁剪框各一份），旋转度数在那
 *   **一份** rotate 槽位上累加，关掉翻转只删那一个方向，换照片时历史里已经播下「刚载入」那一格；
 *   几何改动与裁剪框成对写进同一份配方，前后各提交一格，撤销这一格时两者一起回去；
 * - **跨照片**：换到下一张时哪些东西跟着人走（调色、强度）、哪些跟着照片走（滤镜选择、几何）。
 *
 * `rotate()` / `toggleFlip*()` / `setCropRect()` / `loadPicked()` 都挂在 ViewModel 上（构造要
 * Context，重渲染走 viewModelScope，而 :app 的测试依赖只有 junit4，没有 mockk 与 Robolectric），
 * JVM 里起不来，所以把它们的纯算术与纯构造抽成 [rotatedRecipe] / [rotatedGeometry] /
 * [flippedGeometry] / [croppedRecipe] / [committedTransform] / [geometryToRender] /
 * [renderInputsOf] / [toggledFlip] / [seededHistoryOf] / [carriedColour] 由这里钉。
 * 这些都属「写错了界面不报错、只是行为不对」：90° 不累加就是按了没反应，按类型删翻转会把
 * 另一个方向一起清掉，历史没播种则第一次改动根本退不回去，几何与框不成对写则导出的不是看到的。
 * （strength 的**输入**钳制在 `setStrength` 里，那要 ViewModel 实例，不在本文件钉。）
 *
 * 这里**钉不住**的几件事：滑条松手与裁剪手势结束才提交（`onValueChangeFinished` /
 * `onDragFinished` 的接线）、撤销/重做入口的 enabled、在途渲染**在两处闸上都去比**
 * [renderInputsOf]（能钉的是这份键本身该含什么、不该含什么；协程与互斥的时序模拟不出来）
 * ——这几条只能靠代码审查与真机验收，不假装测过。
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
    fun `state reads all eight fields out of the recipe including the crop box`() {
        val crop = NormRect(left = 0.1f, top = 0.2f, right = 0.8f, bottom = 0.9f)
        val state = PhotoEditState(
            recipe = EditRecipe.EMPTY
                .with(EditStep.Lut("kodak2383", 62))
                .with(EditStep.Color(ColorAdjust(exposure = 40)))
                .with(EditStep.Rotate(90f))
                .with(EditStep.Flip(horizontal = true))
                .let { croppedRecipe(it, crop) },
        )

        assertEquals("kodak2383", state.selectedKey)
        assertEquals(62, state.strength)
        assertEquals(ColorAdjust(exposure = 40), state.adjust)
        assertEquals(1, state.quarterTurns)
        assertTrue(state.flipHorizontal)
        assertFalse("另一个方向不许被牵连", state.flipVertical)
        assertEquals("裁剪框也来自配方那一份 Crop 步骤", crop, state.crop)

        // 下面那条 cropOnly 才是承重的：上面那份配方自己就有旋转与翻转，几何布尔本来就为真，
        // 折叠里把 crop 写死成 FULL 也照样绿——只有「除拖框什么都没改」这份状态认得出它丢了。
        assertTrue("拖过裁剪框就算改过：重置按钮与离开确认都看这两个布尔", state.hasGeometryEdits)
        assertTrue(state.hasEdits)

        val cropOnly = PhotoEditState(recipe = croppedRecipe(EditRecipe.EMPTY, crop))
        assertEquals(crop, cropOnly.crop)
        assertTrue("只拖过裁剪框，配方是空的，也必须认出「改过」", cropOnly.hasGeometryEdits)
        assertTrue(cropOnly.hasEdits)
    }

    @Test
    fun `the crop box reads back full when the recipe carries no crop step`() {
        val rect = NormRect(0.2f, 0.1f, 0.9f, 0.7f)

        // 没有 Crop 步骤 = 全图（默认值与 alpha08 的 `crop = NormRect.FULL` 同一个口径）
        assertEquals(NormRect.FULL, EditRecipe.EMPTY.fields().crop)
        assertEquals(NormRect.FULL, PhotoEditState().crop)
        assertFalse(EditRecipe.EMPTY.fields().hasGeometryEdits)
        // 有那一步就读它，state 与折叠读出来必须是同一个值（两个读法分家就是缺陷的旧形态）
        assertEquals(rect, croppedRecipe(EditRecipe.EMPTY, rect).fields().crop)
        assertEquals(rect, PhotoEditState(recipe = croppedRecipe(EditRecipe.EMPTY, rect)).crop)
    }

    @Test
    fun `a second crop write replaces the one box instead of stacking a stale one`() {
        val first = NormRect(0f, 0f, 0.5f, 0.5f)
        val second = NormRect(0.2f, 0.2f, 0.8f, 0.6f)

        val once = croppedRecipe(EditRecipe.EMPTY, first)
        val twice = croppedRecipe(once, second)

        assertEquals("裁剪只有一份槽位，第二次拖动是替换", listOf(EditStep.Crop(second)), twice.steps)
        assertEquals(second, twice.fields().crop)

        // 拖回全图 = 删掉那一步：于是「没有 Crop 步骤」与「框是全图」是同一件事，
        // 渲染侧就靠这件事省掉一次全图裁剪（见 applyCurrentFilter 里那条 isFull 短路）
        assertEquals(emptyList<EditStep>(), croppedRecipe(twice, NormRect.FULL).steps)

        // 越界的框先 sanitized() 再进配方：配方里不许留画面外的坐标——那一份值正是撤销与重做
        // 要复读的东西，留着 1.6 就等于「界面上的框」与「复读出来的框」两个数
        assertEquals(
            "拖出画面外的右边要夹回 1.0",
            NormRect(0.2f, 0.1f, 1f, 0.7f),
            croppedRecipe(EditRecipe.EMPTY, NormRect(0.2f, 0.1f, 1.6f, 0.7f)).fields().crop
        )
        // 整个盖住画面的框等于全图，同样不许占一份槽位
        assertEquals(
            "拖出画面外的一整圈等于全图",
            emptyList<EditStep>(),
            croppedRecipe(EditRecipe.EMPTY, NormRect(-0.5f, -0.5f, 2f, 2f)).steps
        )
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

    @Test
    fun `switching photos carries the tone and the strength but not the filter or the geometry`() {
        // alpha08 的 loadPicked 带 strength 与 adjust、不带 selectedKey，几何靠「新建 state」清零；
        // 这里逐条钉同一件事，读的是 UI 那条路径（PhotoEditState 的派生属性）。
        val adjust = ColorAdjust(exposure = 43, contrast = -20)
        val previous = EditRecipe.EMPTY
            .with(EditStep.Lut("kodak2383", 44))
            .with(EditStep.Color(adjust))
            .with(EditStep.Rotate(90f))
            .with(EditStep.Flip(horizontal = true))
            .with(EditStep.Flip(horizontal = false))
            .with(EditStep.Straighten(6f))

        val carried = carriedColour(previous, FILTER_NONE)
        val fresh = PhotoEditState(recipe = carried)

        assertEquals("调色跟着人走：下一张还是这次的影调", adjust, fresh.adjust)
        assertEquals(
            "强度同理；它靠配方里那条 key 为「原图」的 Lut 步骤占位才活得下来",
            44,
            fresh.strength
        )
        assertEquals("滤镜选择跟着照片走：下一张回到原图", FILTER_NONE, fresh.selectedKey)

        // 几何「一步都不许带」按配方形状断言，而不是只看折叠布尔：形状说得出**带了哪一步**，
        // 布尔只说「改过」（裁剪框现在也在配方里，所以两种读法都成立，留直接指名的那个）。
        assertEquals(
            "旋转、翻转、拉直、裁剪框都不跟着人走",
            emptyList<EditStep>(),
            carried.steps.filter { it.rank == 0 }
        )
        assertEquals(0, fresh.quarterTurns)
        assertFalse(fresh.flipHorizontal)
        assertFalse(fresh.flipVertical)
        assertEquals(0f, fresh.straighten, 0f)
        assertFalse(fresh.hasGeometryEdits)

        // 带过来的调色本身就算「没存盘的改动」：alpha08 换完照片「重置」就是亮的
        // （canReset/hasEdits 吃的是同一个 hasEdits，见 PhotoEditScreen 的 EditorFrameState）
        assertTrue("换了照片仍带着未存盘的调色，离开确认与重置按钮都得认", fresh.hasEdits)
    }

    @Test
    fun `nothing to carry leaves a bare strength slot instead of a phantom colour step`() {
        // 折叠结果两者一样（identity 的 Color 读回还是 NONE），所以这条只能钉配方形状：
        // 带过去的必须是「有内容的东西」，空步骤不许占槽位。
        val previous = EditRecipe.EMPTY.with(EditStep.Lut(FILTER_NONE, 37))
        val carried = carriedColour(previous, FILTER_NONE)

        assertEquals(listOf(EditStep.Lut(FILTER_NONE, 37)), carried.steps)
        assertEquals(37, carried.fields().strength)
        assertFalse(
            "只带着强度换照片，在新照片上同样不算改过（与 alpha08 同语义），重置不该亮",
            PhotoEditState(recipe = carried).hasEdits
        )
    }

    @Test
    fun `the seeded history points at the recipe the new photo actually starts with`() {
        // loadPicked 里 recipe 与 history 用的是同一个 carried 值，这条钉那个不变量本身：
        // 游标得站在起始配方上，否则第一次撤销退回的是「这张照片从来没有过的状态」。
        val carried = carriedColour(
            EditRecipe.EMPTY
                .with(EditStep.Lut("kodak2383", 44))
                .with(EditStep.Color(ColorAdjust(exposure = 43))),
            FILTER_NONE
        )
        val seeded = seededHistoryOf(carried)

        assertEquals(carried, seeded.current)
        assertFalse("站在刚载入那一格，再往前就没了", seeded.canUndo)

        val edited = seeded.record(carried.with(EditStep.Lut("slog3_fuji_et-8", 20)))
        assertTrue(edited.canUndo)
        assertEquals(
            "第一次撤销要退回「带着影调的刚载入」，不是真正的空白",
            carried,
            edited.undo().current
        )
    }

    @Test
    fun `undoing a rotate takes the crop box back with it`() {
        // 这条钉的是「框与几何成对写进同一份配方」。反面是它曾经的形态：框是 state 上另一份
        // live 值、撤销只搬配方，于是「拖框 → 右转 → 撤销」之后画面回到未旋转、框仍停在旋转后的
        // 坐标系上——用户当初框住的那块内容被无声换成另一块（框本身在屏幕上与导出始终是同一个值）。
        val dragged = NormRect(0.1f, 0.2f, 0.5f, 0.6f)
        val loaded = seededHistoryOf(EditRecipe.EMPTY)
        // setCropRect：写进配方的 Crop 槽位，不进历史
        val framed = croppedRecipe(EditRecipe.EMPTY, dragged)
        // rotate(clockwise = true)：几何与框成对写进同一份配方，前后各提交一格
        val turned = rotatedGeometry(framed, step = 1, aspectRatio = null, baseImageAspect = 4f / 3f)
        val afterTurn = committedTransform(loaded, framed, turned)

        // 转完之后框必须已经在新坐标系里，否则「看到的」与「导出的」当场分家
        assertEquals(
            "旋转把框带进新坐标系",
            Geometry.rotate90(dragged, 1),
            turned.fields().crop
        )
        assertEquals(Geometry.rotate90(dragged, 1), PhotoEditState(recipe = turned).crop)

        val undone = afterTurn.undo().current ?: error("撤销落不到配方")
        assertEquals("撤销的是旋转：画面回到没转", 0, undone.fields().quarterTurns)
        assertEquals("框跟着一起回到旋转前那一块", dragged, undone.fields().crop)
        assertEquals("state 读出来的框就是这一格配方里的框", dragged, PhotoEditState(recipe = undone).crop)
        assertEquals(
            "重做这一格：框又跟着画面走回新坐标系",
            Geometry.rotate90(dragged, 1),
            (afterTurn.undo().redo().current ?: error("重做落不到配方")).fields().crop
        )
    }

    @Test
    fun `undoing a horizontal flip restores the box and leaves the other direction alone`() {
        val before = NormRect(0.1f, 0.2f, 0.6f, 0.5f)
        val framed = croppedRecipe(EditRecipe.EMPTY.with(EditStep.Flip(horizontal = false)), before)
        val history = seededHistoryOf(framed)

        // toggleFlipHorizontal() 走的那一步：槽位按方向开关，框跟着镜像，前后各提交一格
        val turned = flippedGeometry(framed, horizontal = true)
        val afterTurn = committedTransform(history, framed, turned)

        assertEquals("镜像把框也翻过去", Geometry.flipHorizontal(before), turned.fields().crop)
        assertTrue("这一格之后上下翻转还开着", turned.fields().flipVertical)
        assertTrue("左右翻转也开着", turned.fields().flipHorizontal)

        val undone = afterTurn.undo().current ?: error("撤销落不到配方")
        assertEquals("撤销左右翻转之后框回到翻转前那一块", before, undone.fields().crop)
        assertTrue("另一个方向不许被这次开关牵连", undone.fields().flipVertical)
        assertFalse(undone.fields().flipHorizontal)
    }

    @Test
    fun `a locked ratio refits the box to the turned frame instead of rotating the old one`() {
        val base = 4f / 3f
        // 用 16:9 而不是 1:1：1:1 的框转 90° 恰好等于按新画面重贴的结果，
        // 那条分支写错（把旧框转过去）时 1:1 看不出差别，所以拿一个转完必然不同的比例来钉
        val wide = 16f / 9f
        val framed = croppedRecipe(EditRecipe.EMPTY, Geometry.maxRectForAspect(base, wide))

        val turned = rotatedGeometry(framed, step = 1, aspectRatio = wide, baseImageAspect = base)

        assertEquals(1, turned.fields().quarterTurns)
        assertEquals(
            "画面宽高互换了，框要按**新画面**重贴该比例；把旧框转 90° 会得到不再贴合的矩形",
            Geometry.maxRectForAspect(1f / base, wide),
            turned.fields().crop
        )

        val history = committedTransform(seededHistoryOf(framed), framed, turned)
        assertEquals(
            "撤销这一格：画面与框一起回到转换前那一份贴合值",
            framed.fields().crop,
            (history.undo().current ?: error("撤销落不到配方")).fields().crop
        )

        // 自由比例走另一条分支：框跟着转，不做重贴
        val free = croppedRecipe(EditRecipe.EMPTY, NormRect(0.1f, 0.2f, 0.5f, 0.6f))
        assertEquals(
            Geometry.rotate90(NormRect(0.1f, 0.2f, 0.5f, 0.6f), 1),
            rotatedGeometry(free, step = 1, aspectRatio = null, baseImageAspect = base).fields().crop
        )
    }

    @Test
    fun `the geometry handed to the renderer carries exactly the box the ui shows`() {
        val rect = NormRect(0.15f, 0.15f, 0.85f, 0.85f)
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Straighten(3f))
            .with(EditStep.Rotate(90f))
            .with(EditStep.Color(ColorAdjust(exposure = 20)))
            .with(EditStep.Lut("kodak2383", 55))
            .let { croppedRecipe(it, rect) }

        assertEquals(
            "几何 = 拉直 + 旋转 + 裁剪；颜色一步都不许交给 renderGeometry",
            listOf(EditStep.Straighten(3f), EditStep.Rotate(90f), EditStep.Crop(rect)),
            geometryToRender(recipe)
        )
        assertEquals(
            "渲染拿到的那条 Crop 与界面显示的框必须出自同一份配方；分成两处取值时撤销搬得动一处、搬不动另一处",
            PhotoEditState(recipe = recipe).crop,
            geometryToRender(recipe).filterIsInstance<EditStep.Crop>().single().rect
        )
    }

    @Test
    fun `dragging the crop box is not a change the render guard may act on`() {
        val started = EditRecipe.EMPTY
            .with(EditStep.Straighten(3f))
            .with(EditStep.Lut("kodak2383", 55))
        val framed = croppedRecipe(started, NormRect(0.1f, 0.2f, 0.5f, 0.6f))
        val dragged = croppedRecipe(started, NormRect(0.2f, 0.1f, 0.7f, 0.4f))

        assertEquals(
            "拖框只换配方里的 Crop 槽位。裁剪页显示的是 geometryOnly（不含裁剪），拖框对它零影响，" +
                    "于是拖框不能出现在闸的比较里——一旦在途渲染被它判为过期，" +
                    "而拖框又不发起新的渲染，processing 就没人复位，转圈会一直挂着",
            renderInputsOf(framed),
            renderInputsOf(dragged)
        )
    }

    @Test
    fun `the render guard key still carries everything a render actually consumes`() {
        val plain = EditRecipe.EMPTY.with(EditStep.Straighten(3f))
        assertNotEquals("拉直角是渲染的输入，闸不许对它瞎", renderInputsOf(plain), renderInputsOf(plain.with(EditStep.Straighten(9f))))
        assertNotEquals(
            "调色与滤镜同属渲染输入",
            renderInputsOf(plain),
            renderInputsOf(plain.with(EditStep.Color(ColorAdjust(exposure = 20))))
        )
        assertEquals(
            "闸去掉的只有 Crop 这一步，其余几何一步不少",
            listOf(EditStep.Straighten(3f), EditStep.Rotate(90f)),
            renderInputsOf(
                croppedRecipe(plain.with(EditStep.Rotate(90f)), NormRect(0.1f, 0.1f, 0.5f, 0.5f))
            ).steps
        )
    }
}
