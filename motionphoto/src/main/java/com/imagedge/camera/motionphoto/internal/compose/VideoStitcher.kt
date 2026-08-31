package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 多段视频顺序拼接器（LIVE 三拼，批次 B）。
 *
 * 线程模型与 [VideoTrimmer] 一致：Transformer 的 build/start/cancel 必须在
 * applicationLooper（主线程）调用，cancel 的取消通知 post 回主线程。
 *
 * **无缝拼接的关键约束**：序列中各段的视频分辨率/帧率必须一致，否则拼接点
 * 会出现跳变甚至导出失败。调用方（LiveTriptychViewModel）负责先把每段转码成
 * 统一规格（H.264 / 1920x1080，经 16:9 横屏源校验），本器只做序列组装。
 *
 * 每段可独立开关声音：[Segment.audioOn] 为 false 时该段 `setRemoveAudio(true)`，
 * 拼接产物中该段无音轨（静音段在播放中表现为无声，其余段声音正常）。
 */
internal object VideoStitcher {

    /** 拼接段：视频 Uri + 是否保留声音 */
    data class Segment(val uri: Uri, val audioOn: Boolean)

    /**
     * 顺序拼接 [segments] 为单个 MP4。
     * 输入约定：各段已统一为 H.264/1920x1080（由调用方经 VideoTrimmer 归一）。
     */
    suspend fun stitch(
        context: Context,
        segments: List<Segment>,
    ): File {
        require(segments.size >= 2) { "拼接至少需要 2 段视频" }
        val output = withContext(Dispatchers.IO) {
            MotionPhotoTempFiles.createWorkingFile(
                cacheDir = context.cacheDir,
                directoryName = "motion-photo-stitch",
                prefix = "motion-photo-stitched",
                extension = "mp4",
            )
        }
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            if (continuation.isActive) continuation.resume(output)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    MotionPhotoComposeException(
                                        "视频拼接失败：${exportException.errorCodeName} ${exportException.message}",
                                    )
                                )
                            }
                        }
                    })
                    .build()

                // 序列组装：每段统一转码规格（H.264/1080 高度）+ 独立声音开关
                val items = segments.map { segment ->
                    val mediaItem = MediaItem.Builder().setUri(segment.uri).build()
                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(!segment.audioOn)
                        .setEffects(
                            Effects(emptyList(), listOf(Presentation.createForHeight(1080)))
                        )
                        .build()
                }
                val sequence = EditedMediaItemSequence.Builder(items).build()
                val composition = Composition.Builder(sequence).build()

                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { transformer.cancel() }
                            .onFailure { Log.w("motionphoto", "Transformer cancel skipped: ${it.message}") }
                    }
                }

                transformer.start(composition, output.absolutePath)
            }
        }
    }
}
