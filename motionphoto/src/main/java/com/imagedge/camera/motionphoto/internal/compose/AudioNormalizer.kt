package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 音轨兼容性归一器。
 *
 * **背景**：Media3 Transformer 对部分音轨无法解码为 PCM 再编码（真机实测：
 * 微信等 App 转存视频的 PCM 裸音轨在 `createDecodedSampleExporter` 抛
 * `AudioFormat encoding=0x10000000` 异常，报 Asset loader error，重试无效）。
 *
 * **策略**：导出前探测音轨——若为 PCM（`audio/raw`，Android MediaCodec 无 PCM
 * 解码器，Transformer 必败），则自行用 MediaExtractor 读裸 PCM 样本 →
 * MediaCodec 编码为 AAC-LC → 与视频流（**不转码、逐样本拷贝**）remux 成新 MP4，
 * 把「无法解码的音轨」变成「兼容音轨」，**保住声音**（替代直接去音频的有损降级）。
 * 其余编码（AAC/MP3/Opus 等）Transformer 可处理，原样放行。
 *
 * 结果按源 Uri 缓存，避免同一视频重复转码（triptych 会对同一源多次调用）。
 */
internal object AudioNormalizer {

    private val cache = ConcurrentHashMap<String, Uri>()

    /** 返回可直接送入 Transformer 的 Uri：不兼容时为新 remux 的 MP4，否则原样返回 */
    fun ensureCompatibleAudio(context: Context, uri: Uri): Uri {
        cache[uri.toString()]?.let { cached ->
            // 缓存产物是临时文件，可能被系统或工作目录清理删除——失效即重算
            val path = cached.path
            if (cached.scheme == "file" && path != null && File(path).exists()) {
                return cached
            }
            cache.remove(uri.toString())
        }
        val normalized = runCatching { normalizeIfNeeded(context, uri) }
            .onFailure { Log.w("motionphoto", "音轨归一化失败（按原样继续）：${it.message}") }
            .getOrNull() ?: uri
        cache[uri.toString()] = normalized
        return normalized
    }

    private fun normalizeIfNeeded(context: Context, uri: Uri): Uri {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var videoIndex = -1
            var audioIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoIndex < 0) {
                    videoIndex = i; videoFormat = trackFormat
                } else if (mime.startsWith("audio/") && audioIndex < 0) {
                    audioIndex = i; audioFormat = trackFormat
                }
            }
            val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)
            Log.i("motionphoto", "音轨探测：video=${videoFormat?.getString(MediaFormat.KEY_MIME)} audio=$audioMime (index=$audioIndex)")
            // 无音轨 / 非裸 PCM（含裸 PCM 之外的编码 Transformer 均可处理）→ 放行
            if (videoIndex < 0 || audioIndex < 0 || audioMime != MediaFormat.MIMETYPE_AUDIO_RAW) return uri
            // 只处理 16-bit PCM（其余位深罕见，交给无声降级兜底）
            val pcmEncoding = audioFormat!!.getInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            if (pcmEncoding != android.media.AudioFormat.ENCODING_PCM_16BIT) return uri

            // ── 第一遍：仅音频 → 编码 AAC（帧按 4 字节长度前缀落盘，pts 存表）──
            val frameFile = MotionPhotoTempFiles.createWorkingFile(
                cacheDir = context.cacheDir,
                directoryName = "motion-photo-audio-norm",
                prefix = "audio-frames",
                extension = "bin",
            )
            val ptsList = mutableListOf<Long>()
            lateinit var aacFormat: MediaFormat
            encodePcmToAacFrames(extractor, audioIndex, audioFormat, frameFile, ptsList).also { aacFormat = it }

            // ── 第二遍：视频逐样本拷贝 + AAC 帧回灌 → remux ──
            val output = MotionPhotoTempFiles.createWorkingFile(
                cacheDir = context.cacheDir,
                directoryName = "motion-photo-audio-norm",
                prefix = "audio-norm",
                extension = "mp4",
            )
            remux(extractor, videoIndex, audioIndex, videoFormat!!, frameFile, ptsList, aacFormat, output)
            frameFile.delete()
            Log.i("motionphoto", "PCM 音轨已转码为 AAC 并 remux：$uri")
            return Uri.fromFile(output)
        } catch (e: Exception) {
            throw MotionPhotoComposeException("音轨归一化失败：${e.message}")
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** 仅选择音轨，把 PCM 样本喂给 AAC 编码器；编码帧（长度前缀+数据）写 [frameFile]，pts 存 [ptsList]。返回编码器输出格式 */
    private fun encodePcmToAacFrames(
        extractor: android.media.MediaExtractor,
        audioIndex: Int,
        audioFormat: MediaFormat,
        frameFile: File,
        ptsList: MutableList<Long>,
    ): MediaFormat {
        extractor.selectTrack(audioIndex)
        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            DataOutputStream(frameFile.outputStream().buffered()).use { out ->
                var inputDone = false
                var outputDone = false
                var outputFormat: MediaFormat? = null
                val bufferInfo = MediaCodec.BufferInfo()
                while (!outputDone) {
                    // ── 喂输入 ──
                    if (!inputDone) {
                        val inIndex = encoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val input = encoder.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(input, 0)
                            if (sampleSize < 0) {
                                encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                encoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    // ── 收输出 ──
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                        outIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIndex)!!
                            if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                encoded.position(bufferInfo.offset).limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                encoded.get(bytes)
                                out.writeInt(bufferInfo.size)
                                out.write(bytes)
                                ptsList += bufferInfo.presentationTimeUs
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                checkNotNull(outputFormat) { "AAC 编码器未输出格式" }
                return outputFormat
            }
        } finally {
            // 稀缺系统资源：任何异常路径都必须归还编码器
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    /** 视频逐样本拷贝 + AAC 帧回灌写 muxer（取消音频轨选择、选择视频轨） */
    private fun remux(
        extractor: android.media.MediaExtractor,
        videoIndex: Int,
        audioIndex: Int,
        videoFormat: MediaFormat,
        frameFile: File,
        ptsList: List<Long>,
        aacFormat: MediaFormat,
        output: File,
    ) {
        if (audioIndex >= 0) extractor.unselectTrack(audioIndex)
        extractor.selectTrack(videoIndex)
        val rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION, 0)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = muxer.addTrack(aacFormat)
            muxer.start()

            // 1) 视频逐样本拷贝
            // **必须 direct Buffer**：MediaMuxer.writeSampleData 走 JNI GetDirectBufferAddress，
            // 堆 ByteBuffer 会报 "writeSampleData returned an error"（真机实锤）
            var buffer = ByteBuffer.allocateDirect(4 shl 20)
            val info = MediaCodec.BufferInfo()
            // **时间戳防御**：MPEG4Writer 要求每轨时间戳单调递增且非负，违反即报
            // "writeSampleData returned an error"（真机实锤：第二个样本即失败）。
            // 对 getSampleTime 做「非负 + 单调」钳制（B 帧源/异常 extractor 时兜底）。
            var lastVideoPts = -1L
            var videoIdx = 0
            while (true) {
                // 高码率大关键帧可能超过初始缓冲：readSampleData 会静默截断，
                // 截断样本写进 muxer 即损坏视频——按样本实际大小扩容
                val currentSampleSize = extractor.sampleSize
                if (currentSampleSize < 0) break
                if (currentSampleSize > buffer.capacity()) {
                    buffer = ByteBuffer.allocateDirect(currentSampleSize.toInt())
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (size == 0) { extractor.advance(); continue }
                val rawPts = extractor.sampleTime
                val pts = maxOf(rawPts, lastVideoPts + 1, 0L)
                if (rawPts < 0 || rawPts <= lastVideoPts) {
                    Log.w("motionphoto", "视频样本 $videoIdx 时间戳异常 raw=$rawPts last=$lastVideoPts → 钳制为 $pts")
                }
                lastVideoPts = pts
                val flags = if (extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, pts, flags)
                runCatching { muxer.writeSampleData(videoTrack, buffer, info) }
                    .onFailure {
                        Log.e("motionphoto", "remux 视频样本写入失败", it)
                        throw MotionPhotoComposeException(
                            "remux 视频样本写入失败：idx=$videoIdx size=$size pts=$pts flags=$flags: ${it.message}"
                        )
                    }
                extractor.advance()
                videoIdx++
            }
            Log.i("motionphoto", "remux 视频拷贝完成：$videoIdx 个样本，末 pts=$lastVideoPts")

            // 2) AAC 帧回灌
            java.io.DataInputStream(frameFile.inputStream().buffered()).use { fin ->
                val lenBuf = ByteArray(4)
                var ptsIndex = 0
                var audioIdx = 0
                var lastAudioPts = -1L
                while (true) {
                    try {
                        fin.readFully(lenBuf)
                    } catch (eof: java.io.EOFException) {
                        break  // 帧边界处读到文件尾 = 正常结束
                    }
                    val frameSize = ((lenBuf[0].toInt() and 0xFF) shl 24) or ((lenBuf[1].toInt() and 0xFF) shl 16) or
                        ((lenBuf[2].toInt() and 0xFF) shl 8) or (lenBuf[3].toInt() and 0xFF)
                    if (frameSize <= 0 || frameSize > 16 shl 20) {
                        // 静默 break 会产出音画不一致的视频且无失败信号——视为帧文件损坏
                        throw MotionPhotoComposeException("AAC 帧尺寸异常 $frameSize，音频帧文件已损坏")
                    }
                    if (frameSize > buffer.capacity()) {
                        buffer = ByteBuffer.allocateDirect(frameSize)
                    }
                    val frame = ByteArray(frameSize)
                    // 帧体读取中途 EOF = 文件被截断，抛异常交由上层降级（按原样/去音频重试）
                    fin.readFully(frame)
                    val rawPts = ptsList.getOrNull(ptsIndex++)
                        ?: throw MotionPhotoComposeException("AAC 帧数量与时间戳表不一致")
                    val pts = maxOf(rawPts, lastAudioPts + 1, 0L)
                    lastAudioPts = pts
                    buffer.clear(); buffer.put(frame); buffer.flip()
                    info.set(0, frameSize, pts, 0)
                    runCatching { muxer.writeSampleData(audioTrack, buffer, info) }
                        .onFailure {
                            Log.e("motionphoto", "remux 音频帧写入失败", it)
                            throw MotionPhotoComposeException(
                                "remux 音频帧写入失败：idx=$audioIdx size=$frameSize pts=$pts: ${it.message}"
                            )
                        }
                    audioIdx++
                }
                Log.i("motionphoto", "remux 音频回灌完成：$audioIdx 帧")
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
