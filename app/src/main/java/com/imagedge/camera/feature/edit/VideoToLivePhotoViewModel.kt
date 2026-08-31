package com.imagedge.camera.feature.edit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.motionphoto.MotionPhotoComposer
import com.imagedge.camera.motionphoto.MotionPhotoVideoCoverExtractor
import com.imagedge.camera.ui.feedback.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频转 LIVE 图（Motion Photo）导出 ViewModel（批次 A：选段 + 封面自选）。
 *
 * 流程：多选视频 → 逐个进入编辑会话（选段 ≤5s + 段内选封面帧）
 * → 全部确认后批量「裁剪转码 → 抽封面 → 合成 → 保存相册」。
 */
@HiltViewModel
class VideoToLivePhotoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val haptics: Haptics
) : ViewModel() {

    /** 封面候选帧（时间线缩略图用） */
    data class CoverCandidate(val bitmap: Bitmap, val timeMs: Long)

    /**
     * 单个视频的编辑会话。
     *
     * 交互模型（用户定义）：
     * 1. 拉动进度条选定一个**关键点**（= 封面帧，选定后不再移动）；
     * 2. 关键点前后各 5 秒以外的部分视为被删除（保留区间 = [key-5s, key+5s] 钳到视频边界）；
     * 3. 保留区间上出现一个**滑块窗口**，默认 5 秒长（可选 4s/3s），
     *    **必须包含关键点**，整体左右拖动选定最终片段；
     * 4. 导出：片段 = 窗口范围，封面 = 关键点帧。
     */
    data class EditSession(
        val uri: Uri,
        /** 视频总时长；创建时尚未知，probe 后回填 */
        val durationMs: Long = 0L,
        /** 全视频均匀时间线缩略图（选段参考） */
        val timelineThumbs: List<CoverCandidate> = emptyList(),
        val timelineLoading: Boolean = true,
        /** 关键点（封面帧）时间；< 0 表示尚未选定 */
        val keyMs: Long = -1L,
        /** 关键点帧预览图 */
        val keyPreview: Bitmap? = null,
        val keyPreviewLoading: Boolean = false,
        /** 滑块窗口时长（用户可选 3/4/5s，默认 5s；实际窗口长 = min(此值, 保留区间长)） */
        val windowLenMs: Long = DEFAULT_WINDOW_MS,
        /** 滑块窗口起点（窗口必须包含 [keyMs]） */
        val winStartMs: Long = 0L,
    ) {
        /** 保留区间起点（关键点前 5s，钳到 0） */
        val keepStartMs: Long
            get() = if (keyMs < 0) 0L else (keyMs - VideoClipLimits.KEEP_RADIUS_MS).coerceAtLeast(0L)

        /** 保留区间终点（关键点后 5s，钳到视频末尾） */
        val keepEndMs: Long
            get() = if (keyMs < 0 || durationMs <= 0) durationMs
            else (keyMs + VideoClipLimits.KEEP_RADIUS_MS).coerceAtMost(durationMs)

        /** 实际窗口长度：所选时长与保留区间取小（关键点贴近边界时窗口会变短） */
        val effectiveWindowMs: Long
            get() = windowLenMs.coerceAtMost((keepEndMs - keepStartMs).coerceAtLeast(0L))

        /** 窗口终点 */
        val winEndMs: Long
            get() = winStartMs + effectiveWindowMs
    }

    /** 一个已完成编辑的片段（等待批量导出） */
    data class EditedClip(
        val uri: Uri,
        val filename: String?,
        val startMs: Long,
        val endMs: Long,
        /** 封面（关键点）绝对时间；导出时换算为相对裁剪后文件的时间 */
        val coverTimeMs: Long,
    )

    data class UiState(
        val processing: Boolean = false,
        val progressText: String? = null,
        val message: String? = null,
        val doneCount: Int = 0,
        val failCount: Int = 0,
        /** 当前正在编辑的会话；null = 不在编辑态 */
        val session: EditSession? = null,
        /** 编辑队列：待编辑的剩余视频 */
        val pendingUris: List<Uri> = emptyList(),
        /** 已确认编辑的片段 */
        val editedClips: List<EditedClip> = emptyList(),
        val indexLabel: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 本轮导出中「音轨不兼容降级为无声」的数量（exportOne 串行执行，无并发） */
    private var silentExportCount = 0

    // ── 编辑会话 ─────────────────────────────────────────────────

    /** 视频选择器回调：进入逐个编辑流程 */
    fun onVideosPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.processing) return
        silentExportCount = 0
        _state.update { it.copy(pendingUris = uris.drop(1), editedClips = emptyList(), message = null) }
        loadSession(uris.first(), total = uris.size, index = 1)
    }

    private fun loadSession(uri: Uri, total: Int, index: Int) {
        _state.update {
            it.copy(
                session = EditSession(uri = uri),
                indexLabel = if (total > 1) "第 $index / $total 个" else null
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val durationMs = probeDurationMs(uri)
            _state.update { s ->
                val session = s.session?.takeIf { it.uri == uri } ?: return@update s
                s.copy(session = session.copy(durationMs = durationMs))
            }
            // 时间线缩略图：全视频均匀 24 帧（320px，控制内存 ≈ 7MB）
            val thumbs = extractTimeline(uri, durationMs, count = 24)
            _state.update { s ->
                val session = s.session?.takeIf { it.uri == uri } ?: return@update s
                // 默认关键点 = 视频中间（用户可再拖动调整）
                val defaultKey = durationMs / 2
                s.copy(
                    session = session.copy(
                        timelineThumbs = thumbs,
                        timelineLoading = false,
                        keyMs = defaultKey,
                        winStartMs = clampWindowStart(session.copy(durationMs = durationMs, keyMs = defaultKey), defaultKey - DEFAULT_WINDOW_MS / 2)
                    )
                )
            }
            loadKeyPreview()
        }
    }

    /**
     * 拖动进度条选定关键点（= 封面帧）。
     * 保留区间随关键点变化（前后各 5s 钳到边界），窗口以关键点为中心重新放置。
     */
    fun setKeyPoint(ms: Long) {
        _state.update { s ->
            val session = s.session?.takeIf { it.durationMs > 0 } ?: return@update s
            val key = ms.coerceIn(0L, session.durationMs)
            val updated = session.copy(keyMs = key)
            s.copy(
                session = updated.copy(
                    // 关键点变化 → 以关键点为中心重放窗口（含关键点约束自动满足）
                    winStartMs = clampWindowStart(updated, key - updated.effectiveWindowMs / 2)
                )
            )
        }
        loadKeyPreview()
    }

    /** 切换滑块窗口时长（5s/4s/3s）：以关键点为中心重放窗口 */
    fun setWindowLen(lenMs: Long) {
        _state.update { s ->
            val session = s.session ?: return@update s
            val updated = session.copy(windowLenMs = lenMs)
            s.copy(
                session = updated.copy(
                    winStartMs = clampWindowStart(updated, updated.keyMs - updated.effectiveWindowMs / 2)
                )
            )
        }
    }

    /**
     * 左右拖动滑块窗口（[deltaMs] 为位移量，可正可负）。
     * 约束：窗口必须包含关键点 → winStart ∈ [max(keepStart, key-len), min(key, keepEnd-len)]。
     */
    fun dragWindowBy(deltaMs: Long) {
        _state.update { s ->
            val session = s.session?.takeIf { it.keyMs >= 0 } ?: return@update s
            s.copy(session = session.copy(winStartMs = clampWindowStart(session, session.winStartMs + deltaMs)))
        }
    }

    /**
     * 把窗口起点钳到合法区间：窗口必须落在保留区间内且包含关键点。
     */
    private fun clampWindowStart(session: EditSession, desired: Long): Long {
        val len = session.effectiveWindowMs
        if (len <= 0L) return 0L
        val lower = maxOf(session.keepStartMs, session.keyMs - len)
        val upper = minOf(session.keyMs, session.keepEndMs - len)
        return if (lower > upper) {
            // 保留区间比窗口还短（关键点贴近边界且视频太短）：铺满保留区间
            session.keepStartMs
        } else {
            desired.coerceIn(lower, upper)
        }
    }

    /**
     * 抽关键点帧做封面预览（精确命中所选时间）。
     *
     * 卡死修复：原实现抽帧完成时若关键点已变（用户拖动中），结果被丢弃但
     * loading 锁不复位、也不再重试 → 之后（包括后续点击）永远不刷新。
     * 现改为「复位锁 + 追赶最新关键点」：每次重抽耗时有限，用户停止后
     * 最后一轮必然命中。
     *
     * 调用时机：Screen 的 Slider.onValueChangeFinished（松手才抽帧，
     * 拖动中只更新状态，避免 OPTION_CLOSEST 逐帧解码的高频开销）。
     */
    private fun loadKeyPreview() {
        val session = _state.value.session ?: return
        if (session.keyMs < 0) return
        if (session.keyPreviewLoading) return
        _state.update { s -> s.copy(session = s.session?.copy(keyPreviewLoading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val targetMs = session.keyMs
            val bmp = extractFrame(session.uri, targetMs, widthPx = 720)
            val current = _state.value.session
            when {
                current == null || current.uri != session.uri -> return@launch
                // 抽帧期间关键点未变：直接应用
                current.keyMs == targetMs ->
                    _state.update { s ->
                        s.copy(session = s.session?.copy(keyPreview = bmp, keyPreviewLoading = false))
                    }
                // 关键点已变：复位锁并按最新关键点追赶重抽
                else -> {
                    _state.update { s ->
                        s.copy(session = s.session?.copy(keyPreviewLoading = false))
                    }
                    loadKeyPreview()
                }
            }
        }
    }

    /** 松手后刷新封面预览（Slider.onValueChangeFinished 调用；拖动中只更新状态不抽帧） */
    fun refreshKeyPreview() = loadKeyPreview()

    /** 确认当前片段：队列还有则装载下一个，否则批量导出 */
    fun confirmSession() {
        val s = _state.value
        val session = s.session ?: return
        if (session.keyMs < 0) return
        val edited = s.editedClips + EditedClip(
            uri = session.uri,
            filename = queryDisplayName(session.uri),
            startMs = session.winStartMs,
            endMs = session.winEndMs,
            coverTimeMs = session.keyMs,
        )
        val next = s.pendingUris.firstOrNull()
        if (next != null) {
            val total = s.pendingUris.size + edited.size
            _state.update {
                it.copy(editedClips = edited, pendingUris = s.pendingUris.drop(1), session = null)
            }
            loadSession(next, total = total, index = edited.size + 1)
        } else {
            _state.update { it.copy(editedClips = edited, session = null, pendingUris = emptyList()) }
            exportAll(edited)
        }
    }

    /** 放弃当前会话（返回初始态） */
    fun cancelSession() {
        _state.update {
            it.copy(session = null, pendingUris = emptyList(), editedClips = emptyList(), indexLabel = null)
        }
    }

    /** 重新开始（结果页「继续」） */
    fun resetResult() {
        _state.update { it.copy(doneCount = 0, failCount = 0, message = null) }
    }

    // ── 导出 ─────────────────────────────────────────────────────

    private fun exportAll(clips: List<EditedClip>) {
        if (clips.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(processing = true, message = null, doneCount = 0, failCount = 0) }
            var done = 0
            var fail = 0
            clips.forEachIndexed { index, clip ->
                _state.update { it.copy(progressText = "正在导出 ${index + 1}/${clips.size}") }
                val ok = runCatching { exportOne(clip) }
                    .onFailure { AppLog.w("livephoto", "导出失败：${it.message}") }
                    .isSuccess
                if (ok) done++ else fail++
                _state.update { it.copy(doneCount = done, failCount = fail) }
            }
            val silentNote = if (silentExportCount > 0) {
                "（其中 $silentExportCount 个视频音轨不兼容，已生成无声版）"
            } else ""
            silentExportCount = 0
            _state.update {
                it.copy(
                    processing = false,
                    progressText = null,
                    message = if (fail == 0) "导出完成：成功 $done 个，已保存到系统相册$silentNote"
                    else "导出完成：成功 $done 个，失败 $fail 个"
                )
            }
            if (fail == 0) haptics.thud() else haptics.double()
        }
    }

    /** 单个片段：裁剪转码 → 抽封面（裁剪后文件上）→ 合成 → 保存 */
    private suspend fun exportOne(clip: EditedClip): Unit = withContext(Dispatchers.IO) {
        // 1) 裁剪 + 转码归一（H.264/1080p，≤5s）
        // 三级降级（真机实测两类失败模式）：
        //   a. 首次失败（MTK codec 组件瞬时竞争 "Unexpected runtime error"）→ 1.5s 后带音频重试
        //   b. 再失败（音轨特殊编码无法解码 PCM，"Asset loader error"，如微信转存视频）
        //      → 1.5s 后【去音频】重试，出片并在 UI 提示无声
        var silentFallback = false
        val trimmed = runCatching {
            MotionPhotoComposer.trimVideo(context, clip.uri, clip.startMs, clip.endMs)
        }.recoverCatching { firstError ->
            AppLog.w("livephoto", "裁剪首次失败（${firstError.message}），1.5s 后带音频重试")
            kotlinx.coroutines.delay(1_500)
            MotionPhotoComposer.trimVideo(context, clip.uri, clip.startMs, clip.endMs)
        }.recoverCatching { secondError ->
            AppLog.w("livephoto", "带音频重试仍失败（${secondError.message}）——音轨不兼容，降级为无声导出")
            silentFallback = true
            kotlinx.coroutines.delay(1_500)
            MotionPhotoComposer.trimVideo(context, clip.uri, clip.startMs, clip.endMs, audioOn = false)
        }.getOrThrow()
        try {
            // 2) 封面：时间换算到裁剪后文件上；未手选则取段中间（-1 = 各层默认中间帧）
            val coverOffsetMs = if (clip.coverTimeMs >= 0) {
                (clip.coverTimeMs - clip.startMs).coerceIn(0L, clip.endMs - clip.startMs)
            } else {
                -1L
            }
            val coverUri = Uri.fromFile(
                MotionPhotoVideoCoverExtractor.extractCoverJpeg(
                    context = context,
                    videoUri = Uri.fromFile(trimmed),
                    timestampMs = coverOffsetMs,
                )
            )
            // 3) 合成：封面图 + 时间戳双写（官方规范：缺省时 reader 播视频中间帧）
            //    exifSourceUri = 源视频：成品保留源素材的拍摄时间（视频容器无完整 EXIF）
            val result = MotionPhotoComposer.compose(
                context = context,
                imageUri = coverUri,
                videoUri = Uri.fromFile(trimmed),
                coverTimestampUs = if (coverOffsetMs >= 0) coverOffsetMs * 1_000 else -1L,
                exifSourceUri = clip.uri,
            )
            AppLog.i("livephoto", "已合成 Motion Photo：${result.displayName}（${result.totalBytes} 字节）")
            if (silentFallback) silentExportCount += 1
            // 4) 保存相册
            MotionPhotoComposer.saveToGallery(context, result)
        } finally {
            trimmed.delete()
        }
    }

    // ── 媒体工具 ─────────────────────────────────────────────────

    private fun probeDurationMs(uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    /** 均匀抽 [count] 帧时间线缩略图（取关键帧，速度快） */
    private suspend fun extractTimeline(uri: Uri, durationMs: Long, count: Int): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            if (durationMs <= 0) return@withContext emptyList()
            val step = durationMs / count
            (0 until count).mapNotNull { i ->
                val t = step * i
                extractFrame(uri, t, widthPx = 320)?.let { CoverCandidate(it, t) }
            }
        }

    /** 抽单帧（宽 [widthPx]，等比） */
    private fun extractFrame(uri: Uri, timeMs: Long, widthPx: Int): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            val frame = r.getFrameAtTime(timeMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            if (frame.width <= widthPx) {
                frame
            } else {
                val ratio = widthPx.toFloat() / frame.width
                Bitmap.createScaledBitmap(
                    frame,
                    widthPx,
                    (frame.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            }
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** MediaMetadataRetriever 的 use 扩展（实现 AutoCloseable，API 29+） */
    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }
}

/** 视频选段约束（与 VideoTrimmer 保持一致） */
object VideoClipLimits {
    /** 滑块窗口最大时长（用户需求：不超过 5 秒） */
    const val MAX_CLIP_MS = 5_000L

    /**
     * 关键点保留半径：关键点前后各 5 秒以外的部分视为被删除
     * （保留区间最长 10 秒，窗口在其中滑动）
     */
    const val KEEP_RADIUS_MS = 5_000L
}

/** 滑块窗口默认时长（可选 5s/4s/3s） */
const val DEFAULT_WINDOW_MS = 5_000L

/** 可选的滑块窗口时长档位 */
val WINDOW_LEN_OPTIONS_MS = listOf(5_000L, 4_000L, 3_000L)
