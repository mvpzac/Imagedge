package com.imagedge.camera.feature.edit.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.imagedge.camera.data.transfer.DownloadLocation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.data.lut.UserLutStore
import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.ExposureAnalysis
import com.imagedge.camera.image.Geometry
import com.imagedge.camera.image.HistoryList
import com.imagedge.camera.image.ImagePipeline
import com.imagedge.camera.image.LumaHistogram
import com.imagedge.camera.image.NormRect
import com.imagedge.camera.image.lutKeyOrDefault
import com.imagedge.camera.image.rank
import com.imagedge.camera.image.strengthOrDefault
import com.imagedge.camera.share.ExportConfig
import com.imagedge.camera.share.ExportFormat
import com.imagedge.camera.share.ExportManager
import com.imagedge.camera.lut.ColorAdjust
import com.imagedge.camera.lut.CubeLut
import com.imagedge.camera.lut.CubeLutParser
import com.imagedge.camera.lut.LutProcessor
import com.imagedge.camera.ui.feedback.Haptics
import com.imagedge.camera.ui.feedback.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
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
    EXPORT("导出"),
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
    /** 唯一的编辑状态：几何 + 调色 + 滤镜 */
    val recipe: EditRecipe = EditRecipe.EMPTY,
    /** 撤销游标；只在 commitEdit() 时前进 */
    val history: HistoryList<EditRecipe> = HistoryList(),
    /** 长按预览时置位：界面显示原图，用于对比 */
    val comparing: Boolean = false,
    /** 当前编辑分区 */
    val tab: EditTab = EditTab.COLOR,
    /** 归一化裁剪框（相对「拉直+旋转+翻转」之后的画面） */
    val crop: NormRect = NormRect.FULL,
    val cropAspect: CropAspect = CropAspect.FREE,
    /** 裁剪模式的底图（已应用几何、**未裁剪**，裁剪框画在它上面） */
    val cropBase: Bitmap? = null,
    /**
     * 长按对比用的「之前」画面：几何与裁剪**已应用**、调色与 LUT **未应用**。
     *
     * 不能直接用 [original]：那是裸解码图，一旦用户旋转/拉直/裁剪过，
     * 长按看到的和松手后看到的就是两幅不同构图的画，对比本身失去意义。
     * 无几何也无裁剪时它为 null，此时 original 就是正确的对照（构图完全一致）。
     */
    val compareBase: Bitmap? = null,
    val processing: Boolean = false,
    /** 导出中（与预览处理分开，避免「导出时预览还在算」把按钮状态搅乱） */
    val exporting: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
    /** 滤镜 → 用户照片渲染的缩略图（含原图项） */
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val thumbsLoading: Boolean = false,
    /**
     * 当前成品的亮度直方图（与 [filtered] 同一次渲染算出）。
     *
     * 取景页早就有峰值密度/高光警告，编辑器却没有直方图：调高光恢复、调对比度时
     * 只看画面很容易判断过头，而削顶在缩略图上根本看不出来。
     */
    val histogram: LumaHistogram? = null,
    /**
     * 导出配置（格式 / 质量 / 元数据策略）。
     *
     * 默认与原实现一致（JPEG），但质量与 EXIF 策略不再写死：相机照片的 EXIF 里
     * 常有 GPS 坐标与机身信息，「导出即保留全部」对分享到公开平台是不安全的。
     */
    val exportConfig: ExportConfig = ExportConfig()
) {
    val hasImage: Boolean get() = original != null

    // 每个 state 实例只算一次：fields() 是纯映射，同一份 (recipe, crop) 的答案必然相同，
    // 而这几个名字在同一个实例上会被反复读（滑条每动一下就 copy 出一个新实例，见各 setter）。
    // 写成 `get()` 就是每次访问重筛一遍步骤列表——量不大，但没有理由白做。
    private val derived by lazy { recipe.fields(crop) }
    val selectedKey: String get() = derived.selectedKey
    val strength: Int get() = derived.strength
    val adjust: ColorAdjust get() = derived.adjust
    val quarterTurns: Int get() = derived.quarterTurns
    val flipHorizontal: Boolean get() = derived.flipHorizontal
    val flipVertical: Boolean get() = derived.flipVertical
    val straighten: Float get() = derived.straighten
    val hasGeometryEdits: Boolean get() = derived.hasGeometryEdits

    /** 有没有「还没存盘的改动」；判定只在 [EditRecipeFields.hasEdits] 一处 */
    val hasEdits: Boolean get() = derived.hasEdits

    /** 裁剪模式底图的宽高比（宽/高），裁剪框换算与比例预设都要用 */
    val cropBaseAspect: Float
        get() = (cropBase ?: original)?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
}

/** 界面上原来那组编辑字段，现在全部由配方派生；字段名与默认值照旧（与 alpha08 一致） */
data class EditRecipeFields(
    val selectedKey: String,
    val strength: Int,
    val adjust: ColorAdjust,
    val crop: NormRect,
    val quarterTurns: Int,
    val flipHorizontal: Boolean,
    val flipVertical: Boolean,
    val straighten: Float,
) {
    val hasGeometryEdits: Boolean
        get() = quarterTurns % 4 != 0 || flipHorizontal || flipVertical ||
            kotlin.math.abs(straighten) > 0.05f || !crop.isFull

    /** 有没有「还没存盘的改动」——强度单独改不算（与 alpha08 的 hasEdits 同语义） */
    val hasEdits: Boolean
        get() = selectedKey != FILTER_NONE || !adjust.isIdentity || hasGeometryEdits
}

/**
 * 折叠成界面字段。
 *
 * [crop] 作为参数进来而不是塞进配方：裁剪框是**用户正在拖的实时值**，`cropAspect` 是界面选择态，
 * 两者都不该污染撤销单位（拖动每帧都会写 `setCropRect`，进了配方就是一帧一条历史）；
 * `EditStep.Crop` 只在渲染与导出时按 live 值现拼（见 `applyCurrentFilter` / `save`）。
 */
fun EditRecipe.fields(crop: NormRect = NormRect.FULL): EditRecipeFields {
    val rotate = steps.filterIsInstance<EditStep.Rotate>().firstOrNull()
    return EditRecipeFields(
        selectedKey = lutKeyOrDefault(FILTER_NONE),
        strength = strengthOrDefault(80),
        adjust = colorAdjust,
        crop = crop,
        quarterTurns = rotate?.let { (((it.degrees / 90f).toInt() % 4) + 4) % 4 } ?: 0,
        flipHorizontal = steps.any { it is EditStep.Flip && it.horizontal },
        flipVertical = steps.any { it is EditStep.Flip && !it.horizontal },
        straighten = steps.filterIsInstance<EditStep.Straighten>().firstOrNull()?.degrees ?: 0f,
    )
}

/**
 * 在配方那**一份** rotate 槽位上累加一次 90°（[step] 为 +1 顺时针 / -1 逆时针）。
 *
 * 度数必须先加进来再写回：`EditRecipe.with` 按身份替换，rotate 只有一个槽位，
 * 直接写 `Rotate(90f)` 的话第二次按下等于把 90° 又设了一遍——画面不动、也不报错。
 * 转满一圈回到 0 时删掉这个槽位，配方里不留「等效于没转」的步骤。
 */
internal fun rotatedRecipe(recipe: EditRecipe, step: Int): EditRecipe {
    val turns = (((recipe.fields().quarterTurns + step) % 4) + 4) % 4
    return if (turns == 0) recipe.without<EditStep.Rotate>()
    else recipe.with(EditStep.Rotate(90f * turns))
}

/**
 * 翻转开关的写入侧：当前开着就删掉**那一个方向**，关着就占住那一份槽位。
 *
 * 关掉时用的是 `EditRecipe.without(step)`（按身份删），不能用 reified 的
 * `without<EditStep.Flip>()`（按类型删）——后者会把另一个方向一起清掉，而水平与垂直是两枚
 * 独立的 chip、可以同时开着（真机验收点过：两道都开，关掉水平，垂直仍然亮着）。
 * 抽成纯函数是为了给它一条会红的用例：这个错在界面上不报错，只会被当成「我按错了」。
 */
internal fun toggledFlip(recipe: EditRecipe, horizontal: Boolean): EditRecipe {
    val flip = EditStep.Flip(horizontal = horizontal)
    val fields = recipe.fields()
    return if (if (horizontal) fields.flipHorizontal else fields.flipVertical) {
        recipe.without(flip)
    } else {
        recipe.with(flip)
    }
}

/**
 * 换照片时给历史**播种**：第一条就是「刚载入」那一格。
 *
 * `HistoryList.undo()` 不能越过第一条，所以带着空历史进来时用户改完第一笔仍会看到
 * `canUndo == false`——「回到没动过的样子」这条最该有的撤销根本不存在。种子必须与当时的配方
 * 同一个值，否则第一次 undo 会把用户推到一个照片本来没有的状态。
 */
internal fun seededHistoryOf(recipe: EditRecipe): HistoryList<EditRecipe> =
    HistoryList<EditRecipe>().record(recipe)

/**
 * 换到下一张照片时带过去的部分：调色与「记住的强度」。
 *
 * 与 alpha08 逐字一致——它 `loadPicked` 时带 `strength` 与 `adjust`，而 `selectedKey` 回落到默认
 * （原图）。滤镜选择属于「这张照片用哪个风格」，调参数属于「我这次的影调偏好」，
 * 前者跟着照片走、后者跟着人走。配方里那条 key 为「原图」的 Lut 步骤不是残留：
 * 强度要活下来就得让它继续占位（见 strengthOrDefault 的折叠等价测试）。
 */
internal fun carriedColour(previous: EditRecipe, noFilterKey: String): EditRecipe {
    val strength = previous.strengthOrDefault(80)
    val adjust = previous.colorAdjust
    val base = EditRecipe.EMPTY.with(EditStep.Lut(noFilterKey, strength))
    return if (adjust.isIdentity) base else base.with(EditStep.Color(adjust))
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
    private var loadJob: Job? = null
    private var applyJob: Job? = null
    private var thumbnailJob: Job? = null
    private var exportJob: Job? = null
    private val sourceGeneration = AtomicLong(0)
    private val renderGeneration = AtomicLong(0)

    /** 滤镜处理协程无挂起点、cancel 停不住；用互斥串行化，防新旧任务并发践踏复用缓冲 */
    private val applyMutex = Mutex()

    /**
     * 复用的像素转换缓冲（P1-9，详见 [applyCurrentFilter] 内注释）。
     * 换图（尺寸变化）后由调用方重建。
     */
    private var convPixels: IntArray? = null
    private var convRgba: ByteArray? = null
    private var convOutPixels: IntArray? = null
    /** 直方图采样缓冲。不复用 convPixels：那是调色路径的活缓冲，共享会在下一次渲染时串台 */
    private var histoPixels: IntArray? = null

    /** 缩略图渲染源（128px 级的小图，供每个滤镜生成预览） */
    private var thumbSource: Bitmap? = null

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
        val generation = sourceGeneration.incrementAndGet()
        loadJob?.cancel()
        thumbnailJob?.cancel()
        applyJob?.cancel()
        renderGeneration.incrementAndGet()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            var decoded: Bitmap? = null
            var preview: Bitmap? = null
            var thumb: Bitmap? = null
            var published = false
            try {
                decoded = decodeFromUri(uri, LUT_DECODE_MAX_DIM) ?: run {
                    if (sourceGeneration.get() == generation) {
                        _state.update { it.copy(message = "图片解码失败（格式不支持或文件不可读）") }
                    }
                    return@launch
                }
                coroutineContext.ensureActive()
                preview = createPreviewSource(decoded!!)
                thumb = createScaled(decoded!!, LUT_THUMB_MAX_DIM)
                coroutineContext.ensureActive()
                if (sourceGeneration.get() != generation) return@launch

                val previousState = _state.value
                val previousPreview = previewSource
                val previousThumb = thumbSource
                previewSource = preview
                thumbSource = thumb
                convPixels = null
                convRgba = null
                convOutPixels = null
                histoPixels = null
                _state.update {
                    // 上一张的调色与记住的强度跟着人走，滤镜选择与几何跟着照片走（理由见 carriedColour）
                    val carried = carriedColour(it.recipe, FILTER_NONE)
                    PhotoEditState(
                        sourceUri = uri,
                        original = decoded,
                        recipe = carried,
                        // 历史必须在这里播下种子（理由见 seededHistoryOf），而且种子就是新状态那份配方：
                        // 两者取不同的值时，第一次撤销会退回一张照片从来没有过的状态
                        history = seededHistoryOf(carried),
                        // 导出配置也要带过去。不带的话「仅清除位置」只在当前这张有效，
                        // 换下一张就悄悄回到 KEEP_ALL——用户以为自己在保护隐私，
                        // 而 GPS 只是晚了一张照片才跟着出去
                        exportConfig = it.exportConfig,
                        thumbnails = emptyMap()
                    )
                }
                published = true
                releaseBitmapsLater(
                    buildList {
                        addAll(bitmapsIn(previousState))
                        previousPreview?.let(::add)
                        previousThumb?.let(::add)
                    }
                )
                applyCurrentFilter(generation)
                buildThumbnails(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sourceGeneration.get() == generation) {
                    AppLog.w("lut", "选图失败：${e.message}")
                    _state.update { it.copy(message = "选图失败：${e.message}") }
                }
            } finally {
                if (!published) recycleUnique(listOfNotNull(decoded, preview, thumb))
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
    private fun buildThumbnails(expectedGeneration: Long = sourceGeneration.get()) {
        val source = thumbSource ?: return
        thumbnailJob?.cancel()
        _state.update { it.copy(thumbsLoading = true) }
        thumbnailJob = viewModelScope.launch(Dispatchers.Default) {
            val thumbs = HashMap<String, Bitmap>()
            try {
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
                // 原图项直接用源缩略图
                thumbs[FILTER_NONE] = source
                val options = _filters.value
                for (option in options) {
                    coroutineContext.ensureActive()
                    if (sourceGeneration.get() != expectedGeneration || thumbSource !== source) return@launch
                    if (option.key == FILTER_NONE) continue
                    val lut = option.lut ?: lutCache[option.key] ?: continue
                    runCatching {
                        // CpuLutProcessor does not mutate its input; avoid one RGBA copy per LUT.
                        val out = processor.apply(rgba, w, h, lut.data, lut.size, 100, ColorAdjust.NONE)
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
                    }.onFailure {
                        if (it is CancellationException) throw it
                        AppLog.w("lut", "缩略图渲染失败 ${option.label}：${it.message}")
                    }
                }
                coroutineContext.ensureActive()
                if (sourceGeneration.get() != expectedGeneration || thumbSource !== source) return@launch
                val old = _state.value.thumbnails.values.toList()
                _state.update { it.copy(thumbnails = thumbs, thumbsLoading = false) }
                releaseBitmapsLater(old)
            } finally {
                if (sourceGeneration.get() != expectedGeneration || thumbSource !== source || !coroutineContext.isActive) {
                    recycleUnique(thumbs.values.filter { it !== source })
                }
            }
        }
    }

    /** 切换滤镜并应用 */
    fun selectFilter(key: String) {
        // 取消上一个强度/调色的防抖任务：否则 200ms 后它会用新状态再跑一遍（重复全图处理）
        applyJob?.cancel()
        _state.update {
            it.copy(
                recipe = it.recipe.with(EditStep.Lut(key, it.recipe.strengthOrDefault(80))),
                message = null
            )
        }
        applyCurrentFilter()
        commitEdit()
    }

    /** 强度变化（防抖：变化停止 200ms 后应用；进历史由调用方在操作结束时调 commitEdit） */
    fun setStrength(value: Int) {
        _state.update {
            it.copy(recipe = it.recipe.with(EditStep.Lut(it.selectedKey, value.coerceIn(0, 100))))
        }
        scheduleApply()
    }

    /**
     * 改导出配置。**故意不触发 scheduleApply()**：
     * 格式/质量/元数据只影响落盘，不影响画面，重算一次预览是白烧 CPU 与 GPU。
     */
    fun setExportConfig(config: ExportConfig) {
        _state.update { it.copy(exportConfig = config) }
    }

    /** 基础调色变化（增益/分区/对比度/饱和度任一，防抖同强度） */
    fun setAdjust(adjust: ColorAdjust) {
        _state.update { it.copy(recipe = it.recipe.with(EditStep.Color(adjust)), message = null) }
        scheduleApply()
    }

    /** 一键重置：回到原图（清掉滤镜、强度、全部调色与几何） */
    fun resetEdits() {
        applyJob?.cancel()
        _state.update {
            it.copy(
                recipe = EditRecipe.EMPTY,
                crop = NormRect.FULL,
                cropAspect = CropAspect.FREE,
                // 走 record 而不是直接换配方：重置自己就该是一格可撤销的历史
                history = it.history.record(EditRecipe.EMPTY),
                message = null
            )
        }
        applyCurrentFilter()
    }

    /** 一次连续操作结束（滑条松手、几何按钮按下之后）才进历史 */
    fun commitEdit() {
        _state.update { it.copy(history = it.history.record(it.recipe)) }
    }

    /** 退回上一格历史；退不动（已是最旧一条）时无操作 */
    fun undoEdit() {
        val history = _state.value.history
        if (!history.canUndo) return
        val previous = history.undo()
        _state.update { it.copy(history = previous, recipe = previous.current ?: EditRecipe.EMPTY) }
        applyCurrentFilter()
    }

    /** 前进一格历史；走不动时无操作 */
    fun redoEdit() {
        val history = _state.value.history
        if (!history.canRedo) return
        val next = history.redo()
        _state.update { it.copy(history = next, recipe = next.current ?: EditRecipe.EMPTY) }
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

    private fun applyCurrentFilter(expectedGeneration: Long = sourceGeneration.get()) {
        val snapshot = _state.value
        val original = snapshot.original ?: return
        // 预览源优先：交互式处理在降采样副本上跑，比全分辨率快约 6 倍
        val processSource = previewSource ?: original
        val option = _filters.value.firstOrNull { it.key == snapshot.selectedKey }
            ?: return
        val lut = option.lut ?: lutCache[option.key]
        val adjust = snapshot.adjust
        val strength = snapshot.strength
        val crop = snapshot.crop
        val geoSteps = snapshot.recipe.geometryOnly
        // 本次渲染是按这份配方起的头；防抖窗口内配方又被改了一次时，旧参数的结果不许进 state
        val requestRecipe = snapshot.recipe
        val request = renderGeneration.incrementAndGet()
        applyJob?.cancel()
        _state.update { it.copy(processing = true) }
        applyJob = viewModelScope.launch(Dispatchers.Default) {
            var geometryOnly: Bitmap? = null
            var cropped: Bitmap? = null
            var coloredCropped: Bitmap? = null
            var coloredFull: Bitmap? = null
            var published = false
            // 已被 state.compareBase 接管的那张：finally 的回收清单必须放行它，
            // 否则界面上会留一个已回收的位图（崩溃点是异步的，查不到现场）
            var heldByState: Bitmap? = null
            // 处理全程无挂起点，cancel() 停不住已在跑的任务；用互斥串行化，
            // 避免新旧任务并发读写复用的像素缓冲造成画面错乱
            try {
                applyMutex.withLock {
                    coroutineContext.ensureActive()
                    if (sourceGeneration.get() != expectedGeneration ||
                        renderGeneration.get() != request || previewSource !== processSource ||
                        requestRecipe != _state.value.recipe
                    ) return@withLock
                    // 1) 几何：拉直 → 旋转 → 翻转（**不裁剪**，裁剪模式的底图要用它）
                    geometryOnly = if (geoSteps.isEmpty()) {
                        processSource
                    } else {
                        ImagePipeline(geoSteps).renderGeometry(processSource)
                    }
                    coroutineContext.ensureActive()
                    // 2) 裁剪（坐标基于几何后的画面）
                    cropped = if (crop.isFull) {
                        geometryOnly
                    } else {
                        ImagePipeline(listOf(EditStep.Crop(crop))).renderGeometry(geometryOnly)
                    }
                    // 3) 颜色：调色 + LUT（一次像素遍历）
                    coloredCropped = applyPipelineTo(cropped, lut, strength, adjust)
                    coroutineContext.ensureActive()
                    // 裁剪模式的底图 = 几何 + 颜色（让用户带着最终观感去框选）
                    coloredFull = if (cropped === geometryOnly) {
                        coloredCropped
                    } else {
                        applyPipelineTo(geometryOnly, lut, strength, adjust)
                    }
                    coroutineContext.ensureActive()
                    if (sourceGeneration.get() != expectedGeneration ||
                        renderGeneration.get() != request || previewSource !== processSource ||
                        requestRecipe != _state.value.recipe
                    ) return@withLock
                    val oldFiltered = _state.value.filtered
                    val oldCropBase = _state.value.cropBase
                    val oldCompareBase = _state.value.compareBase
                    // 构图已定、尚未调色的那张，交给长按对比用。与 processSource 同一个对象时
                    // 不持有（那是缓存的预览源，生命周期归 loadPicked 管），
                    // 而这种情况下 original 本来就是正确对照——构图完全一致。
                    val compare = cropped.takeIf { it !== processSource }
                    // 先登记再发布：state 一旦拿着它，finally 就不许再回收它，
                    // 中间哪怕抛一个 catch 没接住的 Throwable 也不能留个空窗
                    heldByState = compare
                    _state.update {
                        it.copy(
                            filtered = coloredCropped,
                            cropBase = coloredFull,
                            compareBase = compare,
                            histogram = computeHistogram(coloredCropped),
                            processing = false
                        )
                    }
                    published = true
                    // 只回收被替换掉的旧的那几张；compare 已经进 state，不能回收
                    releaseBitmapsLater(listOfNotNull(oldFiltered, oldCropBase, oldCompareBase))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sourceGeneration.get() == expectedGeneration && renderGeneration.get() == request) {
                    AppLog.w("edit", "渲染失败：${e.message}")
                    _state.update { it.copy(processing = false, message = "处理失败：${e.message}") }
                }
            } finally {
                recycleUnique(
                    listOfNotNull(geometryOnly, cropped)
                        .filter { it !== processSource && it !== heldByState }
                )
                if (!published) recycleUnique(listOfNotNull(coloredCropped, coloredFull))
            }
        }
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
        commitEdit()
        val nextRecipe = rotatedRecipe(_state.value.recipe, step)
        _state.update { s ->
            val rotated = Geometry.rotate90(s.crop, step)
            s.copy(
                recipe = nextRecipe,
                // 旋转后画面宽高互换，比例预设要按新画面重新贴合
                crop = if (s.cropAspect.ratio == null) rotated
                else Geometry.maxRectForAspect(
                    effectiveImageAspect(s.copy(recipe = nextRecipe)),
                    s.cropAspect.ratio
                ),
                comparing = false
            )
        }
        applyCurrentFilter()
        commitEdit()
    }

    /** 左右翻转：开→写进那一份 flip:true 槽位；关→只删 flip:true，另一个方向不动 */
    fun toggleFlipHorizontal() {
        commitEdit()
        _state.update { s ->
            // 关掉一个方向必须按身份删，理由与用例见 toggledFlip
            s.copy(
                recipe = toggledFlip(s.recipe, horizontal = true),
                crop = Geometry.flipHorizontal(s.crop)
            )
        }
        applyCurrentFilter()
        commitEdit()
    }

    /** 上下翻转：与左右翻转各自独立一份槽位，可同时开着 */
    fun toggleFlipVertical() {
        commitEdit()
        _state.update { s ->
            s.copy(
                recipe = toggledFlip(s.recipe, horizontal = false),
                crop = Geometry.flipVertical(s.crop)
            )
        }
        applyCurrentFilter()
        commitEdit()
    }

    /** 拉直角度（-45..45），防抖后重渲染；进历史由调用方在操作结束时调 commitEdit */
    fun setStraighten(degrees: Float) {
        val clamped = degrees.coerceIn(-45f, 45f)
        _state.update {
            it.copy(
                // 阈值内等于「没拉直」，槽位直接删掉，配方里不留几乎看不见的角度
                recipe = if (kotlin.math.abs(clamped) > 0.05f) it.recipe.with(EditStep.Straighten(clamped))
                else it.recipe.without<EditStep.Straighten>()
            )
        }
        scheduleApply()
    }

    /** 重置几何（保留调色与滤镜） */
    fun resetGeometry() {
        _state.update { s ->
            s.copy(
                // 只留 rank 非 0 的步骤（调色与滤镜），几何整段清空
                recipe = EditRecipe(s.recipe.steps.filter { it.rank != 0 }),
                crop = NormRect.FULL,
                cropAspect = CropAspect.FREE
            )
        }
        applyCurrentFilter()
        commitEdit()
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
     * 成品预览的亮度直方图。
     *
     * 与取景页同一套采样口径（[ExposureAnalysis.sampleStride] 降到 ~320px 长边），
     * 所以这里的形状和监看工作台上看到的是可比的。
     * 失败返回 null：直方图是辅助信息，不该因为它让整次渲染报错。
     */
    private fun computeHistogram(bitmap: Bitmap): LumaHistogram? = runCatching {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return@runCatching null
        val size = w * h
        val buffer = reuseIntArray(histoPixels, size) ?: IntArray(size).also { histoPixels = it }
        bitmap.getPixels(buffer, 0, w, 0, 0, w, h)
        ExposureAnalysis.histogram(buffer, w, h, ExposureAnalysis.sampleStride(w, h))
    }.getOrNull()

    /**
     * 对一张位图执行完整管线：基础调色 → LUT → 强度混合。
     *
     * 复用三块转换缓冲（P1-9）：强度/调色滑条每次防抖后都会全图重算，
     * 原先每次新建 3 个大数组（Int 24MB + Byte 24MB + Int 24MB）会让 GC 疯狂抖动。
     * **仅在预览尺寸下复用**——导出走 [renderFullResolution] 的独立条带缓冲，避免与预览互相践踏。
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
     * 导出：**按可用内存尽可能按原分辨率**重算，格式/质量/元数据策略按 [PhotoEditState.exportConfig]
     * 交给 [ExportManager]（与分享导出同一套）。
     *
     * 修复三处原实现的问题：
     * 1. 原实现把 1600px 的编辑副本当导出源 → 6000px 照片导出成 1600px（损失 90% 像素）；
     *    现在从源 URI 重新按全分辨率解码（内存不够时按 2 的幂降级，并在提示里说明）。
     * 2. 全分辨率一次性分配三块大缓冲（24MP ≈ 288MB）会 OOM → 改为**按条带处理**。
     * 3. 导出件曾经丢失全部 EXIF（拍摄时间/机型/GPS）→ 现在按策略复制元数据后提交相册，
     *    并写入 IS_PENDING / DATE_TAKEN。
     */
    fun save() {
        val editSnapshot = _state.value
        if (editSnapshot.original == null) return
        val uri = editSnapshot.sourceUri
        if (uri == null) {
            _state.update { it.copy(message = "源图已失效，请重新选择照片") }
            return
        }
        if (exportJob?.isActive == true || editSnapshot.exporting) return
        val generation = sourceGeneration.get()
        exportJob = viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null, saved = false) }
            try {
                val savedName = withContext(Dispatchers.IO) {
                    var exportSource: Bitmap? = null
                    var geometryApplied: Bitmap? = null
                    var rendered: Bitmap? = null
                    var temp: File? = null
                    try {
                        // 1) 全分辨率（或内存允许的最大分辨率）重算
                        val decoded = decodeFromUri(uri, exportMaxDim())
                            ?: throw IllegalStateException("源图解码失败，请重新选择照片")
                        exportSource = decoded
                        coroutineContext.ensureActive()
                        val option = _filters.value.firstOrNull { it.key == editSnapshot.selectedKey }
                        val lut = option?.lut ?: option?.key?.let { lutCache[it] }
                        val adjust = editSnapshot.adjust
                        val strength = editSnapshot.strength
                        // 几何（含裁剪）在全分辨率上先做，再按条带调色——
                        // 导出必须与预览用同一套几何参数，否则「框选的不是导出的」
                        // 裁剪仍按 live 的裁剪框现拼在末尾，与预览那条管线同一套参数
                        val steps = editSnapshot.recipe.geometryOnly + EditStep.Crop(editSnapshot.crop)
                        val transformed = ImagePipeline(steps).renderGeometry(decoded)
                        geometryApplied = transformed
                        coroutineContext.ensureActive()
                        val result = renderFullResolution(transformed, lut, strength, adjust)
                        rendered = result
                        coroutineContext.ensureActive()

                        // 2) 交给 ExportManager 落盘（格式/质量/元数据策略都在那里）→ 提交相册
                        // 格式 / 质量 / 元数据策略交给 ExportManager 统一处理（与分享导出同一套），
                        // 文件名扩展名必须跟着格式走，否则相册里会出现一个后缀是 .jpg 的 WebP
                        val config = editSnapshot.exportConfig
                        val exported = ExportManager(context).exportRendered(
                            bitmap = result,
                            exifSource = uri,
                            config = config,
                            nameBase = "IMAGEDGE_EDIT_${System.currentTimeMillis()}",
                        )
                        // temp 只为 finally 的清理而存在：commitToGallery 把副本流进相册，
                        // 缓存件一律由下面的 finally 删除
                        temp = exported
                        coroutineContext.ensureActive()
                        commitToGallery(exported, exported.name, config.format, uri)
                        exported.name
                    } finally {
                        recycleUnique(listOfNotNull(rendered, geometryApplied, exportSource))
                        temp?.let { runCatching { it.delete() } }
                    }
                }
                if (sourceGeneration.get() == generation) {
                    _state.update { it.copy(exporting = false, saved = true, message = "已保存到相册：$savedName") }
                }
                haptics.thud()
                snackbarController.show("已保存到相册：$savedName")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sourceGeneration.get() == generation) {
                    AppLog.w("lut", "导出失败：${e.message}")
                    _state.update { it.copy(exporting = false, message = "保存失败：${e.message}") }
                    haptics.double()
                }
            } finally {
                if (sourceGeneration.get() == generation) {
                    _state.update { it.copy(exporting = false) }
                }
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

    /**
     * 提交到相册：优先用户自选的 SAF 目录，否则 DCIM/Imagedge。
     * 走 IS_PENDING → 写完置 0，并写入 DATE_TAKEN，保证相册排序与完整性。
     *
     * MIME 必须跟着 [format] 走：写死 image/jpeg 会让相册把一个 WebP 当成 JPEG
     * 索引，接收方按 MIME 分派解码器时直接解不出来。
     */
    private fun commitToGallery(temp: File, name: String, format: ExportFormat, source: Uri): String {
        val resolver = context.contentResolver
        val treeUriStr = DownloadLocation.treeUri(context)
        // 拍摄时间读**源图**，不读导出件：STRIP_ALL 与 PNG 两种情况下导出件里根本没有 EXIF，
        // 那时 DATE_TAKEN 写不进去，去年拍的照片修完就插进相册「今天」那一堆里。
        // MediaStore 这一列是本机的排序键、不随文件分享出去，所以它与「清除全部信息」不冲突
        val dateMillis = captureDate(source)?.let(::parseExifDate)

        if (treeUriStr != null) {
            val treeUri = treeUriStr.toUri()
            val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = android.provider.DocumentsContract.createDocument(
                resolver, dirUri, format.mime, name
            )
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
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, format.mime)
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

    /** 源图的拍摄时间（EXIF `DateTimeOriginal`）；读不到返回 null，不因此中断导出 */
    private fun captureDate(source: Uri): String? = runCatching {
        context.contentResolver.openFileDescriptor(source, "r")?.use {
            ExifInterface(it.fileDescriptor).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        }
    }.getOrNull()

    /** EXIF 时间格式 `yyyy:MM:dd HH:mm:ss` → 毫秒时间戳 */
    private fun parseExifDate(value: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .apply { isLenient = true }
            .parse(value)
            ?.time
    }.getOrNull()

    override fun onCleared() {
        sourceGeneration.incrementAndGet()
        renderGeneration.incrementAndGet()
        loadJob?.cancel()
        thumbnailJob?.cancel()
        applyJob?.cancel()
        exportJob?.cancel()
        recycleUnique(
            buildList {
                addAll(bitmapsIn(_state.value))
                previewSource?.let(::add)
                thumbSource?.let(::add)
            }
        )
        previewSource = null
        thumbSource = null
        convPixels = null
        convRgba = null
        convOutPixels = null
        histoPixels = null
        super.onCleared()
    }

    private fun bitmapsIn(state: PhotoEditState): List<Bitmap> = buildList {
        state.original?.let(::add)
        state.filtered?.let(::add)
        state.cropBase?.let(::add)
        state.compareBase?.let(::add)
        addAll(state.thumbnails.values)
    }

    /**
     * Give Compose a few frames to release the previous state, then recycle only bitmaps that are
     * not retained by the current state or the current processing sources.
     */
    private fun releaseBitmapsLater(candidates: Collection<Bitmap>) {
        if (candidates.isEmpty()) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(BITMAP_RELEASE_GRACE_MS)
            val retained = identityBitmapSet().apply {
                addAll(bitmapsIn(_state.value))
                previewSource?.let(::add)
                thumbSource?.let(::add)
            }
            recycleUnique(candidates.filter { it !in retained })
        }
    }

    private fun recycleUnique(bitmaps: Collection<Bitmap>) {
        val unique = identityBitmapSet()
        for (bitmap in bitmaps) {
            if (unique.add(bitmap) && !bitmap.isRecycled) runCatching { bitmap.recycle() }
        }
    }

    private fun identityBitmapSet(): MutableSet<Bitmap> =
        Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

    companion object {
        private const val BITMAP_RELEASE_GRACE_MS = 250L
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
