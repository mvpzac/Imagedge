package com.imagedge.camera.image

import com.imagedge.camera.lut.ColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这一轮的成败全押在这里：配方折叠出来的 (滤镜 key, 强度, 调色) 三元组，
 * 必须与 0.2.0-alpha08 直接读三个字段的结果逐位相同。
 *
 * 「没选滤镜也保留强度」这条尤其容易在重构中被「优化」掉——那是行为变化。
 */
class EditRecipeFoldEquivalenceTest {

    @Test
    fun `no filter still remembers the strength`() {
        val recipe = EditRecipe.EMPTY.with(EditStep.Lut(key = "none", strength = 37))

        assertEquals("none", recipe.lutKeyOrDefault(noFilterKey = "none"))
        assertEquals("强度不能因为没套 LUT 就丢", 37, recipe.strengthOrDefault(default = 80))
    }

    @Test
    fun `an unset recipe falls back to the app defaults exactly like the old fields`() {
        val recipe = EditRecipe.EMPTY

        assertEquals("none", recipe.lutKeyOrDefault(noFilterKey = "none"))
        assertEquals(80, recipe.strengthOrDefault(default = 80))
        assertEquals(ColorAdjust.NONE, recipe.colorAdjust)
    }

    @Test
    fun `a chosen filter wins over the remembered strength default`() {
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Color(ColorAdjust(highlights = -40)))
            .with(EditStep.Lut(key = "kodak2383", strength = 62))

        assertEquals("kodak2383", recipe.lutKeyOrDefault(noFilterKey = "none"))
        assertEquals(62, recipe.strengthOrDefault(default = 80))
        assertEquals(-40, recipe.colorAdjust.highlights)
    }

    @Test
    fun `the triple round trips for every slider the ui exposes`() {
        // 七个滑条各自验证一次「写进配方 → 折叠回来 == 原值」，
        // 防止以后加字段时只加了 UI 忘了折叠
        val adjust = ColorAdjust(
            exposure = 12, contrast = -30, saturation = 55,
            temperature = 20, tint = -8, shadows = 44, highlights = -70
        )
        val folded = EditRecipe.EMPTY.with(EditStep.Color(adjust)).colorAdjust

        assertEquals(adjust, folded)
    }
}
