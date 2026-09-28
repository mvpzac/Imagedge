package com.imagedge.camera.image

import com.imagedge.camera.lut.ColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 配方的同类替换规则。规则本身来自 Gallery2 filtershow 的
 * ImagePreset.updateOrAddFilterRepresentation（同名就地替换、保持位置），
 * 但那条身份刻意**不含 LUT 的 key**：把 key 算进身份就等于提前开放多层叠加，
 * 而叠加意味着 N 次全像素遍历 + 8 位上多次抖动叠加。
 */
class EditRecipeTest {

    @Test
    fun `a second colour step replaces the first in place instead of appending`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Straighten(3f))
            .with(EditStep.Color(ColorAdjust(exposure = 20)))
            .with(EditStep.Color(ColorAdjust(exposure = 40)))

        assertEquals("同类替换不得让列表变长", 2, recipe.steps.size)
        assertEquals(40, recipe.colorAdjust.exposure)
        assertEquals("几何仍在前", EditStep.Straighten(3f), recipe.steps[0])
    }

    @Test
    fun `choosing a different filter swaps the single Lut slot`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Lut("none", 80))
            .with(EditStep.Lut("kodak2383", 55))

        assertEquals("换滤镜是替换，不是叠加两层 LUT", 1, recipe.steps.size)
        assertEquals("kodak2383", recipe.lut?.key)
        assertEquals(55, recipe.lut?.strength)
    }

    @Test
    fun `both flips coexist because they are different identities`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Flip(horizontal = true))
            .with(EditStep.Flip(horizontal = false))

        assertEquals(2, recipe.steps.size)
    }

    @Test
    fun `canonical order keeps geometry before colour before lut`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Lut("kodak2383", 80))
            .with(EditStep.Color(ColorAdjust(contrast = 10)))
            .with(EditStep.Rotate(90f))

        assertEquals(
            listOf("rotate", "color", "lut"),
            recipe.steps.map { it.identity }
        )
    }

    @Test
    fun `replacing in place does not reorder an already canonical list`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Crop(NormRect(0.1f, 0.1f, 0.9f, 0.9f)))
            .with(EditStep.Color(ColorAdjust(saturation = 30)))
            .with(EditStep.Color(ColorAdjust(saturation = 10)))

        assertEquals("crop", recipe.steps[0].identity)
        assertEquals("color", recipe.steps[1].identity)
        assertEquals(10, recipe.colorAdjust.saturation)
    }

    @Test
    fun `geometryOnly excludes crop so the crop overlay can draw on an uncut frame`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Straighten(2f))
            .with(EditStep.Crop(NormRect(0.2f, 0.2f, 0.8f, 0.8f)))

        assertEquals(1, recipe.geometryOnly.size)
        assertEquals(2, recipe.allSteps.size)
    }

    @Test
    fun `geometryOnly also excludes colour and lut steps`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Rotate(90f))
            .with(EditStep.Color(ColorAdjust(exposure = 10)))
            .with(EditStep.Lut("kodak2383", 50))
            .with(EditStep.Crop(NormRect(0.2f, 0.2f, 0.8f, 0.8f)))

        // 这条是防「用 identity != "crop" 来筛」的写法：那种写法会把 color/lut
        // 一起当几何交给 renderGeometry，而 ImagePipeline 根本不认识它们
        assertEquals(listOf("rotate"), recipe.geometryOnly.map { it.identity })
        assertEquals(listOf("rotate", "crop"), recipe.allSteps.map { it.identity })
    }

    @Test
    fun `empty recipe reads as today's no-edit state`() {
        val recipe = EditRecipe.EMPTY

        assertEquals(ColorAdjust.NONE, recipe.colorAdjust)
        assertEquals(null, recipe.lut)
        assertEquals(emptyList<EditStep>(), recipe.allSteps)
    }

    @Test
    fun `without removes the rotate slot and leaves the rest in order`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Rotate(90f))
            .with(EditStep.Color(ColorAdjust(exposure = 10)))
            .with(EditStep.Lut("kodak2383", 50))

        val next = recipe.without<EditStep.Rotate>()

        assertEquals(listOf("color", "lut"), next.steps.map { it.identity })
        assertEquals(EditStep.Color(ColorAdjust(exposure = 10)), next.steps[0])
    }

    @Test
    fun `without a step that is absent is a no-op returning an equal recipe`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Crop(NormRect(0.1f, 0.1f, 0.9f, 0.9f)))
            .with(EditStep.Color(ColorAdjust(exposure = 10)))

        assertEquals(recipe, recipe.without<EditStep.Rotate>())
    }

    @Test
    fun `constructor rejects a list holding two steps of the same identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            EditRecipe(
                listOf(
                    EditStep.Color(ColorAdjust(exposure = 10)),
                    EditStep.Color(ColorAdjust(exposure = 30)),
                )
            )
        }
    }

    @Test
    fun `constructor accepts a hand-built list that is already rank ordered`() {
        // 同 rank 相邻（两条几何）也必须放行：约束是「不降」而非「严格递增」
        val recipe = EditRecipe(
            listOf(
                EditStep.Rotate(90f),
                EditStep.Flip(horizontal = true),
                EditStep.Color(ColorAdjust(exposure = 10)),
                EditStep.Lut("kodak2383", 50),
            )
        )

        assertEquals(4, recipe.steps.size)
        assertEquals(listOf("rotate", "flip:true", "color", "lut"), recipe.steps.map { it.identity })
    }
}
