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
 */
internal fun sanitizePresetName(raw: String): String = raw
    .replace(Regex("""[\\/:.*?"<>|\u0000-\u001F]"""), "")
    .trim()
    .take(MAX_PRESET_NAME_CHARS)

/**
 * 名字 → 预设文件；不合法（归一化后为空、或含路径分隔符）返回 null，**不抛**。
 *
 * 含 `/` 或 `\` 的名字是**整名作废**，不是「删掉分隔符接着用」：归一化后
 * 「../../etc/passwd」变成「etcpasswd」，文件确实落在目录里、构不成穿越，
 * 但用户存的那个名字与之后列出来的那个名字已经不是同一个了——静默改名比拒绝更难查。
 * 作废之后读侧给原因、写侧抛 IllegalArgumentException（由 [EditRecipePresetStore.save] 包成 Result），
 * 每条拒绝路径都留下得见的理由。
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
    val text = runCatching { file.readText() }
        .getOrElse { return DecodeResult(null, "预设文件读不出来：${it.message}") }
    return EditRecipeDocument.decode(text)
}

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

    fun save(name: String, recipe: EditRecipe): Result<Unit> =
        runCatching { writePreset(dir, name, recipe) }
            .onFailure { AppLog.w(TAG, "预设保存失败：${it.message}") }

    fun read(name: String): DecodeResult = readPreset(dir, name)

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
