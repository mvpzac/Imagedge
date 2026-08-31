package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MimeTypes
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.internal.format.MotionPhotoMimeSniffer
import com.imagedge.camera.motionphoto.internal.format.QuickTimeMp4Rewriter
import com.imagedge.camera.motionphoto.internal.format.VIDEO_QUICKTIME
import com.imagedge.camera.motionphoto.internal.format.indexOfSubarray
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles
import java.io.File

internal object MotionPhotoVideoPreparer {
    private val appleLivePhotoMarkers = listOf(
        "com.apple.quicktime.live-photo-info".toByteArray(Charsets.US_ASCII),
        "com.apple.quicktime.still-image-time".toByteArray(Charsets.US_ASCII),
        "com.apple.quicktime.live-photo-still-image-transform".toByteArray(Charsets.US_ASCII),
    )

    fun prepare(
        context: Context,
        videoUri: Uri,
        coverTimestampUs: Long = -1L,
    ): PreparedVideo {
        // 音轨兼容归一化：PCM 等不兼容音轨先转 AAC remux（时间戳元数据仍从原始 Uri 解析）
        val effectiveUri = AudioNormalizer.ensureCompatibleAudio(context, videoUri)
        return when (val sourceMimeType = MotionPhotoMimeSniffer.sniffVideoMimeType(context, effectiveUri)) {
            MimeTypes.VIDEO_MP4 -> {
                val copiedFile = copySourceVideo(context, effectiveUri, "mp4")
                PreparedVideo(
                    sourceMimeType = sourceMimeType,
                    outputMimeType = MimeTypes.VIDEO_MP4,
                    processingDescription = "Reuse the original MP4 as-is",
                    preparedFile = copiedFile,
                    presentationTimestampUs = coverTimestampUs.takeIf { it >= 0 }
                        ?: resolvePresentationTimestampUs(context, videoUri, copiedFile),
                    oplusPresentationTimestampUs = resolveOplusPresentationTimestampUs(copiedFile, coverTimestampUs),
                )
            }

            VIDEO_QUICKTIME -> {
                val copiedFile = copySourceVideo(context, effectiveUri, "mov")
                val preparedFile = if (containsAppleLivePhotoMetadata(copiedFile)) {
                    rewriteAppleQuickTimeToMp4(context, copiedFile)
                } else {
                    copiedFile
                }
                val outputMimeType = if (preparedFile == copiedFile) {
                    VIDEO_QUICKTIME
                } else {
                    MimeTypes.VIDEO_MP4
                }
                val processingDescription = if (preparedFile == copiedFile) {
                    "Reuse the original MOV as-is"
                } else {
                    "Rewrite to MP4 in a WeChat-style path (preserving the Apple metadata track)"
                }
                PreparedVideo(
                    sourceMimeType = sourceMimeType,
                    outputMimeType = outputMimeType,
                    processingDescription = processingDescription,
                    preparedFile = preparedFile,
                    presentationTimestampUs = coverTimestampUs.takeIf { it >= 0 }
                        ?: resolvePresentationTimestampUs(context, videoUri, preparedFile),
                    oplusPresentationTimestampUs = resolveOplusPresentationTimestampUs(preparedFile, coverTimestampUs),
                )
            }

            else -> {
                throw MotionPhotoComposeException(
                    "Only BMFF video containers are supported right now, meaning MP4 or QuickTime/MOV. Detected video type: $sourceMimeType",
                )
            }
        }
    }

    /** 最长 marker 52 字节；滑窗保留该长度的重叠区，防止 marker 跨块边界漏检 */
    private const val METADATA_PROBE_OVERLAP = 64

    private fun containsAppleLivePhotoMetadata(file: File): Boolean {
        // 流式探测：readBytes() 会把数百 MB 视频整体载入堆，大视频直接 OOM
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            var tail = ByteArray(0)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) return false
                val window = tail + buffer.copyOf(read)
                if (appleLivePhotoMarkers.any { indexOfSubarray(window, it) != -1 }) {
                    return true
                }
                tail = if (window.size > METADATA_PROBE_OVERLAP) {
                    window.copyOfRange(window.size - METADATA_PROBE_OVERLAP, window.size)
                } else {
                    window
                }
            }
        }
    }

    private fun copySourceVideo(
        context: Context,
        videoUri: Uri,
        extension: String,
    ): File {
        return MotionPhotoTempFiles.createWorkingFile(
            cacheDir = context.cacheDir,
            directoryName = "motion-photo-work",
            prefix = "motion-photo-video",
            extension = extension,
        ).apply {
            context.contentResolver.openInputStream(videoUri)?.use { input ->
                outputStream().use(input::copyTo)
            } ?: throw MotionPhotoComposeException("Failed to read the video file.")
        }
    }

    private fun rewriteAppleQuickTimeToMp4(
        context: Context,
        sourceFile: File,
    ): File {
        val rewrittenBytes = QuickTimeMp4Rewriter.rebrand(sourceFile.readBytes())
        return MotionPhotoTempFiles.createWorkingFile(
            cacheDir = context.cacheDir,
            directoryName = "motion-photo-work",
            prefix = "motion-photo-video",
            extension = "mp4",
        ).apply {
            writeBytes(rewrittenBytes)
        }
    }

    private fun resolvePresentationTimestampUs(
        context: Context,
        sourceVideoUri: Uri,
        preparedFile: File,
    ): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(preparedFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: run {
                        retriever.setDataSource(context, sourceVideoUri)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    }
                    ?: return@runCatching 0L
                durationMs * 1_000 / 2
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    private fun resolveOplusPresentationTimestampUs(preparedFile: File, coverTimestampUs: Long): Long {
        return if (containsAppleLivePhotoMetadata(preparedFile)) {
            0L
        } else {
            coverTimestampUs.takeIf { it >= 0 } ?: resolveMidpointPresentationTimestampUs(preparedFile)
        }
    }

    private fun resolveMidpointPresentationTimestampUs(preparedFile: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(preparedFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: return@runCatching 0L
                durationMs * 1_000 / 2
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }
}
