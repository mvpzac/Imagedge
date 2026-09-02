package com.imagedge.camera.share

import android.content.Intent
import android.net.Uri

/**
 * 系统分享 Intent 构造。
 *
 * 走 Android Sharesheet（ACTION_SEND / ACTION_SEND_MULTIPLE）：
 * 由系统弹出统一的分享面板，用户选择微信、小红书、Instagram 等任意目标，
 * 应用无需集成各家 SDK，也不需要任何额外权限——
 * 这是「分享」环节成本最低、覆盖最广的实现方式。
 */
object ShareIntents {

    /**
     * 分享单张。
     * @param uri 已通过 FileProvider 授权的 content:// Uri（见 [ExportManager]）
     * @param mime 媒体类型，如 `image/jpeg`
     * @param text 可选的附带文案
     */
    fun single(uri: Uri, mime: String, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
        }

    /**
     * 分享多张。
     *
     * 注意：部分接收方对批量分享的支持不一致（有的只取第一张），
     * 属于系统行为，应用层无法保证——UI 上不必为此做特殊处理。
     */
    fun multiple(uris: List<Uri>, mime: String, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
        }

    /**
     * 包装成系统分享面板（始终显示选择器）。
     * @param title 选择器标题，传 null 则由系统使用默认标题
     */
    fun chooser(intent: Intent, title: String?): Intent =
        Intent.createChooser(intent, title)

    /** 用系统默认应用打开（用于「查看导出结果」） */
    fun view(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
