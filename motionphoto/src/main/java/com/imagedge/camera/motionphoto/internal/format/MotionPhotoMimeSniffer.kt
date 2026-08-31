package com.imagedge.camera.motionphoto.internal.format

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import java.util.Locale

internal const val VIDEO_QUICKTIME: String = "video/quicktime"

internal object MotionPhotoMimeSniffer {
    fun sniffImageMimeType(
        context: Context,
        imageUri: Uri,
    ): String {
        readHeader(context, imageUri)?.let(::inferHeaderImageMime)?.let { return it }

        val mimeType = context.contentResolver.getType(imageUri)?.lowercase(Locale.US)
        val pathHint = imageUri.lastPathSegment?.lowercase(Locale.US)
        return when {
            mimeType != null -> mimeType
            pathHint?.endsWith(".jpg") == true || pathHint?.endsWith(".jpeg") == true -> "image/jpeg"
            pathHint?.endsWith(".heic") == true -> "image/heic"
            pathHint?.endsWith(".heif") == true -> "image/heif"
            pathHint?.endsWith(".avif") == true -> "image/avif"
            else -> "application/octet-stream"
        }
    }

    fun sniffVideoMimeType(
        context: Context,
        videoUri: Uri,
    ): String {
        readHeader(context, videoUri)?.let(::inferHeaderIsoBaseMediaMime)?.let { return it }

        val mimeType = context.contentResolver.getType(videoUri)?.lowercase(Locale.US)
        val pathHint = videoUri.lastPathSegment?.lowercase(Locale.US)
        return when {
            mimeType == VIDEO_QUICKTIME || pathHint?.endsWith(".mov") == true -> VIDEO_QUICKTIME
            mimeType == MimeTypes.VIDEO_MP4 || pathHint?.endsWith(".mp4") == true -> MimeTypes.VIDEO_MP4
            else -> mimeType ?: "application/octet-stream"
        }
    }

    fun inferHeaderImageMime(bytes: ByteArray): String? {
        return when {
            looksLikeJpeg(bytes, 0) -> "image/jpeg"
            looksLikeFileType(bytes, signature = "ftypheic") ||
                looksLikeFileType(bytes, signature = "ftypheix") -> "image/heic"
            looksLikeFileType(bytes, signature = "ftypheif") ||
                looksLikeFileType(bytes, signature = "ftypheim") -> "image/heif"
            looksLikeFileType(bytes, signature = "ftypavif") -> "image/avif"
            else -> null
        }
    }

    fun inferHeaderIsoBaseMediaMime(bytes: ByteArray): String? {
        if (!looksLikeIsoBaseMedia(bytes, 0) || bytes.size < 12) {
            return null
        }
        return inferIsoBaseMediaMime(bytes)
    }

    fun inferPrimaryMime(sourceBytes: ByteArray): String {
        return when {
            looksLikeJpeg(sourceBytes, 0) -> "image/jpeg"
            looksLikeFileType(sourceBytes, signature = "ftypheic") -> "image/heic"
            looksLikeFileType(sourceBytes, signature = "ftypheix") -> "image/heic"
            looksLikeFileType(sourceBytes, signature = "ftypavif") -> "image/avif"
            else -> "image/jpeg"
        }
    }

    fun inferSecondaryMime(
        sourceBytes: ByteArray,
        offset: Int,
    ): String {
        return when {
            looksLikeJpeg(sourceBytes, offset) -> "image/jpeg"
            looksLikeIsoBaseMedia(sourceBytes, offset) -> inferIsoBaseMediaMime(sourceBytes, offset)
            else -> "application/octet-stream"
        }
    }

    private fun readHeader(
        context: Context,
        sourceUri: Uri,
        size: Int = 16,
    ): ByteArray? {
        return context.contentResolver.openInputStream(sourceUri)?.use { input ->
            val header = ByteArray(size)
            val bytesRead = input.read(header)
            if (bytesRead >= 12) {
                header.copyOf(bytesRead)
            } else {
                null
            }
        }
    }
}
