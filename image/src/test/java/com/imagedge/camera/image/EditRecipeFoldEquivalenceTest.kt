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
    fun `a chosen filter folds back its key and strength while colour rides along`() {
        // key 与强度出自**同一个** Lut 步骤，两者之间没有先后可争（此前那条名字里的
        // 「wins over the remembered strength default」是错的：并不存在要压制的优先级）。
        // 这条钉的是换成真滤镜以后两个字段都原样折叠出来，且同配方里的 color 不受 lut 影响。
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Color(ColorAdjust(highlights = -40)))
            .with(EditStep.Lut(key = "kodak2383", strength = 62))

        assertEquals("kodak2383", recipe.lutKeyOrDefault(noFilterKey = "none"))
        assertEquals(62, recipe.strengthOrDefault(default = 80))
        assertEquals(-40, recipe.colorAdjust.highlights)
    }

    @Test
    fun `the color step folds back the adjust it was given`() {
        // 钉的是：Color 步骤把整份 ColorAdjust 存进配方、`colorAdjust` 又把它原样读回；
        // 七个滑条各取不同值，所以步骤被丢掉、被 NONE 顶掉、或改成逐字段重建时漏一个，都会红。
        // 它**测不出**「以后新增滑条只加了 UI 忘了折叠」：步骤存的是整个 ColorAdjust，
        // 新字段跟着对象一起原样往返，这条永远绿。真正的漏接线在 :app——那边每个滑条各自写
        // `state.adjust.copy(<某个字段> = it)`（PhotoEditScreen 的调色段），加字段的人可能只加
        // 了那一句 copy；那不在本模块，别指望这里替它守门。
        val adjust = ColorAdjust(
            exposure = 12, contrast = -30, saturation = 55,
            temperature = 20, tint = -8, shadows = 44, highlights = -70
        )
        val folded = EditRecipe.EMPTY.with(EditStep.Color(adjust)).colorAdjust

        assertEquals(adjust, folded)
    }
}
