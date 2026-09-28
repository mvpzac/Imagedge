package com.imagedge.camera.data.edit

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.lut.ColorAdjust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纪律照 data/profile/PresetDocument.kt：版本号、未知键拒绝、越界拒绝、
 * 失败给出原因而不是回落到默认值。一份「猜着读进来」的配方会把用户的照片改坏。
 */
class EditRecipeDocumentTest {

    private val sample = EditRecipe.EMPTY
        .with(EditStep.Straighten(-4.5f))
        .with(EditStep.Rotate(90f))
        // 带一枚 flip，且取 horizontal = false：brief 的样例原本没有 flip，
        // 于是「编码/解码 flip」这条路径一次也没跑过（缺方向的 flip 被猜成某个方向那种变异
        // 当场全绿）。取 false 是为了让两个方向里至少有一个被走过——它是 :40 那个可空字段的
        // 非默认值，所以照样写进文件、照样读回来
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

    @Test
    fun `an out-of-range colour slider is refused rather than clamped`() {
        // 七个调色参数各 -100..100。不查的话渲染侧会把它 coerceIn 到边界：用户存的是 101、
        // 拿到的是 100，两份配方不是同一个东西，而全程没人说这件事
        val text = """{"format":1,"steps":[{"kind":"color","color":{"exposure":101}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `an out-of-range straighten angle is refused`() {
        val text = """{"format":1,"steps":[{"kind":"straighten","degrees":90}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `a rotate angle that is not a quarter turn is refused`() {
        // 界面只写 90 的整数倍。收 45° 的话画面真转 45°，而 quarterTurns 整除取到 0：
        // hasGeometryEdits 报「没动过构图」，裁剪页拿到的是错的画面比例
        val text = """{"format":1,"steps":[{"kind":"rotate","degrees":45}]}"""

        val failure = EditRecipeDocument.decode(text).failure
        assertNotNull("要拒在**步骤校验**这一层，而不是靠 JSON 解析碰巧失败", failure)
        assertEquals(true, failure!!.contains("rotate"))
    }

    @Test
    fun `a crop rect outside the unit square is refused`() {
        val text = """{"format":1,"steps":[{"kind":"crop","rect":{"left":-2.0,"top":0.0,"right":1.0,"bottom":1.0}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `a blank filter key is refused`() {
        // key 空白不是「没选滤镜」的表示法（那是 strength 与 key 一起缺位的 EMPTY 配方），
        // 读进来会得到一个查不到资产的滤镜步骤，套用时才在筛选器列表里找不到
        val text = """{"format":1,"steps":[{"kind":"lut","key":"  ","strength":50}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `more steps than the format allows are refused before any step is parsed`() {
        val steps = List(17) { """{"kind":"lut","key":"a","strength":50}""" }.joinToString(",")

        val failure = EditRecipeDocument.decode("""{"format":1,"steps":[$steps]}""").failure

        assertEquals("数量闸要在逐条解析之前，且报的是它自己的原因", true, failure?.contains("步骤数量异常"))
    }

    @Test
    fun `an empty step list is the identity recipe, not an error`() {
        // 钉住意图：encode(EMPTY) 必须原样读回来。Task 6 套用这样一份预设等于「清掉颜色与滤镜」，
        // 那是合法语义，不是坏文件——所以这里断言的是「接受」，不是「拒绝」
        val result = EditRecipeDocument.decode("""{"format":1,"steps":[]}""")

        assertNull(result.failure)
        assertEquals(EditRecipe.EMPTY, result.recipe)
        assertTrue("isUsable 必须与「有配方且无原因」同真同假，Task 6 就靠它分流", result.isUsable)
    }

    @Test
    fun `a NaN coordinate is refused by the parser before the rect is ever checked`() {
        // 这条是给「定点检查够不够用」设的**绊线**：NaN 能穿过 sanitized() 后不变这个判据
        // （Kotlin 的 Float == 是全序的，NaN == NaN 为真，coerceIn/minOf/maxOf 遇全 NaN 原样返回），
        // 今天靠 isLenient=false 在解析层就把它挡了。哪天换的 kotlinx 版本开始接受这个 token，
        // 这条会红——那时要补的是 toDomain 里的 isFinite，而不是把这条删掉
        val result = EditRecipeDocument.decode(
            """{"format":1,"steps":[{"kind":"crop","rect":{"left":NaN,"top":0.0,"right":1.0,"bottom":1.0}}]}"""
        )

        assertNotNull(result.failure)
        assertEquals(true, result.failure!!.contains("不是受支持的预设格式"))
    }
}