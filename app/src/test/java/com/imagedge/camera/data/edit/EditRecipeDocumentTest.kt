package com.imagedge.camera.data.edit

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.lut.ColorAdjust
import com.imagedge.camera.lut.KeyAxis
import com.imagedge.camera.lut.RangeKey
import com.imagedge.camera.lut.SelectiveAdjust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 今天靠 kotlinx 的 allowSpecialFloatingPointValues（默认 false）在 decodeFloat 里就拒掉——
        // **不是** isLenient，那个开关管的是另一件事，挡不住这个 token。
        // 哪天有人把它打开、或换的 kotlinx 版本改了默认值，这条会红——那时要补的是 toDomain 里的
        // isFinite，而不是把这条删掉
        val result = EditRecipeDocument.decode(
            """{"format":1,"steps":[{"kind":"crop","rect":{"left":NaN,"top":0.0,"right":1.0,"bottom":1.0}}]}"""
        )

        assertNotNull(result.failure)
        assertEquals(true, result.failure!!.contains("不是受支持的预设格式"))
    }

    @Test
    fun `a rotate angle beyond one turn is refused`() {
        // 450 是 90 的整倍，quarterTurns 那关拦不住它——只有 ±360 那条范围闸拦得住。
        // 上一轮补的 45° 用例测的是 %90 那一半，范围这一半当时仍然是拆了也没人红
        val text = """{"format":1,"steps":[{"kind":"rotate","degrees":450}]}"""

        val failure = EditRecipeDocument.decode(text).failure
        assertNotNull("范围闸要有自己的用例，不能搭 %90 那条的便车", failure)
        assertEquals(true, failure!!.contains("rotate"))
    }

    @Test
    fun `a filter key longer than the format allows is refused`() {
        // MAX_KEY_CHARS 是这一句唯一的用处：不给上限，一个 5MB 的 key 也能读成一条合法步骤。
        // 空白那条用例只钉住了 isNotBlank，长度这一半没人钉
        val text = """{"format":1,"steps":[{"kind":"lut","key":"${"k".repeat(121)}","strength":50}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `isUsable is false on a refused file, not just true on an accepted one`() {
        // 只断言正向的话，isUsable 被改成 `recipe != null` 或 `failure == null` 都能全绿；
        // 两个方向都钉，它才真的是「有配方且无原因」
        val refused = EditRecipeDocument.decode("""{"format":1,"steps":[{"kind":"Warp"}]}""")

        assertFalse(refused.isUsable)
        assertNull(refused.recipe)
    }

    @Test
    fun `a selective step round trips through the preset format`() {
        val recipe = EditRecipe.EMPTY.with(
            EditStep.Selective(
                RangeKey.of(KeyAxis.HUE, 0.85f, 1.15f, feather = 0.12f, inverted = true),
                SelectiveAdjust(exposure = 12, contrast = -4, saturation = 8)
            )
        )

        assertEquals(recipe, EditRecipeDocument.decode(EditRecipeDocument.encode(recipe)).recipe)
    }

    @Test
    fun `an encoded selective step carries its key fields into the file`() {
        // 与上面那条 round trip 配对使用：编码器少写一个字段时解码器会拒掉它，round trip 照样红，
        // 但那时给出的理由是「预设含未知或不完整的步骤」——而实际是应用自己存的东西。
        // 键一旦不在文件里，轴/反选/羽化就在往返中静默变成默认值
        val recipe = EditRecipe.EMPTY.with(
            EditStep.Selective(
                RangeKey.of(KeyAxis.HUE, 0.85f, 1.15f, feather = 0.12f, inverted = true),
                SelectiveAdjust(exposure = 12)
            )
        )

        val text = EditRecipeDocument.encode(recipe)

        assertTrue("键的轴必须真的写进文件", text.contains("\"axis\":1"))
        assertTrue("反选必须真的写进文件", text.contains("\"inverted\":true"))
        assertTrue("羽化必须真的写进文件", text.contains("\"feather\":0.12"))
    }

    @Test
    fun `a degenerate selective key in a file is refused with a reason`() {
        // feather 0 在写入侧构造不出来，但文件可以手改。收下它等于让 GLSL 的
        // smoothstep(e0, e0, x) 除零——行为逐驱动不同，而用户只看到一层忽有忽无的色带
        //
        // 这条与上面那条 round trip 是一对：解码侧**还没有** selective 分支时，
        // 任何 selective 文件都被 `else -> null` 兜住而本条照样绿。真正让它有意义的是
        // 那条红的——它证明这条分支存在之后，本条才是「分支在拒绝坏键」而不是「什么都没读」
        val text =
            """{"format":1,"steps":[{"kind":"selective","axis":0,"from":0.4,"to":0.6,"feather":0.0,"inverted":false,"sel":{"exposure":10,"contrast":0,"saturation":0}}]}"""

        val result = EditRecipeDocument.decode(text)
        assertNotNull(result.failure)
        assertNull(result.recipe)
    }

    @Test
    fun `a luma range whose upper knot exceeds one is refused`() {
        // 亮度/饱和度的上拐点锁在 1 内（色相才允许到 2 跨红端接缝）。to + feather = 1.05
        // 会让第二个梯形在暗端绕回，而那正是本仓抓过的「颜色在暗部忽然反着走」
        val text =
            """{"format":1,"steps":[{"kind":"selective","axis":0,"from":0.95,"to":0.95,"feather":0.1,"inverted":false,"sel":{"exposure":10,"contrast":0,"saturation":0}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `a selective step with an unknown axis is refused rather than read as luma`() {
        // axis 是线格式里的整数，ordinal 会被直接喂给 KeyAxis.entries[a]。
        // 读到 3 就该拒：entries[3] 越界要么抛、要么被谁悄悄夹成最后一个（色相），
        // 后者意味着用户存的是亮度、套出来是色相
        // 钉的是「axis=3 被拒」，而拒绝**必须**由 toDomain 自己判：
        // 那里只接 IllegalArgumentException，entries[3] 的越界异常不该被吞掉
        val text =
            """{"format":1,"steps":[{"kind":"selective","axis":3,"from":0.4,"to":0.6,"feather":0.1,"inverted":false,"sel":{"exposure":10,"contrast":0,"saturation":0}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `a selective step with an out-of-range axis value is refused`() {
        // -1 那一支同样要拒：ordinal 越界在 Kotlin 里是抛，runCatching 之外的路径会炸掉调用方
        val text =
            """{"format":1,"steps":[{"kind":"selective","axis":-1,"from":0.4,"to":0.6,"feather":0.1,"inverted":false,"sel":{"exposure":10,"contrast":0,"saturation":0}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `an out-of-range selective slider is refused rather than coerced`() {
        // 三轴与全局调色同一条规矩：-100..100。渲染侧 selectiveGain 里有 coerceIn，
        // 收下 101 的话用户存的是 101、渲染出来的是 100，两份配方不是同一个东西
        val text =
            """{"format":1,"steps":[{"kind":"selective","axis":0,"from":0.4,"to":0.6,"feather":0.1,"inverted":false,"sel":{"exposure":101,"contrast":0,"saturation":0}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }

    @Test
    fun `a selective step without its inverted flag is refused`() {
        // inverted 不给默认值：默认成 false 的话，存的是「只推红色之外」的文件会读成
        // 「只推红色」——方向整个反过来，而预览上只看得见颜色变了
        val text =
            """{"format":1,"steps":[{"kind":"selective","axis":0,"from":0.4,"to":0.6,"feather":0.1,"sel":{"exposure":10,"contrast":0,"saturation":0}}]}"""

        assertNotNull(EditRecipeDocument.decode(text).failure)
    }
}
