package com.imagedge.camera.feature.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.motionphoto.MotionPhotoComposer
import com.imagedge.camera.motionphoto.MotionPhotoParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LIVE 图三拼 ViewModel（批次 B，对标 DJI Mimo「Live 三拼」）。
 *
 * **两阶段流程（用户定义）**：
 * 1. **归一化编辑**：三张实况图（**任意长宽比**，横竖屏混选均可）先统一裁切到
 *    同一长宽比（16:9 / 1:1 / 4:5 全局选择），每张可重新选择封面帧、开关声音；
 * 2. **拼接预览**：三格竖排无缝拼图（所见即所得）+ 预估大小 → 生成。
 *
 * 无缝的关键：每段先经 VideoTrimmer 以**相同目标尺寸**转码归一（裁切分数随段
 * 传入，先裁后缩），三段规格完全一致后序列拼接。
 */
@HiltViewModel
class LiveTriptychViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** 该段画面在裁切窗口内的垂直对齐（横图裁成更"竖"的比例时决定保上/中/下） */
    enum class Alignment { TOP, CENTER, BOTTOM }

    /** 统一长宽比（全局作用于三张；目标分辨率 = 三段转码归一的统一规格） */
    enum class Aspect(val label: String, val ratio: Float, val targetW: Int, val targetH: Int) {
        R16_9("16:9", 16f / 9f, 1920, 1080),
        R1_1("1:1", 1f, 1080, 1080),
        R4_5("4:5", 4f / 5f, 1080, 1350),
    }

    /** 封面候选帧（时间戳 + 缩略图，供点选重选封面） */
    data class CoverThumb(val timeMs: Long, val bitmap: Bitmap)

    /** 一张已解析的实况图槽位 */
    data class TriptychSlot(
        val sourceUri: Uri,
        val displayName: String,
        /** 提取出的静态 JPEG（临时文件；用户未重选封面时即用它裁切） */
        val imageFile: File,
        /** 提取出的嵌入视频（临时文件） */
        val videoFile: File,
        val videoDurationMs: Long,
        val videoWidth: Int,
        val videoHeight: Int,
        /** 该段是否保留声音 */
        val audioOn: Boolean = true,
        /** 该段裁切窗口对齐 */
        val alignment: Alignment = Alignment.CENTER,
        /** 用户重选的封面帧时间（ms）；null = 用原静态图 */
        val coverTimeMs: Long? = null,
        /** 封面候选帧（视频均匀 9 帧，264px 宽） */
        val coverThumbs: List<CoverThumb> = emptyList(),
        val coverThumbsLoading: Boolean = false,
        val thumbnail: Bitmap? = null,
    )

    /** 阶段：归一化编辑 → 拼接预览 */
    enum class Phase { EDIT, PREVIEW }

    data class UiState(
        val parsing: Boolean = false,
        val phase: Phase = Phase.EDIT,
        val aspect: Aspect = Aspect.R16_9,
        val progressText: String? = null,
        val exporting: Boolean = false,
        val slots: List<TriptychSlot> = emptyList(),
        /** 拼接预览（所见即所得：封面裁切后的三格拼图） */
        val previewBitmap: Bitmap? = null,
        val previewLoading: Boolean = false,
        /** 预估导出大小（字节） */
        val estimatedBytes: Long = 0L,
        val message: String? = null,
        val success: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val parsedFiles = mutableListOf<File>()

    /** 图片选择器回调：解析 3 张实况图（任意长宽比） */
    fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.parsing || _state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(parsing = true, message = null, success = false, slots = emptyList(), phase = Phase.EDIT) }
            val slots = mutableListOf<TriptychSlot>()
            for ((index, uri) in uris.take(3).withIndex()) {
                _state.update { it.copy(progressText = "解析实况图 ${index + 1}/${minOf(3, uris.size)}") }
                val slot = runCatching { parseSlot(uri) }
                    .onFailure { e ->
                        AppLog.w("triptych", "解析失败 ${index + 1}：${e.message}")
                        _state.update {
                            it.copy(
                                parsing = false,
                                progressText = null,
                                message = "第 ${index + 1} 张不是可解析的实况图，请重新选择"
                            )
                        }
                        cleanup()
                        return@launch
                    }
                    .getOrNull() ?: return@launch
                slots += slot
            }
            _state.update { it.copy(parsing = false, progressText = null, slots = slots) }
        }
    }

    /** 解析单张：提取静态图/视频 + 读时长尺寸（不再强制 16:9——归一化步骤统一裁切） */
    private suspend fun parseSlot(uri: Uri): TriptychSlot? = withContext(Dispatchers.IO) {
        val parsed = MotionPhotoParser.parse(context, uri)
        parsedFiles += listOfNotNull(parsed.imageFile, parsed.videoFile)
        val retriever = MediaMetadataRetriever()
        val (durationMs, width, height) = try {
            retriever.setDataSource(parsed.videoFile.absolutePath)
            Triple(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            )
        } finally {
            retriever.release()
        }
        require(width > 0 && height > 0) { "invalid video size ${width}x$height" }
        val thumb = BitmapFactory.decodeFile(parsed.imageFile.absolutePath)?.let {
            val scale = 360f / maxOf(it.width, it.height)
            Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true)
        }
        TriptychSlot(
            sourceUri = uri,
            displayName = queryDisplayName(uri) ?: "实况图",
            imageFile = parsed.imageFile,
            videoFile = parsed.videoFile,
            videoDurationMs = durationMs,
            videoWidth = width,
            videoHeight = height,
            thumbnail = thumb,
        )
    }

    fun setAudioOn(index: Int, on: Boolean) = updateSlot(index) { it.copy(audioOn = on) }

    fun setAlignment(index: Int, alignment: Alignment) = updateSlot(index) { it.copy(alignment = alignment) }

    /** 全局统一长宽比：切换后重建拼接预览（编辑态的封面候选不随比例变化） */
    fun setAspect(aspect: Aspect) {
        _state.update { it.copy(aspect = aspect, previewBitmap = null) }
        if (_state.value.phase == Phase.PREVIEW) refreshPreview()
    }

    /** 重选封面：精确抽取所点时间帧作为该格画面 */
    fun setCover(index: Int, timeMs: Long) {
        val slot = _state.value.slots.getOrNull(index) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val frame = extractFrame(slot.videoFile, timeMs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) {
                updateSlot(index) { it.copy(coverTimeMs = timeMs) }
                _state.update { it.copy(previewBitmap = null) }
                if (_state.value.phase == Phase.PREVIEW) refreshPreview()
            }
        }
    }

    /** 重置为原静态图封面 */
    fun resetCover(index: Int) {
        updateSlot(index) { it.copy(coverTimeMs = null) }
    }

    /** 装载某槽位的封面候选帧（9 帧均匀抽取，264px 宽） */
    fun loadCoverThumbs(index: Int) {
        val slot = _state.value.slots.getOrNull(index) ?: return
        if (slot.coverThumbs.isNotEmpty() || slot.coverThumbsLoading) return
        _state.update { s ->
            s.copy(slots = s.slots.mapIndexed { i, t -> if (i == index) t.copy(coverThumbsLoading = true) else t })
        }
        viewModelScope.launch(Dispatchers.IO) {
            val duration = slot.videoDurationMs.coerceAtLeast(1L)
            val count = 9
            val thumbs = (0 until count).mapNotNull { i ->
                val t = duration * i / count
                extractFrame(slot.videoFile, t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { CoverThumb(t, it) }
            }
            _state.update { s ->
                s.copy(slots = s.slots.mapIndexed { i, t ->
                    if (i == index) t.copy(coverThumbs = thumbs, coverThumbsLoading = false) else t
                })
            }
        }
    }

    /** 上移一格（顺序即拼图从上到下的顺序） */
    fun moveUp(index: Int) {
        if (index <= 0) return
        _state.update { s ->
            if (index >= s.slots.size) return@update s
            val slots = s.slots.toMutableList()
            val tmp = slots[index - 1]; slots[index - 1] = slots[index]; slots[index] = tmp
            s.copy(slots = slots)
        }
        if (_state.value.phase == Phase.PREVIEW) refreshPreview()
    }

    fun moveDown(index: Int) {
        _state.update { s ->
            if (index >= s.slots.size - 1) return@update s
            val slots = s.slots.toMutableList()
            val tmp = slots[index + 1]; slots[index + 1] = slots[index]; slots[index] = tmp
            s.copy(slots = slots)
        }
        if (_state.value.phase == Phase.PREVIEW) refreshPreview()
    }

    private inline fun updateSlot(index: Int, transform: (TriptychSlot) -> TriptychSlot) {
        _state.update { s ->
            if (index !in s.slots.indices) return@update s
            s.copy(slots = s.slots.mapIndexed { i, slot -> if (i == index) transform(slot) else slot })
        }
    }

    /** 进入拼接预览：重建三格拼图 + 预估大小 */
    fun enterPreview() {
        _state.update { it.copy(phase = Phase.PREVIEW) }
        refreshPreview()
    }

    /** 回到编辑（调整封面/声音/对齐/比例） */
    fun backToEdit() {
        _state.update { it.copy(phase = Phase.EDIT) }
    }

    /**
     * 预览构建过期标记：构建期间（低端机数百毫秒）用户改了封面/顺序/对齐时置脏。
     * 所有读写都在主线程（调用方均为 UI 动作，构建收尾经主协程回主线程），无需加锁。
     */
    private var previewDirty = false

    /** 重建三格拼图预览 + 预估导出大小 */
    fun refreshPreview() {
        if (_state.value.slots.size != 3) return
        if (_state.value.previewLoading) {
            // 构建进行中不重复起任务；标记过期，收尾时链式重建（否则旧快照跑完，
            // 预览停在旧画面且不再有刷新机会）
            previewDirty = true
            return
        }
        startPreviewBuild()
    }

    /** 单轮预览构建（仅主线程调用；重活在 IO，状态机判定回主线程避免脏标记竞态） */
    private fun startPreviewBuild() {
        val slots = _state.value.slots
        if (slots.size != 3) return
        previewDirty = false
        _state.update { it.copy(previewLoading = true) }
        viewModelScope.launch {
            val aspect = _state.value.aspect
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { buildTriptychBitmap(slots, aspect) }.getOrNull()
            }
            if (previewDirty) {
                // 构建期间状态已变：旧结果作废（从未进入 state，可安全回收），
                // 用最新 slots 再来一轮，直到某轮构建期间无新变化才落结果
                bitmap?.recycle()
                startPreviewBuild()
                return@launch
            }
            val seconds = slots.sumOf { it.videoDurationMs } / 1000.0
            // 预估：视频 ≈ Σ时长 × 10Mbps（H.264 1080p 转码经验码率）÷ 8；静态拼图 JPEG ≈ 3MB
            val estimated = (seconds * 10_000_000 / 8).toLong() + 3L * 1024 * 1024
            _state.update {
                it.copy(previewBitmap = bitmap, previewLoading = false, estimatedBytes = estimated)
            }
        }
    }

    /**
     * 静态三格拼图：每格 = 统一比例目标尺寸，竖排无缝。
     * 每格画面 = 用户所选封面帧（或原静态图）按对齐裁切到统一比例。
     */
    private fun buildTriptychBitmap(slots: List<TriptychSlot>, aspect: Aspect): Bitmap {
        val cellW = aspect.targetW
        val cellH = aspect.targetH
        val result = Bitmap.createBitmap(cellW, cellH * slots.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        slots.forEachIndexed { index, slot ->
            // 格画面来源：重选封面 → 精确帧；未重选 → 原静态图
            val cover = slot.coverTimeMs?.let { extractFrame(slot.videoFile, it, MediaMetadataRetriever.OPTION_CLOSEST) }
                ?: BitmapFactory.decodeFile(slot.imageFile.absolutePath)
                ?: return@forEachIndexed
            val cropped = cropToAspect(cover, aspect.ratio, slot.alignment)
            canvas.drawBitmap(cropped, null, RectF(0f, index * cellH.toFloat(), cellW.toFloat(), (index + 1) * cellH.toFloat()), null)
            if (cropped !== cover) cropped.recycle()
            if (cover !== slot.thumbnail) cover.recycle()
        }
        return result
    }

    /** 位图中心/对齐裁切到目标比例 */
    private fun cropToAspect(bmp: Bitmap, targetRatio: Float, alignment: Alignment): Bitmap {
        val srcRatio = bmp.width.toFloat() / bmp.height
        if (Math.abs(srcRatio - targetRatio) < 0.01f) return bmp
        val cropW: Int; val cropH: Int
        if (srcRatio > targetRatio) {
            // 源更宽：水平居中裁两侧
            cropH = bmp.height
            cropW = (bmp.height * targetRatio).toInt()
        } else {
            // 源更高：垂直按对齐裁上下
            cropW = bmp.width
            cropH = (bmp.width / targetRatio).toInt()
        }
        val x = (bmp.width - cropW) / 2
        val y = when (alignment) {
            Alignment.TOP -> 0
            Alignment.CENTER -> (bmp.height - cropH) / 2
            Alignment.BOTTOM -> bmp.height - cropH
        }
        return Bitmap.createBitmap(bmp, x, y, cropW, cropH)
    }

    /** video → media3 Crop 裁剪分数 [left,right,bottom,top]（负值=该侧裁掉占比），按对齐 */
    private fun cropFractions(slot: TriptychSlot, aspect: Aspect): FloatArray {
        val srcRatio = slot.videoWidth.toFloat() / slot.videoHeight
        return if (srcRatio > aspect.ratio) {
            // 源更宽：裁两侧（水平居中）
            val keep = aspect.ratio / srcRatio
            val cut = (1 - keep) / 2
            floatArrayOf(-cut, -cut, 0f, 0f)
        } else {
            // 源更高：垂直按对齐裁
            val keep = srcRatio / aspect.ratio
            val cut = 1 - keep
            when (slot.alignment) {
                Alignment.TOP -> floatArrayOf(0f, 0f, -cut, 0f)
                Alignment.CENTER -> floatArrayOf(0f, 0f, -cut / 2, -cut / 2)
                Alignment.BOTTOM -> floatArrayOf(0f, 0f, 0f, -cut)
            }
        }
    }

    /**
     * 按视频文件缓存的抽帧器：封面候选一次抽 9 帧，原先每帧都新建实例 +
     * 重新 setDataSource 解析容器，9 倍重复开销。缓存后同一视频只解析一次。
     * MediaMetadataRetriever 非线程安全：使用方对实例加锁；抽帧异常时移除
     * 缓存（可能已进入坏状态），下次重建。
     */
    private val frameRetrievers = HashMap<String, MediaMetadataRetriever>()

    private fun retrieverFor(video: File): MediaMetadataRetriever = synchronized(frameRetrievers) {
        frameRetrievers.getOrPut(video.absolutePath) {
            MediaMetadataRetriever().also { it.setDataSource(video.absolutePath) }
        }
    }

    /** 抽帧（带 OPTION 参数与宽度限制） */
    private fun extractFrame(video: File, timeMs: Long, option: Int): Bitmap? = try {
        val retriever = retrieverFor(video)
        val frame = synchronized(retriever) {
            retriever.getFrameAtTime(timeMs * 1000, option)
        }
        frame?.let { f ->
            if (f.width > 640) {
                val scale = 640f / f.width
                Bitmap.createScaledBitmap(f, 640, (f.height * scale).toInt().coerceAtLeast(1), true).also {
                    if (it !== f) f.recycle()
                }
            } else f
        }
    } catch (e: Exception) {
        AppLog.w("triptych", "抽帧失败 @${timeMs}ms：${e.message}")
        // 抽帧抛异常说明实例可能已进入坏状态：移除缓存，下次重建
        synchronized(frameRetrievers) { frameRetrievers.remove(video.absolutePath) }
            ?.let { runCatching { it.release() } }
        null
    }

    /** 导出：三段裁切+转码归一（统一尺寸） → 序列拼接 → 与拼图合成 → 保存 */
    fun export() {
        val slots = _state.value.slots
        val aspect = _state.value.aspect
        if (slots.size != 3 || _state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null) }
            try {
                // 1) 每段裁切到统一比例并转码归一（同目标尺寸——拼接无缝的前提）
                val normalized = mutableListOf<Pair<File, TriptychSlot>>()
                slots.forEachIndexed { index, slot ->
                    _state.update { it.copy(progressText = "裁切转码片段 ${index + 1}/3") }
                    val crop = cropFractions(slot, aspect)
                    val trimmed = runCatching {
                        MotionPhotoComposer.trimVideo(
                            context = context,
                            videoUri = slot.sourceUri,
                            startMs = 0L,
                            endMs = slot.videoDurationMs,
                            audioOn = slot.audioOn,
                            cropLTRB = crop,
                            targetW = aspect.targetW,
                            targetH = aspect.targetH,
                        )
                    }.recoverCatching {
                        kotlinx.coroutines.delay(1_500)
                        MotionPhotoComposer.trimVideo(
                            context = context,
                            videoUri = slot.sourceUri,
                            startMs = 0L,
                            endMs = slot.videoDurationMs,
                            audioOn = slot.audioOn,
                            cropLTRB = crop,
                            targetW = aspect.targetW,
                            targetH = aspect.targetH,
                        )
                    }.getOrThrow()
                    parsedFiles += trimmed
                    normalized += trimmed to slot
                }
                // 2) 序列拼接（每段独立声音开关）
                _state.update { it.copy(progressText = "拼接视频") }
                val stitched = MotionPhotoComposer.stitchVideos(
                    context = context,
                    segments = normalized.map { (file, slot) -> Uri.fromFile(file) to slot.audioOn },
                )
                parsedFiles += stitched
                // 3) 静态三格拼图（与预览同源）
                _state.update { it.copy(progressText = "合成 LIVE 图") }
                val collage = withContext(Dispatchers.IO) {
                    File.createTempFile("triptych", ".jpg", context.cacheDir).apply {
                        outputStream().use { out ->
                            buildTriptychBitmap(normalized.map { it.second }, aspect)
                                .compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                    }
                }
                parsedFiles += collage
                // 4) 合成 Motion Photo（封面 = 顶部格，presentationTimestampUs = 0）
                //    exifSourceUri = 第一张实况图：成品显示第一个 LIVE 图的拍摄信息（用户需求）
                val result = MotionPhotoComposer.compose(
                    context = context,
                    imageUri = Uri.fromFile(collage),
                    videoUri = Uri.fromFile(stitched),
                    coverTimestampUs = 0L,
                    exifSourceUri = slots.firstOrNull()?.sourceUri,
                )
                AppLog.i("triptych", "三拼已合成：${result.displayName}（${result.totalBytes} 字节）")
                MotionPhotoComposer.saveToGallery(context, result)
                _state.update {
                    it.copy(exporting = false, progressText = null, success = true, message = "三拼 LIVE 图已保存到相册")
                }
                cleanup()
            } catch (e: Exception) {
                AppLog.w("triptych", "三拼导出失败：${e.message}")
                _state.update {
                    it.copy(exporting = false, progressText = null, message = "导出失败：${e.message}")
                }
                cleanup()
            }
        }
    }

    /** 回到初始态（结果页「继续」；成功信息保留一次供结果页显示） */
    fun reset() {
        cleanup()
        val keepMessage = _state.value.message?.takeIf { _state.value.success }
        _state.update { UiState(message = keepMessage) }
    }

    private fun cleanup() {
        parsedFiles.forEach { runCatching { it.delete() } }
        parsedFiles.clear()
        synchronized(frameRetrievers) {
            frameRetrievers.values.forEach { runCatching { it.release() } }
            frameRetrievers.clear()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    override fun onCleared() {
        cleanup()
        super.onCleared()
    }
}
