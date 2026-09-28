package com.imagedge.camera.data.edit

import android.content.Context
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.image.EditRecipe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 预设扩展名。目录里只有这个后缀的文件算预设，其余忽略 */
internal const val PRESET_EXTENSION = "json"

private const val MAX_PRESET_NAME_CHARS = 60

/**
 * 名字归一化：去掉路径分隔、控制字符与**点号**、限长、trim。
 *
 * 名字直接拼进文件路径，而预设名是用户输入的——不归一化就等于
 * 一个「../../etc/passwd」会把文件写到预设目录外面。
 *
 * 点号也一并去掉，是因为读回来的名字走的是 `File.nameWithoutExtension`：
 * 留着点号的话「我的.v2」落盘成 `我的.v2.json`，列表里读回来却是「我的」，
 * 再按这个名字去读就找不到文件——预设存在了却永远套不出来，且不报任何错。
 *
 * **先截断再 trim**，反过来会留下一个尾巴空格：59 个字符 + 空格 + 更多，截到 60 之后
 * 末尾是个空格，而 `presetFileFor` 每次都会再 trim 掉它 → 写进去的文件叫「…​ .json」、
 * 列出来的是「…」、再去按列出的名字读又解析成另一个文件——正是点号那条要防的
 * 「存在但永远套不出来」，只是换了个诱饵。
 */
internal fun sanitizePresetName(raw: String): String = raw
    .replace(Regex("""[\\/:.*?"<>|\u0000-\u001F]"""), "")
    .take(MAX_PRESET_NAME_CHARS)
    .trim()

/**
 * 名字 → 预设文件；不合法（归一化后为空、或含路径分隔符）返回 null，**不抛**。
 *
 * 含 `/` 或 `\` 的名字是**整名作废**，不是「删掉分隔符接着用」：归一化后
 * 「../../etc/passwd」变成「etcpasswd」，文件确实落在目录里、构不成穿越，
 * 但用户存的那个名字与之后列出来的那个名字已经不是同一个了——静默改名比拒绝更难查。
 * 作废之后读侧给原因、写侧抛 IllegalArgumentException（由 [EditRecipePresetStore.save] 包成 Result），
 * 两条都留下得见的理由。删除走 [EditRecipePresetStore.delete]，只回 Boolean，
 * 但调用方先用 [presetExists] 分过一次，所以「不存在」「删不掉」「已删除」在界面上是三句话
 * （见 PhotoEditViewModel.deletePreset）。
 */
internal fun presetFileFor(dir: File, name: String): File? {
    if (name.contains('/') || name.contains('\\')) return null
    val safe = sanitizePresetName(name)
    if (safe.isEmpty()) return null
    return File(dir, "$safe.$PRESET_EXTENSION")
}

internal fun File.listPresetNames(): List<String> =
    listFiles { f -> f.isFile && f.extension == PRESET_EXTENSION }
        ?.mapNotNull { it.nameWithoutExtension.takeIf(String::isNotBlank) }
        // 只保留**能往返**的名字：手放的「我的.v2.json」按后缀会列成「我的.v2」，
        // 而读/套/删都会再归一化成「我的v2」→ 列出来却永远用不了。过滤条件与读侧同一条
        // （presetFileFor 的归一化 + isFile），列表因此不可能出现一个点不动的名字
        ?.filter { name -> presetFileFor(this, name)?.isFile == true }
        ?.sorted()
        ?: emptyList()

/** 写入。目录不存在时创建；名字不合法抛 IllegalArgumentException（由调用方包成 Result） */
internal fun writePreset(dir: File, name: String, recipe: EditRecipe) {
    val file = presetFileFor(dir, name) ?: throw IllegalArgumentException("预设名称无效")
    dir.mkdirs()
    file.writeText(EditRecipeDocument.encode(recipe))
}

/** 读取。任何失败都返回带原因的结果，不抛异常 */
internal fun readPreset(dir: File, name: String): DecodeResult {
    val file = presetFileFor(dir, name) ?: return DecodeResult(null, "预设名称无效")
    if (!file.isFile) return DecodeResult(null, "预设「${sanitizePresetName(name)}」不存在")
    // 只接 IOException：兜住一切会把 OOM 这类程序错误也翻成一句「读不出来：null」给用户看
    val text = try {
        file.readText()
    } catch (e: java.io.IOException) {
        return DecodeResult(null, "预设文件读不出来：${e.message}")
    }
    return EditRecipeDocument.decode(text)
}

/**
 * 这个名字是否已经占用。**按落盘身份问磁盘**，不是拿输入框里的原文去比列表：
 * 归一化会去掉点号并截到 60 字符，「我的.v2」与「我的v2」是同一个预设，
 * 只有走这条路才认得出来——界面上比 `presets` 列表会漏掉这一类撞名。
 */
internal fun presetExists(dir: File, name: String): Boolean =
    presetFileFor(dir, name)?.isFile == true

/**
 * 编辑预设落盘：`filesDir/edit_presets/<名字>.json`。
 *
 * 与 `UserLutStore`（`filesDir/luts`）同构。这一轮**刻意不碰 SAF**：预设是辅助文件，
 * 不是用户资产，读它不该要求用户选目录授权；要分享给别人是下一轮的事。
 */
@Singleton
class EditRecipePresetStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "edit_presets")

    fun list(): List<String> = dir.listPresetNames()

    fun exists(name: String): Boolean = presetExists(dir, name)

    fun save(name: String, recipe: EditRecipe): Result<Unit> =
        runCatching { writePreset(dir, name, recipe) }
            .onFailure { AppLog.w(TAG, "预设保存失败：${it.message}") }

    fun read(name: String): DecodeResult = readPreset(dir, name)

    /**
     * 删除。**只返回 Boolean 是本层的短板**：名字无效与 unlink 失败在界面上会塌成同一句话，
     * 真正的区别只进 AppLog。调用方（[PhotoEditViewModel.deletePreset]）先用 [exists] 分一次，
     * 让「不存在」与「删不掉」在界面上是两条原因——多一次 stat 换一个看得懂的回执，值。
     */
    fun delete(name: String): Boolean {
        val file = presetFileFor(dir, name) ?: return false
        val ok = file.delete()
        if (!ok && file.exists()) AppLog.w(TAG, "预设删除失败：$name")
        return ok
    }

    private companion object {
        const val TAG = "edit"
    }
}
