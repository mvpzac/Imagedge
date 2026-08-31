package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Live 图嵌入视频的裁剪与编码归一器（批次 A 核心）。
 *
 * 职责（对应升级方案 4.1）：
 * 1. **选段裁剪**：`ClippingConfiguration(startMs, endMs)`，段长上限 [MAX_CLIP_MS] = 5s；
 * 2. **强制 H.264**：社区与厂商实测一致——HEVC 嵌入在老 ROM 相册识别不可靠（iPhone/
 *    新旗舰默认 HEVC 源），转码 H.264 是兼容性的第一保障；
 * 3. **分辨率钳制**：短边 ≤ [TARGET_SHORT_SIDE] px，控制嵌入体积（降低被社交平台
 *    二压的概率），音轨保留（实况图带声音才完整）；
 * 4. 输出干净的 MP4（Media3 重封装保证 `ftyp` 位于文件头——依赖「扫描 ftyp 定位视频」
 *    兜底逻辑的 ROM 依赖这一点）。
 *
 * 三拼（批次 B）复用本器：多段必须**同规格**输出，拼接点才无缝。
 */
internal object VideoTrimmer {

    /** 单段最大时长（用户需求：不超过 5 秒） */
    const val MAX_CLIP_MS = 5_000L

    /** 最小可选段长（低于此值实况观感太短） */
    const val MIN_CLIP_MS = 1_500L

    /** 输出视频短边上限（px） */
    const val TARGET_SHORT_SIDE = 1080

    /**
     * 裁剪 [inputUri] 的 [startMs, endMs] 区间并转码归一。
     * 段长超过 [MAX_CLIP_MS] 时截断到 5s；起点负值按 0 处理。
     *
     * @param audioOn 是否保留音轨（false 时 setRemoveAudio）
     * @param cropLTRB 可选中心/对齐裁剪：[left, right, bottom, top]，**负值**表示从该侧
     *   裁掉的帧占比（media3 Crop 语义，如 -0.1 = 裁掉左侧 10%）；null 不裁剪
     * @param targetW/targetH 可选目标分辨率：>0 时输出精确该尺寸（配合 Crop 先裁后缩，
     *   三拼各段统一规格用）；默认 0 走短边 1080 等比缩放
     * @return 裁剪后的临时 MP4 文件（调用方用完由 TempFiles 统一清理）
     */
    suspend fun trim(
        context: Context,
        inputUri: Uri,
        startMs: Long,
        endMs: Long,
        audioOn: Boolean = true,
        cropLTRB: FloatArray? = null,
        targetW: Int = 0,
        targetH: Int = 0,
    ): File {
        val clampedStart = startMs.coerceAtLeast(0L)
        val clampedEnd = endMs.coerceAtLeast(clampedStart + MIN_CLIP_MS)
            .coerceAtMost(clampedStart + MAX_CLIP_MS)
        // 临时文件创建与音轨归一化均为 IO 操作，先行完成（归一化见 [AudioNormalizer]：
        // PCM 等不兼容音轨在此转成 AAC，避免 Transformer 解码失败后只能无声降级）
        val safeUri = withContext(Dispatchers.IO) {
            AudioNormalizer.ensureCompatibleAudio(context, inputUri)
        }
        val output = withContext(Dispatchers.IO) {
            MotionPhotoTempFiles.createWorkingFile(
                cacheDir = context.cacheDir,
                directoryName = "motion-photo-trim",
                prefix = "motion-photo-trimmed",
                extension = "mp4",
            )
        }
        // **线程模型（真机 FATAL 修复）**：Media3 Transformer 强制其全部公开方法
        // （build/start/cancel）在 applicationLooper 上调用，默认为主线程 Looper，
        // 违反即抛 "Transformer is accessed on the wrong thread" 崩溃。
        // 此前整段跑在 Dispatchers.IO 上 → 一点「生成」就崩回首页。
        // 因此：Transformer 的创建与 start 必须切到主线程；Listener 回调也在
        // applicationLooper（主线程）上执行，resume 恢复调用方上下文不受影响。
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                // 音轨不兼容降级：部分视频（微信转存/特殊编码 AAC）的音轨无法被
                // Transformer 解码为 PCM（Asset loader error），audioOn=false 时
                // 直接去音频转码，保证出片
                val builder = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                if (audioOn) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult,
                        ) {
                            if (continuation.isActive) continuation.resume(output)
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    MotionPhotoComposeException(
                                        "视频裁剪失败：${exportException.errorCodeName} ${exportException.message}",
                                    )
                                )
                            }
                        }
                    })
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(safeUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clampedStart)
                            .setEndPositionMs(clampedEnd)
                            .build()
                    )
                    .build()

                // 效果链：先裁剪（可选）再缩放；三拼传入统一目标尺寸保证各段规格一致
                val videoEffects = buildList {
                    if (cropLTRB != null && cropLTRB.size == 4) {
                        add(androidx.media3.effect.Crop(cropLTRB[0], cropLTRB[1], cropLTRB[2], cropLTRB[3]))
                    }
                    add(
                        if (targetW > 0 && targetH > 0) {
                            Presentation.createForWidthAndHeight(targetW, targetH, Presentation.LAYOUT_SCALE_TO_FIT)
                        } else {
                            Presentation.createForShortSide(TARGET_SHORT_SIDE)
                        }
                    )
                }
                val editedItem = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(!audioOn)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()

                // 取消通知可能来自任意线程，而 cancel() 同样必须在主线程调用：
                // post 到主线程执行，并兜底吞掉「已完成后 cancel」的异常
                continuation.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { transformer.cancel() }
                            .onFailure { Log.w("motionphoto", "Transformer cancel skipped: ${it.message}") }
                    }
                }

                // media3 1.9：start 走 (EditedMediaItem, 输出文件路径) 签名
                transformer.start(editedItem, output.absolutePath)
            }
        }
    }
}
