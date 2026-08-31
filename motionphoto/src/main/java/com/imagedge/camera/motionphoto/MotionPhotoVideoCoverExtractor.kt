package com.imagedge.camera.motionphoto

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从视频抽取封面帧，压缩成 JPEG 临时文件。
 *
 * 本项目「视频 → LIVE 图」的输入只有视频，而 MotionPhotoComposer 需要「封面图 + 视频」两个输入，
 * 因此用本工具从视频中间帧自动生成封面。
 */
object MotionPhotoVideoCoverExtractor {

    /**
     * 抽取视频帧作为封面，压缩成 JPEG，写入 cacheDir 临时文件。
     *
     * @param timestampMs 封面时间（毫秒）。< 0 时保持旧行为取视频中间帧；
     *   ≥ 0 时使用用户自选的封面帧（「视频转 LIVE 图」选封面功能）。
     *   时间戳相对 [videoUri] 本身——选段流程下请传**裁剪后文件**上的时间。
     * @return 封面 JPEG 文件，失败抛 [MotionPhotoComposeException]
     */
    suspend fun extractCoverJpeg(
        context: Context,
        videoUri: Uri,
        timestampMs: Long = -1L,
    ): File =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val frameUs = if (timestampMs >= 0L) {
                    timestampMs * 1_000
                } else {
                    // 兜底：取中间帧
                    durationMs * 1_000 / 2
                }
                val bitmap = retriever.getFrameAtTime(
                    frameUs,
                    // 用户选帧要精确命中（就近解码目标帧）；默认中间帧取关键帧更快
                    if (timestampMs >= 0L) {
                        MediaMetadataRetriever.OPTION_CLOSEST
                    } else {
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    },
                ) ?: throw MotionPhotoComposeException("无法从视频抽取封面帧")

                val file = com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles.createWorkingFile(
                    cacheDir = context.cacheDir,
                    directoryName = "motion-photo-cover",
                    prefix = "livecover",
                    extension = "jpg",
                )
                try {
                    FileOutputStream(file).use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                            throw MotionPhotoComposeException("封面帧压缩为 JPEG 失败")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                file
            } finally {
                retriever.release()
            }
        }
}
