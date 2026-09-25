package com.imagedge.camera.data.transfer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 把落盘 Uri 翻译成人看得懂的位置（批次 C：「查看」失败时要报位置）
 *     version: 1.0
 * </pre>
 */

/**
 * MediaStore 用 `RELATIVE_PATH + DISPLAY_NAME`（就是相册里看到的那条路径），
 * SAF 树与其它来源回退到 Uri 自己的可读片段。
 *
 * 从 DownloadManager 里提出来是因为传输页的兜底提示也要说同一句话：
 * 「打不开」之后用户真正需要的是**那份文件在哪**，两处各写一遍迟早会说不一致。
 */
fun readablePathOf(context: Context, uri: Uri?): String {
    if (uri == null) return ""
    return try {
        if (uri.scheme == "content") {
            val projection = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val rel = c.getString(0)
                    val name = c.getString(1)
                    when {
                        rel != null && name != null -> "$rel$name"
                        name != null -> name
                        else -> uri.lastPathSegment ?: uri.toString()
                    }
                } else {
                    uri.lastPathSegment ?: uri.toString()
                }
            } ?: (uri.lastPathSegment ?: uri.toString())
        } else {
            uri.path ?: uri.toString()
        }
    } catch (_: Exception) {
        // 查不到（没权限 / 已被删 / 提供方不响应）时至少给一个能认出来的字符串，
        // 而不是让一条兜底提示自己再抛一次
        uri.lastPathSegment ?: uri.toString()
    }
}
