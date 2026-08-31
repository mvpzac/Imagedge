package com.imagedge.camera.data.lut

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 用户 LUT 文件存储（filesDir/luts）——设置页导入/导出/删除，
 *              编辑页读取合并为可用滤镜。
 *     version: 1.0
 * </pre>
 */
@Singleton
class UserLutStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File get() = File(context.filesDir, "luts").apply { mkdirs() }

    /** 用户声明的 LUT 适用类型（name → LutType），与文件分开存，导入后可重新声明 */
    private val typePrefs get() =
        context.getSharedPreferences("lut_types", Context.MODE_PRIVATE)

    /** 某个 LUT 的适用类型：优先用户声明，其次按文件名推断 */
    fun typeOf(name: String): LutType {
        val declared = typePrefs.getString(name, null)
        if (declared != null) {
            runCatching { LutType.valueOf(declared) }.getOrNull()?.let { return it }
        }
        return LutType.fromFileName(name)
    }

    /** 声明某个 LUT 的适用类型（导入后由用户选择，决定它归入编辑页哪一排） */
    fun setType(name: String, type: LutType) {
        typePrefs.edit().putString(name, type.name).apply()
    }

    /** 已保存的 LUT 文件名列表（按名称排序） */
    fun list(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension.equals("cube", true) }
            ?.map { it.name }?.sorted() ?: emptyList()

    /** 按类型筛选 */
    fun listByType(type: LutType): List<String> = list().filter { typeOf(it) == type }

    /** 从系统选择器导入（保留原文件名；同名覆盖） */
    fun import(uri: Uri): Result<String> = runCatching {
        val rawName = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.cube"
        // 显示名来自外部输入：剥掉路径成分，防 "../x.cube" 逃逸 luts 目录
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            .takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "imported_${System.currentTimeMillis()}.cube"
        val target = File(dir, name)
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw IllegalStateException("非法文件名：$rawName")
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取所选文件")
        } catch (e: Exception) {
            // 失败时清掉半截文件，避免损坏文件出现在滤镜列表里
            runCatching { target.delete() }
            throw e
        }
        name
    }

    /** 删除（同时清除类型声明，避免残留映射到不存在的文件） */
    fun delete(name: String): Boolean {
        val ok = File(dir, name).delete()
        if (ok) typePrefs.edit().remove(name).apply()
        return ok
    }

    /** 导出到用户指定位置（SAF CreateDocument 返回的 uri） */
    fun exportTo(name: String, target: Uri): Unit {
        context.contentResolver.openOutputStream(target)?.use { output ->
            File(dir, name).inputStream().use { it.copyTo(output) }
        } ?: throw IllegalStateException("无法写入目标位置")
    }

    fun exists(name: String): Boolean = File(dir, name).isFile

    fun readText(name: String): String = File(dir, name).readText()

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}
