package com.imagedge.camera.data.edit

import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.NormRect
import com.imagedge.camera.lut.ColorAdjust
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 格式版本。加字段就 +1；读不认识的版本一律明确拒绝，不做「尽力猜」 */
private const val FORMAT_VERSION = 1

@Serializable
private data class StoredColor(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    val shadows: Int = 0,
    val highlights: Int = 0,
)

@Serializable
private data class StoredRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
)

/**
 * 一条步骤的线格式。用「可空字段 + kind 标签」而不是多态序列化，
 * 是因为 kotlinx 的多态需要类型名写在文件里，而那等于把内部类名变成对外格式。
 */
@Serializable
private data class StoredStep(
    val kind: String,
    val degrees: Float? = null,
    val horizontal: Boolean? = null,
    val rect: StoredRect? = null,
    val color: StoredColor? = null,
    val key: String? = null,
    val strength: Int? = null,
    val sel: StoredSelective? = null,
)

@Serializable
private data class StoredSelective(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
)

@Serializable
private data class StoredRecipe(
    val format: Int = FORMAT_VERSION,
    val steps: List<StoredStep> = emptyList(),
)

/** 解码结果：要么有配方，要么有原因，不允许两者都没有 */
data class DecodeResult(val recipe: EditRecipe?, val failure: String?) {
    val isUsable: Boolean get() = recipe != null && failure == null
}

/**
 * 编辑配方的文本编解码（纯函数，全部在 JVM 上可验证）。
 *
 * 纪律与 `data/profile/PresetDocument.kt` 一致：预设文件来自磁盘，是不可信输入，
 * 未知键、未知步骤、越界数字、超长文本都必须**明确拒绝并给原因**，
 * 而不是宽容地读出一个半成品配方去改用户的照片。
 *
 * **线格式带的是整份配方，含几何四步**。这是有意的：
 * 预设要能原样 reopen 才能改、才能再存，只存颜色与滤镜等于把「存下来的」和「用的时候」
 * 变成两种东西。套用侧只取 `rank > 0` 的部分（见 PhotoEditViewModel.presetAppliedTo 与
 * savePreset，两处判据相同）——**不是** `rank != 0`：今天等价只因 rank 没有负值，
 * 写 `> 0` 让「几何」这件事由「排在 0」这一个事实定义。别为了别的口径去把 `encode` 裁短。
 */
object EditRecipeDocument {

    /** 一份配方文本正常是几百字节；超上限直接判为不可信 */
    const val MAX_TEXT_CHARS = 16 * 1024

    private const val MAX_STEPS = 16
    /** 滤镜 key 的长度上限；写侧（PhotoEditViewModel.savePreset）用同一个数把关 */
    const val MAX_KEY_CHARS = 120

    private val json = Json {
        // 必须 true：版本号要真的写进文件。默认 false 时 `format = 1`（等于默认值）会被省略，
        // 读侧就拿不到版本；「未来版本必须拒绝」那条测试会**直接红**而不是空跑——
        // 它的 replaceFirst 匹配不到 format、文本原样进解码、读回来仍是版本 1。
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encode(recipe: EditRecipe): String = json.encodeToString(
        StoredRecipe.serializer(),
        StoredRecipe(format = FORMAT_VERSION, steps = recipe.steps.map { it.toStored() })
    )

    fun decode(text: String?): DecodeResult {
        if (text.isNullOrBlank()) return DecodeResult(null, "预设内容为空")
        if (text.length > MAX_TEXT_CHARS) {
            return DecodeResult(null, "预设过大（${text.length} 字符，上限 $MAX_TEXT_CHARS）")
        }
        val stored = runCatching { json.decodeFromString(StoredRecipe.serializer(), text) }
            .getOrElse { return DecodeResult(null, "不是受支持的预设格式，或文件已损坏") }

        if (stored.format != FORMAT_VERSION) {
            return DecodeResult(null, "不支持的预设版本 ${stored.format}（本应用读写版本 $FORMAT_VERSION）")
        }
        if (stored.steps.size > MAX_STEPS) {
            return DecodeResult(null, "步骤数量异常（${stored.steps.size} > $MAX_STEPS）")
        }

        val steps = mutableListOf<EditStep>()
        for (item in stored.steps) {
            val step = item.toDomain()
            if (step == null) return DecodeResult(null, "预设含未知或不完整的步骤「${item.kind}」")
            steps += step
        }
        // 不变量只有 EditRecipe.init 那一份：这里再抄一遍「同身份至多一步 / rank 非降」就是
        // 两份规则，将来 init 改了自己不会跟着改。所以是**接住它抛的异常并翻译成原因**——
        // 文件来自磁盘，是不可信输入，一个能解析但不合规矩的文件不该把调用方炸崩。
        // 只接 IllegalArgumentException：init 里真出了 bug（NPE 之类）要往上抛，
        // 用 runCatching 兜住一切会把程序错误报成「你的预设文件有问题」
        val recipe = try {
            EditRecipe(steps)
        } catch (e: IllegalArgumentException) {
            return DecodeResult(null, "预设里的步骤不合规矩：${e.message}")
        }
        return DecodeResult(recipe, null)
    }

    private fun EditStep.toStored(): StoredStep = when (this) {
        is EditStep.Straighten -> StoredStep("straighten", degrees = degrees)
        is EditStep.Rotate -> StoredStep("rotate", degrees = degrees)
        is EditStep.Flip -> StoredStep("flip", horizontal = horizontal)
        is EditStep.Crop -> StoredStep("crop", rect = StoredRect(rect.left, rect.top, rect.right, rect.bottom))
        is EditStep.Color -> StoredStep(
            "color",
            color = StoredColor(
                adjust.exposure, adjust.contrast, adjust.saturation,
                adjust.temperature, adjust.tint, adjust.shadows, adjust.highlights
            )
        )
        is EditStep.Lut -> StoredStep("lut", key = key, strength = strength)
        // 键与三轴的线格式字段在 T5 一次补齐（解码侧与失败即封闭的校验同批）；这里先把编码侧
        // 的位置占住——它是穷举 when，编译器逼着每个新步骤都表态，不能靠「以后再说」
        is EditStep.Selective -> StoredStep("selective", sel = StoredSelective(adjust.exposure, adjust.contrast, adjust.saturation))
    }

    private fun StoredStep.toDomain(): EditStep? = when (kind) {
        "straighten" -> degrees?.takeIf { it in -45f..45f }?.let { EditStep.Straighten(it) }
        // 只收 90° 的整数倍：界面上唯一写这个槽位的地方就是 `EditStep.Rotate(90f * turns)`，
        // 而派生状态按 (degrees / 90f).toInt() 取整——收一个手改的 45°，画面真的转 45°，
        // quarterTurns 却报 0：hasGeometryEdits 说「没动过构图」，裁剪页拿到的是错的画面比例。
        // 与其让派生值与真值打架，不如在读侧就把这种文件拒掉
        "rotate" -> degrees?.takeIf { it in -360f..360f && it % 90f == 0f }?.let { EditStep.Rotate(it) }
        "flip" -> horizontal?.let { EditStep.Flip(it) }
        "crop" -> rect?.let {
            // 定点检查够用：NaN 到不了这一层——kotlinx 的 allowSpecialFloatingPointValues
            // （默认 false）在 decodeFloat 里就拒掉非有限值，与 isLenient 无关，有用例钉着；
            // 而 ±Infinity 同样在那一层被拒，退一步说也会被 sanitized() 钳回边界、与原判据不等 → 拒
            val candidate = NormRect(it.left, it.top, it.right, it.bottom)
            if (candidate.sanitized() != candidate) null else EditStep.Crop(candidate)
        }
        "color" -> color?.let {
            if (!it.everySliderInRange()) null
            else EditStep.Color(
                // 用具名参数：ColorAdjust 现在有七个字段（exposure/contrast/saturation/
                // temperature/tint/shadows/highlights），按位置写等于把顺序也变成线格式的一部分
                ColorAdjust(
                    exposure = it.exposure, contrast = it.contrast, saturation = it.saturation,
                    temperature = it.temperature, tint = it.tint,
                    shadows = it.shadows, highlights = it.highlights
                )
            )
        }
        "lut" -> {
            val lutKey = key?.takeIf { it.isNotBlank() && it.length <= MAX_KEY_CHARS }
            val value = strength?.takeIf { it in 0..100 }
            if (lutKey == null || value == null) null else EditStep.Lut(lutKey, value)
        }
        else -> null
    }

    private fun StoredColor.everySliderInRange(): Boolean =
        listOf(exposure, contrast, saturation, temperature, tint, shadows, highlights)
            .all { it in -100..100 }
}
