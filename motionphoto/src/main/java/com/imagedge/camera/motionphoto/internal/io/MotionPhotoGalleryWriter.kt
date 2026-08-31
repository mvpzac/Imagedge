package com.imagedge.camera.motionphoto.internal.io

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.MotionPhotoComposeResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

internal object MotionPhotoGalleryWriter {
    fun saveToGallery(
        context: Context,
        composeResult: MotionPhotoComposeResult,
    ): Uri {
        val resolver = context.contentResolver
        // 相册时间优先取封面 EXIF 的拍摄时间（EXIF 保留需求：成品时间线 = 源素材时间），
        // 无 EXIF 时退回导出时间——避免三拼/视频转 LIVE 成品全部显示为导出当天。
        val dateTaken = readCoverDateTaken(composeResult.motionPhotoFile)
            ?: System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, composeResult.displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Camera",
            )
            put(MediaStore.Images.Media.DATE_TAKEN, dateTaken)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw MotionPhotoComposeException(
                "Failed to create the destination item in the system gallery.",
            )

        return try {
            resolver.openOutputStream(itemUri)?.use { output ->
                composeResult.motionPhotoFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw MotionPhotoComposeException("Failed to write to the system gallery.")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (error: Throwable) {
            resolver.delete(itemUri, null, null)
            throw error
        }
    }

    /** 从 Motion Photo 封面（JPEG 段）读 EXIF 拍摄时间 → epoch ms；无则 null */
    private fun readCoverDateTaken(file: File): Long? = runCatching {
        val raw = ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: return null
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
    }.getOrNull()
}
