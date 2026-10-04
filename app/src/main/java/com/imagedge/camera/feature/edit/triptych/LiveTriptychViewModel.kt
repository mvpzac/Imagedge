package com.imagedge.camera.feature.edit.triptych

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.feature.edit.clip.ClipMath
import com.imagedge.camera.feature.edit.clip.ClipSpec
import com.imagedge.camera.feature.edit.clip.CoverSource
import com.imagedge.camera.feature.edit.clip.coverSourceFor
import com.imagedge.camera.motionphoto.MotionPhotoComposer
import com.imagedge.camera.motionphoto.MotionPhotoParser
import com.imagedge.camera.ui.feedback.Haptics
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
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : LIVE 图三拼 ViewModel——预览常驻，每格持一份 ClipSpec
 * </pre>
 */

/**
 * LIVE 图三拼 ViewModel（批次 B，对标 DJI Mimo「Live 三拼」）。
 *
 * **没有两阶段**：三张实况图（**任意长宽比**，横竖屏混选均可）先统一裁切到同一
 * 长宽比（16:9 / 1:1 / 4:5 / 27:16 全局选择），每格可选起止、封面帧、声音、对齐；
 * 三格竖排拼图**常驻在编辑页上**，参数一改就重建，导出是页面上唯一的主按钮。
 * 原先的 `Phase.EDIT/PREVIEW` 只是把本来就能实时显示的东西延后到一次点击之后，
 * 于是「参数变了但预览没变」成了靠 phase 门控维持的假象。
 *
 * 无缝的关键：每段先经 `trimVideo` 以**相同目标尺寸**转码归一（裁切分数随段
 * 传入，先裁后缩），三段规格完全一致后序列拼接。目标尺寸取自
 * `Aspect.cellSize(state.quality)`，不是写死的 `refW/refH`——后者恒为 1080p。
 */
@HiltViewModel
class LiveTriptychViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val haptics: Haptics
) : ViewModel() {

    /** 该段画面在裁切窗口内的垂直对齐（横图裁成更"竖"的比例时决定保上/中/下） */
    enum class Alignment(val label: String) {
        TOP("顶"),
        CENTER("中"),
        BOTTOM("底"),
    }

    /** 参数区的四个 tab。三拼一次只编一格，避免三张长卡片往下滚 */
    enum class TriptychTab(val label: String) {
        ASPECT("比例"), CELL("本格"), COVER("封面"), AUDIO("声音"),
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
        /**
         * 选段 + 封面 + 声音。`coverMs` 为 `null` = 未重选封面（用原始静态图）；
         * 非 `null` 时相对**原始**视频，可越界（收口在 [coverSourceFor]）
         */
        val clip: ClipSpec = initialClipSpec(videoDurationMs),
        /** 该格在统一比例下保上/中/下。三拼拼贴独有，不进 ClipSpec */
        val alignment: Alignment = Alignment.CENTER,
        /** 封面候选帧（视频均匀 9 帧，264px 宽） */
        val coverThumbs: List<CoverThumb> = emptyList(),
        val coverThumbsLoading: Boolean = false,
        val thumbnail: Bitmap? = null,
    )

    data class UiState(
        val parsing: Boolean = false,
        val aspect: Aspect = Aspect.R16_9,
        /** 导出画质档位。经 [Aspect.cellSize] 决定三段视频的目标尺寸与拼图画布尺寸 */
        val quality: Quality = Quality.P1080,
        /** 当前正在编辑第几格。读取方：Screen 的格选择条与「本格 / 封面」tab（Task 5 接上） */
        val selectedIndex: Int = 0,
        /** 当前参数区 tab。读取方：Screen 的 tab 条（Task 5 接上，四个 tab 各渲染一块） */
        val tab: TriptychTab = TriptychTab.CELL,
        /** 导出成功且已落盘；结果页据此呈现，之后不再视为「编辑中」 */
        val done: Boolean = false,
        val progressText: String? = null,
        val exporting: Boolean = false,
        val slots: List<TriptychSlot> = emptyList(),
        /** 拼接预览（所见即所得：封面裁切后的三格拼图） */
        val previewBitmap: Bitmap? = null,
        val previewLoading: Boolean = false,
        /** 预估导出大小（字节）。**估算值**，推导见 [estimateTriptychBytes] */
        val estimatedBytes: Long = 0L,
        val message: String? = null,
        val success: Boolean = false,
        /** 成品在相册里的文件名（结果面板要说「在哪」，只说「已完成」等于没说完） */
        val exportName: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val parsedFiles = mutableListOf<File>()

    /**
     * 导出过程中产生的派生文件（转码段 / 拼接产物 / 拼图 JPEG）。
     * 与 [parsedFiles] 分开管理：导出失败时只清理这些，**保留槽位提取出的原视频**，
     * 用户才能直接点「重试」而不是被迫重新选三张图（v1 失败后 cleanup 把源文件也删了）。
     */
    private val exportFiles = mutableListOf<File>()

    /** 图片选择器回调：解析 3 张实况图（任意长宽比） */
    fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.parsing || _state.value.exporting) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    parsing = true, message = null, success = false,
                    slots = emptyList(), done = false, selectedIndex = 0,
                    // 旧拼图必须一起作废：留着它，新素材解析完之前那一屏显示的是**上一批**
                    // 的三格，而它此刻挂在 `ready == false` 的分支之外看着还像当前内容。
                    // 这正是 [invalidatePreview] 的 KDoc 说它要防的那个洞
                    previewBitmap = null, estimatedBytes = 0L,
                )
            }
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
            // 预览常驻：三张齐了立刻建第一次拼图。原先这一步挂在「进入拼接预览」按钮上，
            // 于是刚解析完的页面是空的——phase 门控删了，触发点就得补在这里
            refreshPreview()
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
        // 缩略图必须采样解码：实况图静态帧可达 24MP，整图解码约 96MB，
        // 只为一张 360px 缩略图付这个代价会直接把低端机顶到 OOM 阈值
        val thumb = decodeSampled(parsed.imageFile, 360)
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

    /**
     * 裁切对齐。[Alignment] **只在源比目标比例更高（`srcRatio < aspect.ratio`）时才有效果**：
     * 那一条分支上 [cropFractions] 裁的是**上下**、按对齐定位置，拼图那一格由
     * `cropToAspect` 裁上下、也按同一个字段定位置——两处都变，所以对齐一改预览必须重建。
     *
     * **源更宽的那一支（`srcRatio > aspect.ratio`）改对齐不改变任何一帧**：
     * [cropFractions] 那一支裁的是左右且水平居中，`cropToAspect` 同一支也裁左右，
     * `y` 算出来恒为 0。此刻重建预览是白费的 3 帧开销，但它发生在一次用户动作之后，
     * 浪费得有限，而在此处按「视频尺寸 vs 目标比例」去跳过重建会漏掉一种情况：
     * `cropToAspect` 吃的是**解码出来那张图**的比例（静态图可能与视频不同），
     * 拿 `videoWidth/videoHeight` 代替它就是拿一个数去判断另一件事。
     */
    fun setAlignment(index: Int, alignment: Alignment) {
        updateSlot(index) { it.copy(alignment = alignment) }
        invalidatePreview()
    }

    /** 全局统一长宽比：切换后重建拼接预览（封面候选帧本身不随比例变化） */
    fun setAspect(aspect: Aspect) {
        _state.update { it.copy(aspect = aspect) }
        invalidatePreview()
    }

    /**
     * 导出画质档位。这是 [Quality] 第一次真正接上生产路径：改它会改
     * [Aspect.cellSize]，于是同时改掉三段视频的转码目标尺寸与拼图画布尺寸。
     *
     * 码率这一维**此刻没有传下去**：`MotionPhotoComposer.trimVideo` 的签名里
     * 还没有 `bitrate` 参数（Task 7 才加），编码器走的是 Media3 自己的默认值。
     * 所以切到 P720 只降分辨率、不降码率——别把这个 setter 当成「省流量的那个开关」，
     * [estimateTriptychBytes] 也正因为如此只按像素算、不按 [Quality.bitrate] 算。
     */
    fun setQuality(q: Quality) {
        _state.update { it.copy(quality = q) }
        invalidatePreview()
    }

    /** 当前 tab。读取方是 Screen 的 tab 条与 tab 内容区（Task 5 接上） */
    fun setTab(tab: TriptychTab) = _state.update { it.copy(tab = tab) }

    /** 选中第几格。读取方：Screen 的格选择条；「本格 / 封面」两个 tab 的内容跟着它取槽位 */
    fun select(index: Int) {
        if (index !in _state.value.slots.indices) return
        _state.update { it.copy(selectedIndex = index) }
    }

    /**
     * 写回某一格的选段。封面时间允许越界（用户可以先挑最好的帧再决定裁哪段），
     * 收口统一在 [coverSourceFor] 里的 `ClipMath.effectiveCoverMs`：预览与导出
     * 走的是同一个函数，所以「屏幕上那一帧」与「产物那一帧」不可能分家。
     */
    fun setSpec(index: Int, spec: ClipSpec) {
        updateSlot(index) { it.copy(clip = spec) }
        invalidatePreview()
    }

    /**
     * 恢复原静态图封面 = 回到「未重选」这一态，即 [ClipSpec.coverMs] 置 `null`。
     *
     * **不是**置 0：0 是候选条带第一格的合法封面时刻（见 [ClipSpec] 的 KDoc），
     * 置 0 会让画面变成「视频第 0 帧」而不是原静态图，而两者看起来几乎一样。
     */
    fun resetCover(index: Int) {
        val spec = _state.value.slots.getOrNull(index)?.clip?.copy(coverMs = null) ?: return
        setSpec(index, spec)
    }

    /**
     * 参数变了：作废旧拼图并重建。
     *
     * 先把 [UiState.previewBitmap] 置空是有意的——顺序变了（[moveUp]/[moveDown]）
     * 之后继续挂着**旧顺序**的拼图，比什么都不挂更容易骗人。
     */
    private fun invalidatePreview() {
        _state.update { it.copy(previewBitmap = null) }
        refreshPreview()
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
                // 与最终取帧用同一个 OPTION_CLOSEST：OPTION_CLOSEST_SYNC 抽到的是最近关键帧，
                // 用户点选的高亮帧与拼图里实际用的帧可能明显不同（错帧感）
                extractFrame(slot.videoFile, t, MediaMetadataRetriever.OPTION_CLOSEST, maxWidth = 264)
                    ?.let { CoverThumb(t, it) }
            }
            _state.update { s ->
                s.copy(slots = s.slots.mapIndexed { i, t ->
                    if (i == index) t.copy(coverThumbs = thumbs, coverThumbsLoading = false) else t
                })
            }
        }
    }

    /**
     * 上移一格（顺序即拼图从上到下的顺序）。**换不动就不重建**：
     * `invalidatePreview()` 会白白抽 3 帧，而没换顺序时旧拼图就是对的。
     */
    fun moveUp(index: Int) {
        if (index <= 0) return
        if (!swapSlots(index, index - 1)) return
        invalidatePreview()
    }

    fun moveDown(index: Int) {
        if (!swapSlots(index, index + 1)) return
        invalidatePreview()
    }

    /** 相邻两格对调。越界或索引无效时返回 false（**不改状态**） */
    private fun swapSlots(i: Int, j: Int): Boolean {
        if (i !in _state.value.slots.indices || j !in _state.value.slots.indices) return false
        _state.update { s ->
            val slots = s.slots.toMutableList()
            val tmp = slots[j]; slots[j] = slots[i]; slots[i] = tmp
            s.copy(slots = slots)
        }
        return true
    }

    private inline fun updateSlot(index: Int, transform: (TriptychSlot) -> TriptychSlot) {
        _state.update { s ->
            if (index !in s.slots.indices) return@update s
            s.copy(slots = s.slots.mapIndexed { i, slot -> if (i == index) transform(slot) else slot })
        }
    }

    /** 结果页「再拼一张」：清空状态与全部临时文件 */
    fun startOver() {
        cleanup()
        _state.update { UiState() }
    }

    /**
     * 预览构建过期标记：构建期间（低端机数百毫秒）用户改了封面/顺序/对齐时置脏。
     * 所有读写都在主线程（调用方均为 UI 动作，构建收尾经主协程回主线程），无需加锁。
     */
    private var previewDirty = false

    /**
     * 重建三格拼图预览 + 预估导出大小。**无门控**：任何参数变化都调它。
     *
     * 原先这里有一道 `phase == Phase.PREVIEW` 的门，等于「参数改了但你还没按
     * 进入预览，所以先不给你看」——预览本来就是实时的东西，门控只制造了
     * 「改了没反应」的时间窗。
     */
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
        // 三样快照同批取：它们必须描述同一次构建，混批取会让「画布尺寸」与
        // 「预估体积」来自两个不同的画质档
        val aspect = _state.value.aspect
        val quality = _state.value.quality
        previewDirty = false
        _state.update { it.copy(previewLoading = true) }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                // 预览：抽帧宽度取 960（屏幕上看足够，省内存）；导出时用整格宽重抽
                runCatching { buildTriptychBitmap(slots, aspect, quality, frameWidth = 960) }.getOrNull()
            }
            if (previewDirty) {
                // 构建期间状态已变：旧结果作废（从未进入 state，可安全回收），
                // 用最新 slots 再来一轮，直到某轮构建期间无新变化才落结果
                bitmap?.recycle()
                startPreviewBuild()
                return@launch
            }
            // 预估按**选段**时长算，不是素材总时长：用户把三段各收到 2s 之后，
            // 产物也是 6s 的视频，再用素材总长估就是拿一个不会发生的数糊弄界面
            val estimated = estimateTriptychBytes(slots.map { it.clip.durationMs }, aspect, quality)
            _state.update {
                it.copy(previewBitmap = bitmap, previewLoading = false, estimatedBytes = estimated)
            }
        }
    }

    /**
     * 静态三格拼图：每格 = 统一比例目标尺寸，竖排无缝。
     * 每格画面 = 用户所选封面帧（或原静态图）按对齐裁切到统一比例。
     *
     * 画布尺寸取自 [Aspect.cellSize] 而非 `refW/refH`——后者是 1080p 档的**参考**
     * 尺寸，用它画出来的拼图在 720p 档下与三段视频的实际转码尺寸对不上。
     *
     * @param quality 必须与调用方传给 `trimVideo` 的那个是同一个，否则成品拼图
     *   与成品视频在同一个画质档位上分家
     * @param frameWidth 抽帧/解码宽度上限：预览用 960（够看且省内存），导出用整格宽
     *   （[CellSize.width]）——v1 预览与导出都按 640 抽帧再放大到格子里，成品封面明显发虚。
     */
    private fun buildTriptychBitmap(
        slots: List<TriptychSlot>,
        aspect: Aspect,
        quality: Quality,
        frameWidth: Int
    ): Bitmap {
        val cell = aspect.cellSize(quality)
        val cellW = cell.width
        val cellH = cell.height
        val result = createBitmap(cellW, cellH * slots.size)
        val canvas = Canvas(result)
        slots.forEachIndexed { index, slot ->
            // 格画面来源交给 [coverSourceFor] 判：未重选封面 → 原静态图，
            // 重选了 → 视频在该时刻的帧（**已按选段收口**，收口不放在这里，
            // 放进 `if/else` 两侧就等于给「收口」和「取帧」各留一个能漏的口子）。
            //
            // 抽帧失败**回退到静态图**，而不是留空：留空的那一格在预览里是透明的，
            // 存成 JPEG 后 alpha 0 压成**一条黑带**，而界面照样写「已保存」。
            // 两条路都拿不到画面时（静态图也解不开）才跳过这一格。
            val cover = when (val source = coverSourceFor(slot.clip)) {
                is CoverSource.Still -> decodeSampled(slot.imageFile, frameWidth)
                is CoverSource.Frame -> extractFrame(
                    slot.videoFile, source.timeMs, MediaMetadataRetriever.OPTION_CLOSEST, frameWidth
                ) ?: run {
                    AppLog.w(
                        "triptych",
                        "第 ${index + 1} 格封面抽帧失败 @${source.timeMs}ms，回退到静态图"
                    )
                    decodeSampled(slot.imageFile, frameWidth)
                }
            } ?: return@forEachIndexed
            val cropped = cropToAspect(cover, aspect.ratio, slot.alignment)
            canvas.drawBitmap(
                cropped, null,
                RectF(0f, index * cellH.toFloat(), cellW.toFloat(), (index + 1) * cellH.toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            if (cropped !== cover) cropped.recycle()
            cover.recycle()
        }
        return result
    }

    /** 采样解码本地图片文件（长边 ≤ [maxWidth]），避免为缩略图/格子做整图解码 */
    private fun decodeSampled(file: File, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
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

    /**
     * 抽帧（带 OPTION 参数与宽度限制）。
     * @param maxWidth 抽帧宽度上限；预览缩略图 264~640，拼图按格子宽传（见 buildTriptychBitmap）
     */
    private fun extractFrame(video: File, timeMs: Long, option: Int, maxWidth: Int = 640): Bitmap? = try {
        val retriever = retrieverFor(video)
        val frame = synchronized(retriever) {
            retriever.getFrameAtTime(timeMs * 1000, option)
        }
        frame?.let { f ->
            if (f.width > maxWidth) {
                val scale = maxWidth.toFloat() / f.width
                f.scale(maxWidth, (f.height * scale).toInt().coerceAtLeast(1)).also {
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

    /**
     * 导出：三段裁切+转码归一（统一尺寸） → 序列拼接 → 与拼图合成 → 保存。
     *
     * `done` 是一次的终点，**这道拒绝必须写在 ViewModel 里**而不是只靠界面把按钮藏起来：
     * 上一轮同一个缺陷就复发过一次（CHANGELOG「三拼的骨架主按钮和阶段主按钮撞车」），
     * `saveVisible` 一旦因为任何理由重新为真，界面挡不住一次重复导出——
     * 而重复导出的后果是相册里多出一份文件，界面还在说「已保存」。
     */
    fun export() {
        val slots = _state.value.slots
        val aspect = _state.value.aspect
        val quality = _state.value.quality
        if (slots.size != 3 || _state.value.exporting || _state.value.done) return
        // 三处必须共用这一个 cellSize：三段视频的转码目标尺寸、拼图画布尺寸、封面抽帧宽度。
        // 混批取值会让「预览里那张图」与「相册里那张图」在 720p 档下是两个尺寸
        val cell = aspect.cellSize(quality)
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
                            // **必须用提取出来的 MP4**：sourceUri 是实况图本身（JPEG 头 + 后挂 MP4），
                            // 直接交给 Media3 Transformer 会按「图片输入」处理——裁剪分数是按视频
                            // 尺寸算的却作用在静态画面上，导出的动态部分要么是静帧、要么直接失败。
                            // 这正是「三拼导出异常」的根因。
                            videoUri = Uri.fromFile(slot.videoFile),
                            startMs = slot.clip.startMs,
                            endMs = slot.clip.endMs,
                            audioOn = slot.clip.audioOn,
                            cropLTRB = crop,
                            targetW = cell.width,
                            targetH = cell.height,
                        )
                    }.recoverCatching {
                        kotlinx.coroutines.delay(1_500)
                        MotionPhotoComposer.trimVideo(
                            context = context,
                            videoUri = Uri.fromFile(slot.videoFile),
                            startMs = slot.clip.startMs,
                            endMs = slot.clip.endMs,
                            audioOn = slot.clip.audioOn,
                            cropLTRB = crop,
                            targetW = cell.width,
                            targetH = cell.height,
                        )
                    }.getOrThrow()
                    exportFiles += trimmed
                    normalized += trimmed to slot
                }
                // 2) 序列拼接（每段独立声音开关）
                _state.update { it.copy(progressText = "拼接视频") }
                val stitched = MotionPhotoComposer.stitchVideos(
                    context = context,
                    segments = normalized.map { (file, slot) -> Uri.fromFile(file) to slot.clip.audioOn },
                )
                exportFiles += stitched
                // 3) 静态三格拼图（与预览同源：同一个 buildTriptychBitmap + 同一个 cellSize）
                _state.update { it.copy(progressText = "合成 LIVE 图") }
                val collage = withContext(Dispatchers.IO) {
                    File.createTempFile("triptych", ".jpg", context.cacheDir).apply {
                        // 导出用整格宽抽帧，封面才清晰；用完立即回收
                        val collageBitmap = buildTriptychBitmap(
                            normalized.map { it.second }, aspect, quality,
                            frameWidth = cell.width
                        )
                        outputStream().use { out ->
                            collageBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        collageBitmap.recycle()
                    }
                }
                exportFiles += collage
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
                    it.copy(
                        exporting = false,
                        progressText = null,
                        success = true,
                        exportName = result.displayName,
                        // 结果页需要停留展示，才能让用户看到「已保存」而不是回到编辑页一头雾水
                        done = true,
                        message = "三拼 LIVE 图已保存到相册"
                    )
                }
                haptics.thud()
                cleanupExportFiles()
            } catch (e: Exception) {
                AppLog.w("triptych", "三拼导出失败：${e.message}")
                _state.update {
                    it.copy(exporting = false, progressText = null, message = "导出失败：${e.message}")
                }
                haptics.double()
                // 只清理本次导出的派生文件：保留槽位源文件，让用户能直接重试
                cleanupExportFiles()
            }
        }
    }

    /** 清理导出派生文件（保留槽位提取出的原视频/静态帧） */
    private fun cleanupExportFiles() {
        exportFiles.forEach { runCatching { it.delete() } }
        exportFiles.clear()
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
        cleanupExportFiles()
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

/**
 * 三格堆叠后的成品尺寸。格宽 × (格高 × 格数)，与 `buildTriptychBitmap` 的画布构造同一口径。
 *
 * 宽只取一格、高乘格数——这是「竖排无缝」的定义，也是屏幕上定 `aspectRatio` 用的那对数。
 * 与 `Aspect.label(cells)` 共用同一口径：标签说「每格 a:b → 整体 c:d」，
 * 这个函数给出 c:d 的**像素**，两者必须同时改。
 */
fun triptychCanvasSize(aspect: Aspect, quality: Quality, cells: Int = 3): CellSize {
    val cell = aspect.cellSize(quality)
    return CellSize(cell.width, cell.height * cells)
}

/**
 * 体积预估：视频 ≈ 格子像素 × 帧数 × 每像素比特数 ÷ 8，静态拼图 JPEG 记 3 MiB。
 *
 * **为什么按像素算而不是按 [Quality.bitrate] 算**：码率**根本没有传到编码器**——
 * `MotionPhotoComposer.trimVideo` 的签名里没有 `bitrate`，`VideoTrimmer` 也没有
 * `setEncoderFactory` / `setVideoEncoderSettings`，于是走的是 Media3 `Transformer` 的
 * 默认编码器工厂（`media3-transformer` 1.9.3 `DefaultEncoderFactory`）。那份实现
 * （`javap` 反编译核对过）在没拿到显式码率时**只会**从源 `Format.averageBitrate`
 * 或按 `getSuggestedBitrate(宽, 高, 帧率) = (int)(0.14 × 宽 × 高 × 帧率)` 自己估一个，
 * 两条都与本项目的档位无关。拿 [Quality.bitrate] 乘出来的数（12Mbps / 6Mbps）是一个
 * 编码器从未被告知的值：切 P720 在界面上少一半，产物却不会。
 *
 * 画质档位**当前唯一真正改到的就是分辨率**（`export()` 把 `cell.width/height` 交给
 * `trimVideo` 的 `targetW/targetH`），所以预估就该跟着像素走：像素少了，编码器要吞的
 * 比特自然少了，方向是对的。27:16 是唯一两档同尺寸的比例（短边 640 < 720 的上限，
 * 不缩，见 [cellSize]），它的两档预估**必然相等**——这正是应该显示的结果。
 *
 * 0.14 比特/像素/帧这个量级与 Media3 自己的默认值同数量级（30fps 下 1920×1080 ≈ 8.7Mbps），
 * 取的是**量级**，不是它的公式：真源帧率本文件没读（`TriptychSlot` 不带 frameRate），
 * 画面复杂度也会让实际码率浮动。**这是估算，不是编码器实测值**——
 * 界面上那一行也照此写了话，别把它改成一句看起来更确定的说法。
 *
 * 其余两点不变：时长是**选段**时长（`ClipSpec.durationMs`），不是素材总长；负值按 0 计。
 */
fun estimateTriptychBytes(clipDurationsMs: List<Long>, aspect: Aspect, quality: Quality): Long {
    val seconds = clipDurationsMs.sumOf { it.coerceAtLeast(0L) } / 1000.0
    val cell = aspect.cellSize(quality)
    val video = (seconds * cell.width * cell.height * ESTIMATE_FRAMES_PER_SECOND * BITS_PER_PIXEL_FRAME / 8)
        .toLong()
    return video + COLLAGE_JPEG_BYTES
}

/** 预估用的帧率。源帧率不在 [TriptychSlot] 里（`MediaMetadataRetriever` 读的是时长与尺寸） */
private const val ESTIMATE_FRAMES_PER_SECOND = 30f

/** 每像素每帧的比特数。量级对齐 Media3 `DefaultEncoderFactory.getSuggestedBitrate` 的 0.14 */
private const val BITS_PER_PIXEL_FRAME = 0.14f

/** 静态拼图 JPEG 的体积常数。3 MiB，与 [estimateTriptychBytes] 的调用点无关 */
private const val COLLAGE_JPEG_BYTES = 3L * 1024 * 1024

/**
 * 一格素材解析完成后的**初始选段**：从头取，最多到 [ClipMath.MAX_CLIP_MS]。
 *
 * 走 [ClipMath.clampStart] / [ClipMath.clampEnd] 而不是直接写 `ClipSpec(0, duration, null)`，
 * 是为了让 10s 素材的初始选段就是 5s——导出侧 `VideoTrimmer.trim` 会把超长的段
 * 截到 `startMs + MAX_CLIP_MS`，初始态若按素材总长给，用户看到的起点就已经是
 * 一个导出时会**静默改短**的选段（这正是 ClipBounds 要共源的原因）。
 *
 * **短素材那一支是有名字的，不是「忘了钳制」**：素材短于 [ClipMath.MIN_CLIP_MS] 时
 * 不存在合法选段，两个钳制各自塌陷到素材两端，于是初始选段 = 整个素材，
 * `ClipSpec.durationMs` 小于 [ClipMath.MIN_CLIP_MS]。这落在 [ClipMath] 写明的
 * 退化区间那一支上——**零长度但有序**；此处非零（是整段素材），但同样不是
 * 「最短片段」，而是「没有比整段更短的合法选段可选」。时长读出 0 的元数据则
 * 两边都塌到 0，`startMs == endMs == 0`，仍然有序（不会造出倒置区间）。
 *
 * 注意导出侧还会再钳一次：`VideoTrimmer.trim` 第 73 行 `coerceAtLeast(clampedStart + MIN_CLIP_MS)`，
 * 于是短素材那一支传下去的是一个**超出素材末尾**的止点。Media3 在容器边界上怎么处理它，
 * 本文件管不到、也未在此验证——`:app` 单测跑不到 Media3。本函数保证的是
 * 「初始选段自身不撒谎」（它等于素材真实时长），不是「导出请求等于选段」。
 *
 * 这条规则同时是 [LiveTriptychViewModel.TriptychSlot.clip] 的默认实参，
 * 所以「新建一个槽位」与「解析一张素材」走的是同一条路径，不存在两份初始选段。
 */
fun initialClipSpec(mediaDurationMs: Long): ClipSpec {
    val wanted = minOf(mediaDurationMs.coerceAtLeast(0L), ClipMath.MAX_CLIP_MS)
    val start = ClipMath.clampStart(0L, wanted)
    return ClipSpec(
        startMs = start,
        endMs = ClipMath.clampEnd(wanted, start, mediaDurationMs.coerceAtLeast(0L)),
        // 初始态是「未重选封面」：画面用实况图里的原始静态图。
        // 不能写 0L——0 是候选条带第一格的合法封面时刻，写 0 等于替用户选了一帧
        coverMs = null,
    )
}
