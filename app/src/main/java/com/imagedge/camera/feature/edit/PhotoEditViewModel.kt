package com.imagedge.camera.feature.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.data.lut.UserLutStore
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.Geometry
import com.imagedge.camera.image.ImagePipeline
import com.imagedge.camera.image.NormRect
import com.imagedge.camera.lut.ColorAdjust
import com.imagedge.camera.lut.CubeLut
import com.imagedge.camera.lut.CubeLutParser
import com.imagedge.camera.lut.LutProcessor
import com.imagedge.camera.ui.feedback.Haptics
import com.imagedge.camera.ui.feedback.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.File
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 相册编辑调节——几何（裁剪 / 旋转 / 翻转 / 拉直）+ 调色（基础参数 + LUT 滤镜）；
 *              导出按原分辨率重算并保留 EXIF。
 *     version: 2.0
 * </pre>
 */

/** 编辑分区（面板切换；同一时间只展示一组控件，避免一屏塞满滑条） */
enum class EditTab(val label: String) {
    COLOR("调色"),
    CROP("裁剪"),
    ROTATE("旋转"),
}

/** 裁剪比例预设；[ratio] 为像素比例（宽/高），null = 自由 */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("自由", null),
    R1_1("1:1", 1f),
    R4_3("4:3", 4f / 3f),
    R3_2("3:2", 3f / 2f),
    R16_9("16:9", 16f / 9f),
    R9_16("9:16", 9f / 16f),
}

/** 单个滤镜选项（内置或用户导入）
 * @param type 适用类型：决定它在编辑页归入哪一排（三类输入曲线互不相通） */
data class LutFilterOption(
    val key: String,
    val label: String,
    val lut: CubeLut?,
    val type: com.imagedge.camera.data.lut.LutType = com.imagedge.camera.data.lut.LutType.CREATIVE
)

/** 原图（不应用滤镜）选项 key */
const val FILTER_NONE = "none"

/**
 * LUT 编辑的解码上限（最长边，px）。
 *
 * 从 2048 下调到 1600：像素量减少 39%，配合 [PhotoEditViewModel] 里的缓冲复用，
 * 单次滤镜应用的堆峰值从约 120MB 压到约 30MB —— 连续切换滤镜不再逼近
 * 大堆应用的 OOM 阈值。1600px 对编辑预览与导出（JPEG 95）仍完全够用。
 */
private const val LUT_DECODE_MAX_DIM = 1600

/**
 * 交互式预览的处理上限（最长边，px）。
 *
 * 预览/拖强度滑条在 [LUT_PREVIEW_MAX_DIM] 上跑，像素量约为全分辨率
 * （[LUT_DECODE_MAX_DIM]）的 1/6，单次滤镜应用从 1~2s 降到数百毫秒，拖动滑条
 * 明显跟手；导出（[PhotoEditViewModel.save]）时再按全分辨率重算，不牺牲成品清晰度。
 */
private const val LUT_PREVIEW_MAX_DIM = 640

/**
 * 滤镜缩略图的长边（px）。所有滤镜都用**用户自己的照片**渲染缩略图——
 * 只写滤镜名（如「Teal Orange」）用户无法预判效果，这是滤镜功能最影响体验的一环。
 * 22 个滤镜 × 128² ≈ 36 万像素，一次装配耗时几十毫秒。
 */
private const val LUT_THUMB_MAX_DIM = 128

/**
 * 导出时的可用内存预算占比。
 *
 * 全分辨率导出至少要同时持有「源位图 + 输出位图」两份 ARGB_8888，
 * 24MP 源图即 96MB×2。这里按 JVM 堆上限的 1/3 反推可处理的最大长边，
 * 装不下时按 2 的幂采样降级——**宁可略降分辨率，也不能 OOM 崩掉正在编辑的照片**。
 */
private const val EXPORT_HEAP_BUDGET_RATIO = 3

data class PhotoEditState(
    /** 当前编辑的源图 URI（界面据此判断是否已加载、以及是否需要重新载入） */
    val sourceUri: Uri? = null,
    val original: Bitmap? = null,
    val filtered: Bitmap? = null,
    val selectedKey: String = FILTER_NONE,
    val strength: Int = 80,
    /** 基础调色（曝光/对比度/饱和度/色温），与 LUT 同一次处理完成 */
    val adjust: ColorAdjust = ColorAdjust.NONE,
    /** 长按预览时置位：界面显示原图，用于对比 */
    val comparing: Boolean = false,
    /** 当前编辑分区 */
    val tab: EditTab = EditTab.COLOR,
    /** 归一化裁剪框（相对「拉直+旋转+翻转」之后的画面） */
    val crop: NormRect = NormRect.FULL,
    val cropAspect: CropAspect = CropAspect.FREE,
    /** 顺时针 90° 旋转次数（0..3） */
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** 拉直角度（-45..45，正 = 顺时针） */
    val straighten: Float = 0f,
    /** 裁剪模式的底图（已应用几何、**未裁剪**，裁剪框画在它上面） */
    val cropBase: Bitmap? = null,
    val processing: Boolean = false,
    /** 导出中（与预览处理分开，避免「导出时预览还在算」把按钮状态搅乱） */
    val exporting: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
    /** 滤镜 → 用户照片渲染的缩略图（含原图项） */
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val thumbsLoading: Boolean = false
) {
    val hasImage: Boolean get() = original != null

    /** 是否有任何调整（LUT 或基础调色），用于「重置」按钮的可用态 */
    val hasEdits: Boolean
        get() = selectedKey != FILTER_NONE || !adjust.isIdentity || hasGeometryEdits

    /** 是否有几何编辑 */
    val hasGeometryEdits: Boolean
        get() = quarterTurns % 4 != 0 || flipHorizontal || flipVertical ||
            kotlin.math.abs(straighten) > 0.05f || !crop.isFull

    /** 裁剪模式底图的宽高比（宽/高），裁剪框换算与比例预设都要用 */
    val cropBaseAspect: Float
        get() = (cropBase ?: original)?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
}

@HiltViewModel
class PhotoEditViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val processor: LutProcessor,
    private val userLutStore: UserLutStore,
    private val snackbarController: SnackbarController,
    private val haptics: Haptics
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoEditState())
    val state: StateFlow<PhotoEditState> = _state.asStateFlow()

    /** 滤镜列表：原图 + 资产内置 .cube + 用户管理目录的 .cube（IO 异步装配） */
    private val _filters = MutableStateFlow(
        listOf(LutFilterOption(FILTER_NONE, "原图", null))
    )
    val filters: StateFlow<List<LutFilterOption>> = _filters.asStateFlow()

    /**
     * LUT 缓存（P1-9）。
     *
     * **必须是 ConcurrentHashMap**：写入发生在 [loadBuiltins]/[loadUserLuts] 的
     * Dispatchers.IO 协程，读取发生在 [applyCurrentFilter] 的 Dispatchers.Default 协程。
     * 原先是裸 `mutableMapOf`（HashMap），跨线程无保护地并发读写可能造成数据损坏
     * 或直接抛 ConcurrentModificationException。
     */
    private val lutCache = java.util.concurrent.ConcurrentHashMap<String, CubeLut>()
    private var applyJob: Job? = null

    /** 滤镜处理协程无挂起点、cancel 停不住；用互斥串行化，防新旧任务并发践踏复用缓冲 */
    private val applyMutex = Mutex()

    /**
     * 复用的像素转换缓冲（P1-9，详见 [applyCurrentFilter] 内注释）。
     * 换图（尺寸变化）后由调用方重建。
     */
    private var convPixels: IntArray? = null
    private var convRgba: ByteArray? = null
    private var convOutPixels: IntArray? = null

    /** 缩略图渲染源（128px 级的小图，供每个滤镜生成预览） */
    private var thumbSource: Bitmap? = null

    /** 当前编辑的源图 URI（导出时需要重新按全分辨率解码） */
    private var sourceUri: Uri? = null

    /**
     * 预览处理源（降采样副本）。交互式滤镜/强度调整在它上面跑，比全分辨率快约 6 倍；
     * 全分辨率 [PhotoEditState.original] 仅用于展示与导出重算。
     */
    @Volatile
    private var previewSource: Bitmap? = null

    init {
        loadBuiltins()
        loadUserLuts()
        // 滤镜表是异步装配的：若用户先选好照片、内置/导入滤镜随后才装完，
        // 新出现的滤镜就不会有缩略图。这里在滤镜表变化后补齐缺失项。
        viewModelScope.launch {
            _filters.collect { options ->
                if (thumbSource == null) return@collect
                val have = _state.value.thumbnails
                if (options.any { it.key !in have }) buildThumbnails()
            }
        }
    }

    /** 资产内置：S-Log3 富士胶片模拟（app/src/main/assets/luts） */
    private fun loadBuiltins() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val names = context.assets.list("luts")?.filter { it.endsWith(".cube") } ?: return@launch
                val options = names.sorted().mapNotNull { name ->
                    val content = context.assets.open("luts/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                    val lut = CubeLutParser.parse(content) ?: return@mapNotNull null
                    lutCache[name] = lut
                    // 内置 LUT 按命名前缀归入对应排（SLog2_/SLog3_，其余算创意滤镜）
                    LutFilterOption("asset_$name", displayLabel(name), lut, LutType.fromFileName(name))
                }
                // P1-9：必须用 update 原子读改写。loadBuiltins 与 loadUserLuts 并发执行，
                // 原先 `value = value + options` 的「读-算-写」三步之间可能插入对方的写，
                // 后写者会把先写者的结果整个覆盖掉 —— 用户导入的 LUT 会随机消失。
                _filters.update { current ->
                    listOf(current.first()) + options + current.drop(1)
                }
                AppLog.i("lut", "内置 LUT 装配完成：${options.size} 个")
            } catch (e: Exception) {
                AppLog.w("lut", "内置 LUT 装配失败：${e.message}")
            }
        }
    }

    /** 用户管理目录的 .cube */
    private fun loadUserLuts() {
        viewModelScope.launch(Dispatchers.IO) {
            val options = userLutStore.list().mapNotNull { name ->
                runCatching {
                    val lut = CubeLutParser.parse(userLutStore.readText(name)) ?: return@mapNotNull null
                    lutCache["user_$name"] = lut
                    // 用户导入的按其声明的类型归类（导入时弹窗声明，未声明则按文件名推断）
                    LutFilterOption("user_$name", displayLabel(name), lut, userLutStore.typeOf(name))
                }.getOrNull()
            }
            _filters.update { it + options }
        }
    }

    /** 展示名：去 .cube 扩展名，下划线转空格 */
    private fun displayLabel(fileName: String): String =
        fileName.removeSuffix(".cube").removeSuffix(".CUBE").replace('_', ' ')

    /** 选择图片（系统图片选择器返回的 content uri） */
    fun loadPicked(uri: Uri) {
        sourceUri = uri
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = decodeFromUri(uri, LUT_DECODE_MAX_DIM) ?: run {
                    _state.update { it.copy(message = "图片解码失败（格式不支持或文件不可读）") }
                    return@launch
                }
                previewSource = createPreviewSource(bitmap)
                thumbSource = createScaled(bitmap, LUT_THUMB_MAX_DIM)
                _state.update {
                    PhotoEditState(
                        sourceUri = uri,
                        original = bitmap,
                        strength = it.strength,
                        adjust = it.adjust,
                        thumbnails = emptyMap()
                    )
                }
                applyCurrentFilter()
                buildThumbnails()
            } catch (e: Exception) {
                AppLog.w("lut", "选图失败：${e.message}")
                _state.update { it.copy(message = "选图失败：${e.message}") }
            }
        }
    }

    /**
     * 按 URI 解码并**应用 EXIF 方向**。
     *
     * 原实现把整个文件读成 ByteArray 再 BitmapFactory.decodeByteArray：
     * 既没有应用 EXIF Orientation（竖拍照片在编辑页与导出成品里都是躺着的），
     * 又多占一份完整文件大小的内存。改为 fd 路径 + ImageDecoder 兜底，
     * 两条路径都带方向归一。
     */
    private fun decodeFromUri(uri: Uri, targetLong: Int): Bitmap? {
        val fromFd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetLong) sample *= 2
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val rotation = runCatching { ExifInterface(pfd.fileDescriptor).rotationDegrees }.getOrNull() ?: 0
                applyRotation(decoded, rotation)
            }
        }.onFailure { AppLog.w("lut", "fd 解码失败：${it.message}") }.getOrNull()
        if (fromFd != null && fromFd.width > 0) return fromFd

        // 兜底：ImageDecoder（HEIF/HDR 等 BitmapFactory 解不了的格式，自动应用方向）
        return runCatching {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val longSide = maxOf(info.size.width, info.size.height)
                if (longSide > targetLong) {
                    var s = 1
                    while (longSide / (s * 2) >= targetLong) s *= 2
                    decoder.setTargetSampleSize(s)
                }
            }
        }.onFailure { AppLog.w("lut", "ImageDecoder 解码失败：${it.message}") }.getOrNull()
    }

    /** 按角度旋转（0 度原样返回；旋转后的新位图接管所有权） */
    private fun applyRotation(src: Bitmap?, degrees: Int): Bitmap? {
        if (src == null || degrees % 360 == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }.getOrNull()
        if (rotated != null && rotated !== src) runCatching { src.recycle() }
        return rotated ?: src
    }

    /** 长边缩放到指定尺寸（小于目标则原样返回） */
    private fun createScaled(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val scale = maxDim.toFloat() / maxSide
        return src.scale(
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1)
        )
    }

    /**
     * 用**用户自己的照片**给每个滤镜渲染一张缩略图（强度取满、不含基础调色）。
     * 结果写进 state，UI 的滤镜条直接显示效果预览。
     */
    private fun buildThumbnails() {
        val source = thumbSource ?: return
        _state.update { it.copy(thumbsLoading = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val w = source.width
            val h = source.height
            val size = w * h
            val pixels = IntArray(size)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgba = ByteArray(size * 4)
            for (i in 0 until size) {
                val px = pixels[i]
                rgba[i * 4] = (px shr 16 and 0xFF).toByte()
                rgba[i * 4 + 1] = (px shr 8 and 0xFF).toByte()
                rgba[i * 4 + 2] = (px and 0xFF).toByte()
                rgba[i * 4 + 3] = (px shr 24 and 0xFF).toByte()
            }
            val thumbs = HashMap<String, Bitmap>()
            // 原图项直接用源缩略图
            thumbs[FILTER_NONE] = source
            for (option in _filters.value) {
                if (option.key == FILTER_NONE) continue
                val lut = option.lut ?: lutCache[option.key] ?: continue
                runCatching {
                    val out = processor.apply(rgba.copyOf(), w, h, lut.data, lut.size, 100, ColorAdjust.NONE)
                    val bmp = createBitmap(w, h)
                    val outPixels = IntArray(size)
                    for (i in 0 until size) {
                        outPixels[i] = (out[i * 4 + 3].toInt() and 0xFF) shl 24 or
                            ((out[i * 4].toInt() and 0xFF) shl 16) or
                            ((out[i * 4 + 1].toInt() and 0xFF) shl 8) or
                            (out[i * 4 + 2].toInt() and 0xFF)
                    }
                    bmp.setPixels(outPixels, 0, w, 0, 0, w, h)
                    thumbs[option.key] = bmp
                }.onFailure { AppLog.w("lut", "缩略图渲染失败 ${option.label}：${it.message}") }
            }
            _state.update { it.copy(thumbnails = thumbs, thumbsLoading = false) }
        }
    }

    /** 切换滤镜并应用 */
    fun selectFilter(key: String) {
        // 取消上一个强度/调色的防抖任务：否则 200ms 后它会用新状态再跑一遍（重复全图处理）
        applyJob?.cancel()
        _state.update { it.copy(selectedKey = key, message = null) }
        applyCurrentFilter()
    }

    /** 强度变化（防抖：变化停止 200ms 后应用） */
    fun setStrength(value: Int) {
        _state.update { it.copy(strength = value) }
        scheduleApply()
    }

    /** 基础调色变化（曝光/对比度/饱和度/色温，防抖同强度） */
    fun setAdjust(adjust: ColorAdjust) {
        _state.update { it.copy(adjust = adjust, message = null) }
        scheduleApply()
    }

    /** 一键重置：回到原图（清掉滤镜、强度、全部调色） */
    fun resetEdits() {
        applyJob?.cancel()
        _state.update {
            it.copy(
                selectedKey = FILTER_NONE,
                strength = 80,
                adjust = ColorAdjust.NONE,
                quarterTurns = 0,
                flipHorizontal = false,
                flipVertical = false,
                straighten = 0f,
                crop = NormRect.FULL,
                cropAspect = CropAspect.FREE,
                message = null
            )
        }
        applyCurrentFilter()
    }

    /** 长按预览：true = 显示原图（撤销全部效果） */
    fun setComparing(comparing: Boolean) {
        _state.update { it.copy(comparing = comparing) }
    }

    private fun scheduleApply() {
        applyJob?.cancel()
        applyJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            applyCurrentFilter()
        }
    }

    private fun applyCurrentFilter() {
        val original = _state.value.original ?: return
        // 预览源优先：交互式处理在降采样副本上跑，比全分辨率快约 6 倍
        val processSource = previewSource ?: original
        val option = _filters.value.firstOrNull { it.key == _state.value.selectedKey }
            ?: return
        val lut = option.lut ?: lutCache[option.key]
        val snapshot = _state.value
        val adjust = snapshot.adjust
        val strength = snapshot.strength
        val crop = snapshot.crop
        val geoSteps = geometrySteps(snapshot)
        applyJob?.cancel()
        _state.update { it.copy(processing = true) }
        applyJob = viewModelScope.launch(Dispatchers.Default) {
            // 处理全程无挂起点，cancel() 停不住已在跑的任务；用互斥串行化，
            // 避免新旧任务并发读写复用的像素缓冲造成画面错乱
            applyMutex.withLock {
                if (!isActive) return@launch
                try {
                    // 1) 几何：拉直 → 旋转 → 翻转（**不裁剪**，裁剪模式的底图要用它）
                    val geometryOnly = if (geoSteps.isEmpty()) {
                        processSource
                    } else {
                        ImagePipeline(geoSteps).renderGeometry(processSource)
                    }
                    // 2) 裁剪（坐标基于几何后的画面）
                    val cropped = if (crop.isFull) {
                        geometryOnly
                    } else {
                        ImagePipeline(listOf(EditStep.Crop(crop))).renderGeometry(geometryOnly)
                    }
                    // 3) 颜色：调色 + LUT（一次像素遍历）
                    val coloredCropped = applyPipelineTo(cropped, lut, strength, adjust)
                    // 裁剪模式的底图 = 几何 + 颜色（让用户带着最终观感去框选）
                    val coloredFull = if (cropped === geometryOnly) {
                        coloredCropped
                    } else {
                        applyPipelineTo(geometryOnly, lut, strength, adjust)
                    }
                    // 中间产物回收（绝不回收 previewSource 本身）
                    if (cropped !== geometryOnly && cropped !== processSource) runCatching { cropped.recycle() }
                    if (geometryOnly !== processSource) runCatching { geometryOnly.recycle() }
                    // 被取消的旧任务不得落结果：否则会把新滤镜的 selectedKey/状态覆盖回去
                    if (isActive) {
                        _state.update {
                            it.copy(
                                filtered = coloredCropped,
                                cropBase = coloredFull,
                                processing = false
                            )
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("edit", "渲染失败：${e.message}")
                    _state.update { it.copy(processing = false, message = "处理失败：${e.message}") }
                }
            }
        }
    }

    /**
     * 几何步骤（顺序与 [ImagePipeline] 的约定一致：拉直 → 旋转 → 翻转；裁剪单独处理）。
     * 裁剪不放进这里，因为裁剪模式的底图必须是「未裁剪」的。
     */
    private fun geometrySteps(s: PhotoEditState = _state.value): List<EditStep> {
        val steps = mutableListOf<EditStep>()
        if (kotlin.math.abs(s.straighten) > 0.05f) steps += EditStep.Straighten(s.straighten)
        val turns = ((s.quarterTurns % 4) + 4) % 4
        if (turns != 0) steps += EditStep.Rotate(90f * turns)
        if (s.flipHorizontal) steps += EditStep.Flip(horizontal = true)
        if (s.flipVertical) steps += EditStep.Flip(horizontal = false)
        return steps
    }

    // ── 几何编辑 API ──────────────────────────────────────────────

    /** 切换编辑分区；进入裁剪时确保底图与裁剪框就绪 */
    fun setTab(tab: EditTab) {
        _state.update { it.copy(tab = tab, comparing = false, message = null) }
        if (tab == EditTab.CROP) {
            // 已选比例预设时才重贴比例；自由比例下**不能**重置用户的裁剪框
            if (_state.value.cropAspect.ratio != null) {
                _state.update {
                    it.copy(crop = Geometry.maxRectForAspect(effectiveImageAspect(it), it.cropAspect.ratio))
                }
            }
        }
        // 统一重渲染：裁剪框/几何可能在别的分区被改过，预览与底图都要跟上
        applyCurrentFilter()
    }

    /** 拖动裁剪框（不触发重渲染：裁剪模式下显示的是底图 + 覆盖层，松手/离开裁剪页才需要成品） */
    fun setCropRect(rect: NormRect) {
        _state.update { it.copy(crop = rect.sanitized()) }
    }

    /**
     * 选择裁剪比例：把裁剪框收成该比例能占满画面的最大矩形。
     * @param silent 进入裁剪页时的初始化调用（不重渲染，覆盖层会自然显示）
     */
    fun setCropAspect(aspect: CropAspect, silent: Boolean = false) {
        val forced = Geometry.maxRectForAspect(effectiveImageAspect(_state.value), aspect.ratio)
        _state.update { it.copy(cropAspect = aspect, crop = forced) }
        if (!silent) applyCurrentFilter()
    }

    /** 顺时针/逆时针 90°；裁剪框同步旋转，锁定比例时重新贴合该比例 */
    fun rotate(clockwise: Boolean) {
        val step = if (clockwise) 1 else -1
        _state.update { s ->
            val turns = (((s.quarterTurns + step) % 4) + 4) % 4
            val rotated = Geometry.rotate90(s.crop, step)
            s.copy(
                quarterTurns = turns,
                // 旋转后画面宽高互换，比例预设要按新画面重新贴合
                crop = if (s.cropAspect.ratio == null) rotated
                else Geometry.maxRectForAspect(
                    effectiveImageAspect(s.copy(quarterTurns = turns)),
                    s.cropAspect.ratio
                ),
                comparing = false
            )
        }
        applyCurrentFilter()
    }

    fun toggleFlipHorizontal() {
        _state.update { it.copy(flipHorizontal = !it.flipHorizontal, crop = Geometry.flipHorizontal(it.crop)) }
        applyCurrentFilter()
    }

    fun toggleFlipVertical() {
        _state.update { it.copy(flipVertical = !it.flipVertical, crop = Geometry.flipVertical(it.crop)) }
        applyCurrentFilter()
    }

    /** 拉直角度（-45..45），防抖后重渲染 */
    fun setStraighten(degrees: Float) {
        _state.update { it.copy(straighten = degrees.coerceIn(-45f, 45f)) }
        scheduleApply()
    }

    /** 重置几何（保留调色与滤镜） */
    fun resetGeometry() {
        _state.update {
            it.copy(
                quarterTurns = 0,
                flipHorizontal = false,
                flipVertical = false,
                straighten = 0f,
                crop = NormRect.FULL,
                cropAspect = CropAspect.FREE
            )
        }
        applyCurrentFilter()
    }

    /** 只重置裁剪（保留旋转/翻转/拉直与调色） */
    fun resetCrop() {
        _state.update { it.copy(crop = NormRect.FULL, cropAspect = CropAspect.FREE) }
        applyCurrentFilter()
    }

    /** 当前几何（旋转/拉直）之后画面的宽高比（宽/高） */
    private fun effectiveImageAspect(s: PhotoEditState): Float {
        val base = previewSource ?: s.original ?: return 4f / 3f
        val aspect = base.width.toFloat() / base.height
        return if (((s.quarterTurns % 4) + 4) % 4 % 2 == 1) 1f / aspect else aspect
    }

    /**
     * 对一张位图执行完整管线：基础调色 → LUT → 强度混合。
     *
     * 复用三块转换缓冲（P1-9）：强度/调色滑条每次防抖后都会全图重算，
     * 原先每次新建 3 个大数组（Int 24MB + Byte 24MB + Int 24MB）会让 GC 疯狂抖动。
     * **仅在预览尺寸下复用**——导出走独立缓冲（见 [exportBitmap]），避免与预览互相践踏。
     */
    private suspend fun applyPipelineTo(
        src: Bitmap,
        lut: CubeLut?,
        strength: Int,
        adjust: ColorAdjust,
    ): Bitmap {
        // GPU 直通路径：一次完成「调色 + LUT」，省掉 Bitmap→IntArray→RGBA→IntArray→Bitmap
        // 的两趟 CPU 拷贝（GPU 实现见 GpuLutProcessor；不支持时返回 null 走下面的 CPU 实现）
        if (processor.supportsBitmapPath) {
            val gpu = processor.applyToBitmap(
                src,
                lut?.data ?: LutProcessor.EMPTY_LUT,
                lut?.size ?: 0,
                strength,
                adjust
            )
            if (gpu != null) return gpu
        }
        val w = src.width
        val h = src.height
        val size = w * h
        val pixels = reuseIntArray(convPixels, size) ?: IntArray(size).also { convPixels = it }
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgba = reuseByteArray(convRgba, size * 4) ?: ByteArray(size * 4).also { convRgba = it }
        for (i in 0 until size) {
            val px = pixels[i]
            rgba[i * 4] = (px shr 16 and 0xFF).toByte()
            rgba[i * 4 + 1] = (px shr 8 and 0xFF).toByte()
            rgba[i * 4 + 2] = (px and 0xFF).toByte()
            rgba[i * 4 + 3] = (px shr 24 and 0xFF).toByte()
        }
        val out = if (lut == null) {
            processor.applyAdjustOnly(rgba, w, h, adjust)
        } else {
            processor.apply(rgba, w, h, lut.data, lut.size, strength, adjust)
        }
        val result = createBitmap(w, h)
        val outPixels = reuseIntArray(convOutPixels, size) ?: IntArray(size).also { convOutPixels = it }
        packRgba(out, outPixels, size)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    /** RGBA8 字节数组 → ARGB int 数组 */
    private fun packRgba(rgba: ByteArray, out: IntArray, pixelCount: Int) {
        for (i in 0 until pixelCount) {
            out[i] = (rgba[i * 4 + 3].toInt() and 0xFF) shl 24 or
                ((rgba[i * 4].toInt() and 0xFF) shl 16) or
                ((rgba[i * 4 + 1].toInt() and 0xFF) shl 8) or
                (rgba[i * 4 + 2].toInt() and 0xFF)
        }
    }

    /** 取尺寸匹配的复用缓冲；尺寸不符（换图）则返回 null 由调用方重建 */
    private fun reuseIntArray(buffer: IntArray?, expectedSize: Int): IntArray? =
        buffer?.takeIf { it.size == expectedSize }

    private fun reuseByteArray(buffer: ByteArray?, expectedSize: Int): ByteArray? =
        buffer?.takeIf { it.size == expectedSize }

    /**
     * 导出：**按可用内存尽可能按原分辨率**重算，并保留原图 EXIF。
     *
     * 修复三处原实现的问题：
     * 1. 原实现把 1600px 的编辑副本当导出源 → 6000px 照片导出成 1600px（损失 90% 像素）；
     *    现在从源 URI 重新按全分辨率解码（内存不够时按 2 的幂降级，并在提示里说明）。
     * 2. 全分辨率一次性分配三块大缓冲（24MP ≈ 288MB）会 OOM → 改为**按条带处理**。
     * 3. 导出件丢失全部 EXIF（拍摄时间/机型/GPS）→ 写进缓存临时文件，复制源 EXIF，
     *    再提交到相册，并写入 IS_PENDING / DATE_TAKEN。
     */
    fun save() {
        if (_state.value.original == null) return
        val uri = sourceUri
        if (uri == null) {
            _state.update { it.copy(message = "源图已失效，请重新选择照片") }
            return
        }
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null, saved = false) }
            try {
                val savedName = withContext(Dispatchers.IO) {
                    // 1) 全分辨率（或内存允许的最大分辨率）重算
                    val exportSource = decodeFromUri(uri, exportMaxDim())
                        ?: throw IllegalStateException("源图解码失败，请重新选择照片")
                    val option = _filters.value.firstOrNull { it.key == _state.value.selectedKey }
                    val lut = option?.lut ?: option?.key?.let { lutCache[it] }
                    val adjust = _state.value.adjust
                    val strength = _state.value.strength
                    // 几何（含裁剪）在全分辨率上先做，再按条带调色——
                    // 导出必须与预览用同一套几何参数，否则「框选的不是导出的」
                    val snapshot = _state.value
                    val steps = geometrySteps(snapshot) + EditStep.Crop(snapshot.crop)
                    val geometryApplied = ImagePipeline(steps).renderGeometry(exportSource)
                    val rendered = renderFullResolution(geometryApplied, lut, strength, adjust)
                    if (geometryApplied !== exportSource) runCatching { geometryApplied.recycle() }
                    runCatching { exportSource.recycle() }
                    // 2) 写入缓存文件 → 复制 EXIF → 提交相册
                    val temp = File.createTempFile("lutexport", ".jpg", context.cacheDir)
                    temp.outputStream().use { out ->
                        rendered.compress(Bitmap.CompressFormat.JPEG, 96, out)
                    }
                    rendered.recycle()
                    copyExif(uri, temp)
                    val name = "IMAGEDGE_EDIT_${System.currentTimeMillis()}.jpg"
                    commitToGallery(temp, name)
                    temp.delete()
                    name
                }
                _state.update { it.copy(exporting = false, saved = true, message = "已保存到相册：$savedName") }
                haptics.thud()
                snackbarController.show("已保存到相册：$savedName")
            } catch (e: Exception) {
                AppLog.w("lut", "导出失败：${e.message}")
                _state.update { it.copy(exporting = false, message = "保存失败：${e.message}") }
                haptics.double()
            }
        }
    }

    /**
     * 计算本次导出允许的最大长边。
     *
     * 全分辨率导出需同时持有源位图与输出位图（各 4 字节/像素）。
     * 按堆上限的 1/[EXPORT_HEAP_BUDGET_RATIO] 反推像素上限，再换算成边长；
     * 至少保证 2048px，避免极端内存环境下导出到不可用的小图。
     */
    private fun exportMaxDim(): Int {
        val budgetBytes = Runtime.getRuntime().maxMemory() / EXPORT_HEAP_BUDGET_RATIO
        // 两份位图 → 每像素 8 字节
        val maxPixels = (budgetBytes / 8).coerceAtLeast(2048L * 2048L)
        val maxSide = kotlin.math.sqrt(maxPixels.toDouble()).toInt()
        return maxSide.coerceIn(2048, 6000)
    }

    /**
     * 全分辨率处理：**按水平条带**跑，避免一次性分配整图的三块缓冲。
     * 条带高度 256 行时，6000px 宽的临时缓冲约 256×6000×(4+4+4) ≈ 18MB。
     */
    private suspend fun renderFullResolution(
        src: Bitmap,
        lut: CubeLut?,
        strength: Int,
        adjust: ColorAdjust,
    ): Bitmap {
        // GPU 直通：大图正是 GPU 收益最大的场景（实现内部按条带渲染，避免一次性申请两张全尺寸纹理）
        if (processor.supportsBitmapPath) {
            val gpu = processor.applyToBitmap(
                src,
                lut?.data ?: LutProcessor.EMPTY_LUT,
                lut?.size ?: 0,
                strength,
                adjust
            )
            if (gpu != null) return gpu
        }
        val w = src.width
        val h = src.height
        val out = createBitmap(w, h)
        val stripH = 256
        var y = 0
        while (y < h) {
            val rows = minOf(stripH, h - y)
            val count = w * rows
            val pixels = IntArray(count)
            src.getPixels(pixels, 0, w, 0, y, w, rows)
            val rgba = ByteArray(count * 4)
            for (i in 0 until count) {
                val px = pixels[i]
                rgba[i * 4] = (px shr 16 and 0xFF).toByte()
                rgba[i * 4 + 1] = (px shr 8 and 0xFF).toByte()
                rgba[i * 4 + 2] = (px and 0xFF).toByte()
                rgba[i * 4 + 3] = (px shr 24 and 0xFF).toByte()
            }
            val processed = if (lut == null) {
                processor.applyAdjustOnly(rgba, w, rows, adjust)
            } else {
                processor.apply(rgba, w, rows, lut.data, lut.size, strength, adjust)
            }
            val outPixels = IntArray(count)
            packRgba(processed, outPixels, count)
            out.setPixels(outPixels, 0, w, 0, y, w, rows)
            y += rows
            // 长任务让出调度权，避免整页在导出期间完全无响应
            yield()
        }
        return out
    }

    /** 把源图的 EXIF（含方向、拍摄时间、机型、GPS）复制到导出件 */
    private fun copyExif(source: Uri, target: File) {
        runCatching {
            val srcExif = context.contentResolver.openFileDescriptor(source, "r")?.use {
                ExifInterface(it.fileDescriptor)
            } ?: return
            val dstExif = ExifInterface(target.absolutePath)
            for (tag in COPY_EXIF_TAGS) {
                srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
            }
            // 像素已在编辑阶段转正 → 方向必须写回 NORMAL，否则相册会再转一次
            dstExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dstExif.saveAttributes()
        }.onFailure { AppLog.w("lut", "EXIF 复制失败（成品仍可用，仅丢元数据）：${it.message}") }
    }

    /**
     * 提交到相册：优先用户自选的 SAF 目录，否则 DCIM/Imagedge。
     * 走 IS_PENDING → 写完置 0，并写入 DATE_TAKEN，保证相册排序与完整性。
     */
    private fun commitToGallery(temp: File, name: String): String {
        val resolver = context.contentResolver
        val treeUriStr = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("download_tree_uri", null)
        val dateTaken = runCatching {
            ExifInterface(temp.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        }.getOrNull()
        val dateMillis = dateTaken?.let(::parseExifDate)

        if (treeUriStr != null) {
            val treeUri = treeUriStr.toUri()
            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = android.provider.DocumentsContract.createDocument(resolver, dirUri, "image/jpeg", name)
                ?: throw IllegalStateException("无法在所选目录创建文件（权限或路径无效）")
            try {
                resolver.openOutputStream(fileUri)?.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("无法打开输出流")
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, fileUri) }
                throw e
            }
            return fileUri.toString()
        }

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DCIM}/Imagedge"
            )
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            dateMillis?.let { put(android.provider.MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("创建相册条目失败")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入相册")
            runCatching {
                resolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null, null
                )
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return name
    }

    /** EXIF 时间格式 `yyyy:MM:dd HH:mm:ss` → 毫秒时间戳 */
    private fun parseExifDate(value: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .apply { isLenient = true }
            .parse(value)
            ?.time
    }.getOrNull()

    companion object {
        /** 导出时复制的 EXIF 字段（拍摄参数 + 时间 + 作者信息） */
        private val COPY_EXIF_TAGS = arrayOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
        )
    }

    /** 预览处理源：最长边缩到 [LUT_PREVIEW_MAX_DIM] 以内（交互式处理提速约 6 倍） */
    private fun createPreviewSource(full: Bitmap): Bitmap {
        val maxSide = maxOf(full.width, full.height)
        if (maxSide <= LUT_PREVIEW_MAX_DIM) return full
        val scale = LUT_PREVIEW_MAX_DIM.toFloat() / maxSide
        return full.scale(
            (full.width * scale).toInt().coerceAtLeast(1),
            (full.height * scale).toInt().coerceAtLeast(1)
        )
    }

}
