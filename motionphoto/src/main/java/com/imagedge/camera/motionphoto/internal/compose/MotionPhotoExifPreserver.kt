package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EXIF 保留（用户需求：视频转 LIVE 图 / 三拼 LIVE 图的导出保留原素材 EXIF）：
 *
 * - 图片源（实况图/照片）：把源图的 MAKE/MODEL/拍摄时间/曝光参数/焦距注入封面 JPEG，
 *   使成品 Motion Photo 在系统相册中能显示与源素材一致的拍摄信息；
 * - 视频源：视频容器一般无 EXIF（ExifInterface 不支持解析 MP4），退化为从视频元数据
 *   （MediaMetadataRetriever.METADATA_KEY_DATE）与 MediaStore DATE_TAKEN 取拍摄时间，
 *   保证成品在相册中的时间线正确。
 *
 * 注入时机在 MotionPhotoStillImagePreparer（JPEG 复用/转码落盘之后、打包 MP 之前）——
 * 封面 JPEG 是我们生成的普通 JPEG，ExifInterface.saveAttributes() 重写不会破坏 MP 结构。
 */
internal object MotionPhotoExifPreserver {

    /** 把 [sourceUri] 的 EXIF 注入 [targetJpeg]（找不到源信息时静默跳过，绝不抛异常） */
    fun injectExifFrom(context: Context, sourceUri: Uri, targetJpeg: File) {
        val target = runCatching { ExifInterface(targetJpeg.absolutePath) }.getOrNull() ?: return

        // 1) 图片源：尝试完整 EXIF（实况图/相机照片）
        val srcExif = runCatching {
            context.contentResolver.openFileDescriptor(sourceUri, "r")?.use {
                ExifInterface(it.fileDescriptor)
            }
        }.getOrNull()
        if (srcExif != null && !srcExif.getAttribute(ExifInterface.TAG_MODEL).isNullOrEmpty()) {
            copyIfPresent(srcExif, target, ExifInterface.TAG_MAKE)
            copyIfPresent(srcExif, target, ExifInterface.TAG_MODEL)
            copyIfPresent(srcExif, target, ExifInterface.TAG_DATETIME_ORIGINAL)
            copyIfPresent(srcExif, target, ExifInterface.TAG_EXPOSURE_TIME)
            copyIfPresent(srcExif, target, ExifInterface.TAG_F_NUMBER)
            copyIfPresent(srcExif, target, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            copyIfPresent(srcExif, target, ExifInterface.TAG_ISO_SPEED_RATINGS)
            copyIfPresent(srcExif, target, ExifInterface.TAG_FOCAL_LENGTH)
            copyIfPresent(srcExif, target, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            copyIfPresent(srcExif, target, ExifInterface.TAG_LENS_MODEL)
            copyIfPresent(srcExif, target, ExifInterface.TAG_FLASH)
            // 封面是全新渲染的 JPEG，方向一律为 1（不再携带源图的旋转标记）
            target.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        } else {
            // 2) 视频源：取拍摄时间（视频元数据 creation_time / MediaStore DATE_TAKEN）
            val epochMs = videoCaptureEpochMs(context, sourceUri)
            val dateTime = epochMs?.let { formatExifDateTime(it) }
            if (dateTime != null) {
                target.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
                target.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
                target.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            }
        }
        runCatching { target.saveAttributes() }
            .onFailure { e ->
                android.util.Log.w(
                    "MotionPhotoExif", "EXIF 注入失败：${e.message}"
                )
            }
    }

    private fun copyIfPresent(
        src: ExifInterface,
        dst: ExifInterface,
        tag: String,
    ) {
        val v = src.getAttribute(tag) ?: return
        if (v.isNotBlank()) dst.setAttribute(tag, v)
    }

    /** 视频拍摄时间（epoch ms）：先读容器元数据，再查 MediaStore DATE_TAKEN 兜底 */
    private fun videoCaptureEpochMs(context: Context, uri: Uri): Long? {
        val fromMeta = runCatching {
            val date = MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            }
            parseMetadataDate(date)
        }.getOrNull()
        if (fromMeta != null) return fromMeta

        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }

    /** 解析 MediaMetadataRetriever 的日期格式，失败返回 null */
    private fun parseMetadataDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        // 常见两类：
        //   20260831T191500.000Z / 20260831T191500Z（字面 Z = UTC）
        //   20260831T191500.000+0800（数字时区偏移——此前盲拼 " Z" 解析必败，拍摄时间被丢弃）
        return if (s.endsWith("Z")) {
            parseWith(s, "yyyyMMdd'T'HHmmss.SSS'Z'", utc = true)
                ?: parseWith(s, "yyyyMMdd'T'HHmmss'Z'", utc = true)
        } else {
            parseWith(s, "yyyyMMdd'T'HHmmss.SSSZ", utc = false)
                ?: parseWith(s, "yyyyMMdd'T'HHmmssZ", utc = false)
        }
    }

    private fun parseWith(value: String, pattern: String, utc: Boolean): Long? = runCatching {
        SimpleDateFormat(pattern, Locale.US).apply {
            if (utc) timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(value)?.time
    }.getOrNull()

    /** epoch ms → EXIF 日期时间字符串（"yyyy:MM:dd HH:mm:ss"） */
    private fun formatExifDateTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        return fmt.format(Date(epochMs))
    }

    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }
}
