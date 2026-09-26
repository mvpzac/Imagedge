package com.imagedge.camera.data.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 「照片存到哪儿」的唯一所有者：SAF 目录 + 给人看的说明
 *     version: 1.0
 * </pre>
 */

/**
 * 保存位置的读取与措辞。
 *
 * 为什么要有这个文件：同一个偏好键 `"download_tree_uri"` 此前被**四处各自硬写**
 * （设置页、相机仓库两处写盘、照片编辑器写库），改一次要记得四个地方——
 * 漏掉一处的表现是「设置里换了目录，但某条路径还往老地方写」，
 * 那种 bug 只在真机上、且只在用户换过目录之后才看得见。
 *
 * 措辞也放这里（新手手册 §6：不能为了短文案省去保存位置）：设置页要说、
 * 选择照片的底栏也要说，两处各写一份迟早会不一致。
 */
object DownloadLocation {

    private const val PREFERENCES = "settings"
    private const val KEY_TREE = "download_tree_uri"

    /** 默认落点：应用自己的相册目录，系统相册能看见 */
    const val DEFAULT_LABEL = "默认：DCIM/Imagedge（系统相册）"

    /** 用户选的 SAF tree uri；null = 用默认目录 */
    fun treeUri(context: Context): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_TREE, null)

    fun setTreeUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE, uri.toString())
            .apply()
    }

    fun clearTreeUri(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TREE)
            .apply()
    }

    /** 给人看的那一句。读不出目录 id 时也要说清「在自定义目录」，不能退回默认文案骗人 */
    fun label(context: Context): String {
        val uriStr = treeUri(context) ?: return DEFAULT_LABEL
        return runCatching {
            "已选择：" + DocumentsContract.getTreeDocumentId(uriStr.toUri())
        }.getOrDefault("已选择自定义目录")
    }
}
