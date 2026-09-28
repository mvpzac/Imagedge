package com.imagedge.camera.data.edit

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.lut.ColorAdjust
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EditRecipePresetStoreTest {

    private fun tempDir(tag: String) =
        File(System.getProperty("java.io.tmpdir"), "preset-$tag-${System.nanoTime()}")

    @Test
    fun `a saved preset comes back as the same recipe`() {
        val dir = tempDir("roundtrip").apply { mkdirs() }
        val recipe = EditRecipe.EMPTY
            .with(EditStep.Color(ColorAdjust(contrast = 25)))
            .with(EditStep.Lut("kodak2383", 70))

        writePreset(dir, "我的默认", recipe)
        val read = readPreset(dir, "我的默认")

        assertEquals(null, read.failure)
        assertEquals(recipe, read.recipe)
        dir.deleteRecursively()
    }

    @Test
    fun `a name that would escape the directory is refused`() {
        val dir = tempDir("escape").apply { mkdirs() }

        assertNull(
            "含路径分隔的名字必须解析不出文件，否则预设写入会落到目录外",
            presetFileFor(dir, "../../etc/passwd")
        )
        assertNotNull(readPreset(dir, "../../etc/passwd").failure)
        dir.deleteRecursively()
    }

    @Test
    fun `reading a missing preset reports instead of throwing`() {
        val dir = tempDir("missing").apply { mkdirs() }

        val result = readPreset(dir, "没有这个")

        // 必须断言**是哪一条**拒绝：光看 failure != null 的话，把上面那个 isFile 分支删掉
        // 也照样绿——readText 抛 IOException 会被兜底的 runCatching 翻成另一条原因。
        // 「文件不存在」与「文件读不出来」是两件事，用户要看到的是前者
        assertEquals(true, result.failure?.contains("不存在"))
        assertNull(result.recipe)
        dir.deleteRecursively()
    }

    @Test
    fun `a hand-corrupted file fails closed instead of yielding a partial recipe`() {
        val dir = tempDir("corrupt").apply { mkdirs() }
        File(dir, "坏掉.$PRESET_EXTENSION").writeText("{这不是 JSON")

        val result = readPreset(dir, "坏掉")

        assertNotNull(result.failure)
        assertNull(result.recipe)
        dir.deleteRecursively()
    }

    @Test
    fun `a preset name containing a dot still comes back from the listing`() {
        // 读回来的名字走 File.nameWithoutExtension：名字里留点号的话
        // 「我的.v2」落盘成 我的.v2.json，列表里读回来是「我的」，再按这个名字去读就找不到文件——
        // 预设存在了却永远套不出来，且不报任何错。归一化去掉点号正是为了这一条
        val dir = tempDir("dot").apply { mkdirs() }
        val recipe = EditRecipe.EMPTY.with(EditStep.Color(ColorAdjust(contrast = 10)))

        writePreset(dir, "我的.v2", recipe)

        val listed = dir.listPresetNames()
        assertEquals(listOf("我的v2"), listed)
        assertEquals(null, readPreset(dir, listed.single()).failure)
        assertEquals(recipe, readPreset(dir, listed.single()).recipe)
        dir.deleteRecursively()
    }

    @Test
    fun `listing only sees preset files`() {
        val dir = tempDir("list").apply { mkdirs() }
        writePreset(dir, "乙", EditRecipe.EMPTY)
        writePreset(dir, "甲", EditRecipe.EMPTY)
        File(dir, "无关.txt").writeText("x")

        assertEquals(
            "列表要按名字稳定排序，且不被非预设文件污染",
            listOf("乙", "甲").sorted(),
            dir.listPresetNames()
        )
        dir.deleteRecursively()
    }
}
