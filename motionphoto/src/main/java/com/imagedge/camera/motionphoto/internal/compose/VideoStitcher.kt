package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
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
 * **每段独立开关声音的实现约束（真机踩坑）**：Media3 的 [EditedMediaItemSequence] 要求
 * 序列内各段**轨道数一致**，`SequenceAssetLoader` 在「前一段无音轨、后一段有音轨」时
 * 直接报错（"The preceding MediaItem does not contain any audio track…"）。
 * 因此**不能**对静音段用 `setRemoveAudio(true)`：
 * - 只要有一段要声音 → 所有段都保留音轨，静音段改挂 `GainProcessor(0)` 把增益压到 0；
 * - 三段都不要声音 → 统一 `setRemoveAudio(true)`（轨道数一致，合法）；
 * - 某段源本身没有音轨 → 打开 `experimentalSetForceAudioTrack()` 让 Media3 补静音轨，
 *   否则同样会因轨道数不一致而导出失败。
 */
@OptIn(UnstableApi::class)
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
        val hasAnyAudio = segments.any { it.audioOn }
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
                    // 静音段：保留音轨、增益归零（见类注释的轨道数约束）
                    val audioProcessors = if (hasAnyAudio && !segment.audioOn) {
                        listOf(GainProcessor(DefaultGainProvider.Builder(0f).build()))
                    } else {
                        emptyList()
                    }
                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveAudio(!hasAnyAudio)
                        .setEffects(
                            Effects(audioProcessors, listOf(Presentation.createForHeight(1080)))
                        )
                        .build()
                }
                val sequenceBuilder = EditedMediaItemSequence.Builder(items)
                if (hasAnyAudio) {
                    // 源视频可能本来就没有音轨（实况图常见）：补静音轨以对齐各段轨道数
                    sequenceBuilder.experimentalSetForceAudioTrack(true)
                }
                val sequence = sequenceBuilder.build()
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
