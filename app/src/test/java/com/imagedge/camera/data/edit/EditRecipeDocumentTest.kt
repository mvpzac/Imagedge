package com.imagedge.camera.data.edit

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.lut.ColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纪律照 data/profile/PresetDocument.kt：版本号、未知键拒绝、越界拒绝、
 * 失败给出原因而不是回落到默认值。一份「猜着读进来」的配方会把用户的照片改坏。
 */
class EditRecipeDocumentTest {

    private val sample = EditRecipe.EMPTY
        .with(EditStep.Straighten(-4.5f))
        .with(EditStep.Rotate(90f))
        // 垂直翻转，且**刻意取 horizontal = false**：false 是字段的默认值，
        // 一旦有人把 encodeDefaults 关掉「省字节」，这一步就会从文件里整个消失，
        // 读回来是「没有翻转」——上面那条 round trip 是唯一能抓住它的地方
        .with(EditStep.Flip(horizontal = false))
        .with(EditStep.Crop(com.imagedge.camera.image.NormRect(0.1f, 0.2f, 0.9f, 0.8f)))
        .with(EditStep.Color(ColorAdjust(exposure = 15, highlights = -30)))
        .with(EditStep.Lut("kodak2383", 62))

    @Test
    fun `a preset round trips every step it carries`() {
        val decoded = EditRecipeDocument.decode(EditRecipeDocument.encode(sample)).recipe

        assertEquals(sample, decoded)
    }

    @Test
    fun `a flip step without its direction is refused rather than guessed`() {
        // 缺 horizontal 就猜一个方向，猜错的那一次是把用户的照片整个镜像掉——界面上看不出来，
        // 因为镜像本身是合法操作。与「未知步骤」同一条纪律：给原因，不猜
        val text = """{"format":1,"steps":[{"kind":"flip"}]}"""

        val result = EditRecipeDocument.decode(text)
        assertNotNull(result.failure)
        assertNull(result.recipe)
    }

    @Test
    fun `an unknown key is rejected rather than ignored`() {
        val text = EditRecipeDocument.encode(sample)
            .replace("\"format\":", "\"hacked\":1,\"format\":")

        assertNotNull(EditRecipeDocument.decode(text).failure)
        assertNull(EditRecipeDocument.decode(text).recipe)
    }

    @Test
    fun `a future format version is refused outright`() {
        val text = EditRecipeDocument.encode(sample).let {
            it.replaceFirst(Regex("\"format\":\\s*\\d+"), "\"format\":99")
        }

        val failure = EditRecipeDocument.decode(text).failure
        assertNotNull("版本不认识必须拒绝，不能尽力猜", failure)
        assertEquals(true, failure!!.contains("99"))
    }

    @Test
    fun `an unknown step kind is rejected with its name`() {
        // 只留 kind：多余的键会先被 ignoreUnknownKeys=false 挡在 JSON 层，
        // 那样测到的是「未知键拒绝」（上一条测试已经覆盖），而不是未知步骤种类
        val text = """{"format":1,"steps":[{"kind":"Warp"}]}"""

        val result = EditRecipeDocument.decode(text)
        assertNotNull(result.failure)
        assertEquals(true, result.failure!!.contains("Warp"))
    }

    @Test
    fun `an out-of-range strength is refused, not clamped`() {
        // 强度 0..100、七个调色参数 −100..100：越界即畸形。
        // 「悄悄钳到能用的值」是这条纪律的反面——那份配方从此与用户存的不是同一个东西
        val text = """{"format":1,"steps":[{"kind":"lut","key":"a","strength":999}]}"""

        val failure = EditRecipeDocument.decode(text).failure
        assertNotNull(failure)
    }

    @Test
    fun `steps out of canonical order are refused with a reason`() {
        // EditRecipe 的 init 要求 rank 非降；构造器抛的 IllegalArgumentException 若不接住，
        // 一个能解析、但不合规矩的文件会把调用方炸崩——预设读侧必须给原因而不是抛
        val text = """{"format":1,"steps":[{"kind":"lut","key":"a","strength":50},{"kind":"rotate","degrees":90}]}"""

        val result = EditRecipeDocument.decode(text)
        assertNotNull("乱序文件必须被拒绝并给出原因，不能抛异常", result.failure)
        assertNull(result.recipe)
    }

    @Test
    fun `a file carrying the same kind twice is refused with a reason`() {
        // 同类两份同样违反 EditRecipe 的「同一身份至多一步」不变量：按 init 会抛，
        // 按宽容读法会取后一份——两条都不是这里要的行为
        val text = """{"format":1,"steps":[{"kind":"lut","key":"a","strength":50},{"kind":"lut","key":"b","strength":80}]}"""

        val result = EditRecipeDocument.decode(text)
        assertNotNull(result.failure)
        assertNull(result.recipe)
    }

    @Test
    fun `blank and oversized input fail closed`() {
        assertNotNull(EditRecipeDocument.decode(null).failure)
        assertNotNull(EditRecipeDocument.decode("").failure)
        // 必须用**非空白**的超长文本：全空格会先被 isNullOrBlank 判成「内容为空」，
        // 那条大小上限的分支就一次也没跑到——断言照样绿，测的却是别的东西
        val oversized = EditRecipeDocument.decode("x".repeat(64 * 1024))
        assertNotNull(oversized.failure)
        assertEquals(true, oversized.failure!!.contains("过大"))
    }
}
