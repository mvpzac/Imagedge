package com.imagedge.camera.feature.edit.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import com.imagedge.camera.data.transfer.DownloadLocation
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.core.export.ExportLimits
import com.imagedge.camera.data.edit.EditRecipeDocument
import com.imagedge.camera.data.edit.EditRecipePresetStore
import com.imagedge.camera.data.edit.sanitizePresetName
import com.imagedge.camera.data.hdr.HdrExport
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.data.lut.UserLutStore
import com.imagedge.camera.image.EditRecipe
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.ExposureAnalysis
import com.imagedge.camera.image.Geometry
import com.imagedge.camera.image.HistoryList
import com.imagedge.camera.image.LensCorrectionParams
import com.imagedge.camera.image.correctLensDistortion
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
import com.imagedge.camera.lut.KeyAxis
import com.imagedge.camera.lut.LutProcessor
import com.imagedge.camera.lut.RangeKey
import com.imagedge.camera.lut.SelectiveAdjust
import com.imagedge.camera.lut.SelectiveSpec
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
    /**
     * 裁剪比例预设：**界面选择态**，不是编辑动作——`EditStep` 里没有「比例」这一种步骤。
     * 选预设改的是裁剪框（[setCropAspect] 走 [croppedRecipe]），而框在配方里，所以它随撤销
     * 一起回去（用例见 PhotoEditRecipeStateTest 的「a locked ratio refits…」与「undoing a rotate…」）。
     */
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
    val exportConfig: ExportConfig = ExportConfig(),
    /**
     * 用户要不要 HDR 导出。
     *
     * 界面上的开关在不可用时是灰的（并显示原因），所以这里为 true 一定意味着**真能做**；
     * 导出时仍会再判一次——两道判的是同一份规则 [HdrExport.availability]。
     */
    val hdr: Boolean = false,
    /**
     * 源照片本身是否带增益图（`Bitmap.hasGainmap()`）。
     *
     * v1 的 HDR **只透传、不伪造**：源照片没有 HDR 数据就没有可带的，
     * 从 SDR 反推出来的高光是用户照片里本来没有的东西。
     */
    val sourceHasGainMap: Boolean = false,
    /**
     * 「显示作用范围」：把区间权重画成灰度图。
     *
     * **不是配方的一部分**——它是一个查看手段，存进配方会让导出的成品变成一张灰度图。
     * 也因此它不占历史的一格：开关它不该能被撤销。
     */
    val showKeyMask: Boolean = false,
    /**
     * 镜头校正（畸变 + 横向色差）。
     *
     * **刻意不进 [recipe]**：镜头校正是**镜头**的属性，不是用户在这张照片上做的一步编辑。
     * 进了配方它就会跟着预设一起走、跟着撤销一起滚——而它真正的依据是镜头本身，
     * 那一版还不带任何镜头数据库（见 LensCorrection 顶上关于 k1 的说明）。
     *
     * 所以它和 [showKeyMask] 一样只是 ViewModel 状态：影响预览与导出，不进历史、不进预设。
     *
     * 代价是**它进不了历史**：撤销与重做只在配方之间来回，所以「重置」清掉它之后，
     * 撤销那一步重置会带回配方、**带不回镜头校正**——要恢复只能重拖两条滑条。
     * [hasEdits] 与 [resetEdits] 都算上了它，所以「有没有改动」和「一键回原图」至少是真的。
     */
    val lens: LensCorrectionParams = LensCorrectionParams(),
) {
    val hasImage: Boolean get() = original != null

    // 每个 state 实例只算一次：fields() 是纯映射，同一份配方必然给出同一组值，
    // 而这几个名字在同一个实例上会被反复读（滑条每动一下就 copy 出一个新实例，见各 setter）。
    // 写成 `get()` 就是每次访问重筛一遍步骤列表——量不大，但没有理由白做。
    // 不用 by lazy：滑条每动一下就 copy() 出一个新实例，而 lazy 的委托对象本身
    // 也要分配、每次读还要过一次同步。实例一次性使用、构造时算一次就够。
    private val derived = recipe.fields()
    /** 归一化裁剪框（相对「拉直+旋转+翻转」之后的画面）：配方里那条 Crop 步骤，没有就是全图 */
    val crop: NormRect get() = derived.crop
    val selectedKey: String get() = derived.selectedKey
    val strength: Int get() = derived.strength
    val adjust: ColorAdjust get() = derived.adjust
    val quarterTurns: Int get() = derived.quarterTurns
    val flipHorizontal: Boolean get() = derived.flipHorizontal
    val flipVertical: Boolean get() = derived.flipVertical
    val straighten: Float get() = derived.straighten
    val hasGeometryEdits: Boolean get() = derived.hasGeometryEdits
    val selective: SelectiveAdjust get() = derived.selective
    val selectiveKey: RangeKey? get() = derived.selectiveKey

    /**
     * 有没有「还没存盘的改动」；**配方那半边**判定只在 [EditRecipeFields.hasEdits] 一处。
     *
     * 镜头校正在这里 OR 进来。它是唯一一件「影响导出成品、却不进配方」的编辑，
     * 不算进去的话，只调了镜头校正的会话里「重置」是灰的、「未存盘」标记也不亮，
     * 而导出的照片确实变形了——界面说没改，成品说改了。
     */
    val hasEdits: Boolean get() = derived.hasEdits || !lens.isIdentity

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
    val selective: SelectiveAdjust,
    val selectiveKey: RangeKey?,
) {
    val hasGeometryEdits: Boolean
        get() = quarterTurns % 4 != 0 || flipHorizontal || flipVertical ||
            kotlin.math.abs(straighten) > 0.05f || !crop.isFull

    /** 有没有「还没存盘的改动」——强度单独改不算（与 alpha08 的 hasEdits 同语义） */
    val hasEdits: Boolean
        get() = selectedKey != FILTER_NONE || !adjust.isIdentity || hasGeometryEdits || !selective.isIdentity
}

/**
 * 折叠成界面字段：八份值全部来自配方这一个来源。
 *
 * 裁剪框也在配方里（`EditStep.Crop` 那一步）。它曾是一份**独立于配方**的 live 值，于是
 * 「旋转 + 撤销」之后画面回到未旋转、框却还停在旋转后的坐标系里——用户当初框住的那块内容被无声
 * 换成另一块。（屏幕上那一个框与导出的那一个框从来都是同一个值，坏的是框与它依附的几何脱了钩；
 * 别把这条写成「看到的不是导出的」，那个缺陷从来不存在。）
 * `geometryToRender` 与 PhotoEditRecipeStateTest 的「undoing a rotate…」一起钉这件事。
 * 「拖动裁剪框不许一帧一条历史」这件事现在靠**写入侧不提交**、手势结束时才提交来做
 * （见 [PhotoEditViewModel.setCropRect] 与 [PhotoEditViewModel.commitEdit]），不是靠把框放在配方外。
 */
fun EditRecipe.fields(): EditRecipeFields {
    val rotate = steps.filterIsInstance<EditStep.Rotate>().firstOrNull()
    return EditRecipeFields(
        selectedKey = lutKeyOrDefault(FILTER_NONE),
        strength = strengthOrDefault(80),
        adjust = colorAdjust,
        crop = steps.filterIsInstance<EditStep.Crop>().firstOrNull()?.rect ?: NormRect.FULL,
        quarterTurns = rotate?.let { (((it.degrees / 90f).toInt() % 4) + 4) % 4 } ?: 0,
        flipHorizontal = steps.any { it is EditStep.Flip && it.horizontal },
        flipVertical = steps.any { it is EditStep.Flip && !it.horizontal },
        straighten = steps.filterIsInstance<EditStep.Straighten>().firstOrNull()?.degrees ?: 0f,
        selective = steps.filterIsInstance<EditStep.Selective>().firstOrNull()?.adjust ?: SelectiveAdjust(),
        selectiveKey = steps.filterIsInstance<EditStep.Selective>().firstOrNull()?.key,
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

/** 画面在给定配方下的宽高比：转了奇数格就宽高一换（归一后 1 与 3 是竖幅那一侧） */
internal fun aspectAfterGeometry(baseImageAspect: Float, quarterTurns: Int): Float =
    if (((quarterTurns % 4) + 4) % 4 % 2 == 1) 1f / baseImageAspect else baseImageAspect

/**
 * 裁剪框写进配方的那**一份** Crop 槽位：同身份就地替换，第二次拖动不会叠出第二个框。
 *
 * 全图时删掉那一步、而不是存一条 `Crop(FULL)`：配方里不留「等效于没裁」的占位，于是
 * 「没有 Crop 步骤」与「框是全图」变成同一件事——渲染侧就靠这一件事省掉一次全图裁剪
 * （见 [PhotoEditViewModel] 里 `if (crop.isFull)` 那条短路）。
 */
internal fun croppedRecipe(recipe: EditRecipe, rect: NormRect): EditRecipe {
    val sanitized = rect.sanitized()
    return if (sanitized.isFull) recipe.without<EditStep.Crop>()
    else recipe.with(EditStep.Crop(sanitized))
}

/**
 * 转一次 90°：配方里那份 rotate 槽位累加，**裁剪框同时换到新的坐标系**，两个值写进同一份配方。
 *
 * 成对写入是「撤销这一格能把框一起带回来」的唯一办法——框曾是 state 上另一份 live 值，
 * 撤销搬得动配方、搬不动它，于是旋转之后再撤销：画面回到未旋转，框却留在旋转后的坐标系里，
 * 导出的就是另一块区域（用例见 PhotoEditRecipeStateTest 的「undoing a rotate…」）。
 * 锁了比例预设时不旋转框，而是按旋转后的画面重新贴合该比例：框是预设算出来的，
 * 画面宽高一换，继续旋转旧框只会得到一个不再贴合预设的矩形。
 */
internal fun rotatedGeometry(
    recipe: EditRecipe,
    step: Int,
    aspectRatio: Float?,
    baseImageAspect: Float,
): EditRecipe {
    val turned = rotatedRecipe(recipe, step)
    val rect = if (aspectRatio == null) {
        Geometry.rotate90(recipe.fields().crop, step)
    } else {
        Geometry.maxRectForAspect(
            aspectAfterGeometry(baseImageAspect, turned.fields().quarterTurns),
            aspectRatio
        )
    }
    return croppedRecipe(turned, rect)
}

/**
 * 开关一个方向的翻转，裁剪框跟着镜像。
 *
 * 开与关都要变换：翻转是自身的逆运算，关掉之后画面回到镜像前，框若原地不动就压在
 * 被镜像掉的那一块内容上——配对必须对称，否则又变成两份状态互相追。
 */
internal fun flippedGeometry(recipe: EditRecipe, horizontal: Boolean): EditRecipe {
    val rect = if (horizontal) Geometry.flipHorizontal(recipe.fields().crop)
    else Geometry.flipVertical(recipe.fields().crop)
    return croppedRecipe(toggledFlip(recipe, horizontal), rect)
}

/**
 * 一次几何改动的历史配对：改动前那一格先记进历史，改动后那一格随后记下。
 *
 * 少了前一次提交，用户改完第一笔几何就退不回「刚载入」；少了后一次，重做没有落点。
 * 裁剪框就在配方里，所以撤销这一格时画面与框一起回去——这两样从此不可能脱节。
 */
internal fun committedTransform(
    history: HistoryList<EditRecipe>,
    recipe: EditRecipe,
    next: EditRecipe,
): HistoryList<EditRecipe> = history.record(recipe).record(next)

/**
 * 交给 `ImagePipeline.renderGeometry` 的那份几何：全部几何步骤（含裁剪框），不含调色与滤镜。
 *
 * 之所以要有这个名字：导出要读的就是它，而**预览不走这条整链**——预览先把不含裁剪的几何
 * 跑一遍当裁剪页底图，再从那张结果上裁（见 `applyCurrentFilter`），免得同一段几何被栅格化两遍。
 * 两条路的框都出自配方里同一份 `EditStep.Crop`（预览用的 `snapshot.crop` 是它的派生字段），
 * 所以「预览与导出各拿一个框」那种分家仍然不可能；被钉住的是这件事，
 * 用例是 PhotoEditRecipeStateTest 的「the geometry handed to the renderer…」。
 */
internal fun geometryToRender(recipe: EditRecipe): List<EditStep> = recipe.allSteps

/**
 * 在途渲染的**闸**比较的那一份配方：整份配方去掉裁剪框。
 *
 * 闸的本职是「按旧参数算出来的结果不许进 state」，但裁剪框是唯一一个改了不必重算的输入：
 * 裁剪页显示的是 `cropBase`（`recipe.geometryOnly`，刻意不含裁剪），拖框对它零影响，
 * 而拖框只可能发生在裁剪页上（写它的三处都在裁剪页：[setCropRect] 的手势、[setCropAspect]
 * 与 [resetCrop] 的按钮，外加 [setTab] 进裁剪页时那次按 chip 的重新贴合）。
 * 于是拖框不许再把在途渲染判废——拖框不发起渲染，
 * 被丢弃的渲染就没有人接替，`processing` 只在采纳与异常两条路径里复位，卡住的转圈正好长在这上面。
 *
 * 换到的代价是「带框那一张」（`filtered` / `compareBase` / `histogram`）可能短暂落后于配方，
 * 已核对这三样在裁剪页都不显示：预览取的是 `cropBase`（PhotoEditScreen 的 `PreviewArea`）、
 * 长按对比在裁剪页被禁用、直方图属调色页。补上的路径也都带渲染：切分区经 `setTab`、
 * 撤销与重做各自 `applyCurrentFilter`、导出直读配方而非预览位图。
 */
internal fun renderInputsOf(recipe: EditRecipe): EditRecipe = recipe.without<EditStep.Crop>()

/**
 * 这一份配方要的局部调整。**预览与导出读的是同一个函数**。
 *
 * 两条渲染路径各读一次配方、各自决定要不要往下传，是「预览与成品分家」最省事的写法：
 * 局部调整只出现在预览里，导出的成品原封不动，而两边都不报错。放进这个纯函数是因为
 * 它在 JVM 上可测——接线本身仍测不到（ViewModel 起不来），那条边界记在
 * PhotoEditRecipeStateTest 的类注释里。
 */
internal fun EditRecipe.selectiveSpec(): SelectiveSpec? =
    steps.filterIsInstance<EditStep.Selective>().firstOrNull()?.let { SelectiveSpec(it.key, it.adjust) }

/** 滑条的刻度上限：0..200 对应 0.00..2.00（[AppSlider] 只收整数，用它把浮点值搬上去） */
internal const val SELECTIVE_STEPS = 200

/**
 * 局部区间的默认羽化。**界面的起手值与 [setSelectiveAdjust] 的兜底用同一个数**——
 * 两处各写一个 0.1 的结果就是「先动滑条再切维度」与「先切维度再动滑条」得到两种边界。
 */
internal const val DEFAULT_FEATHER = 0.1f
private const val SELECTIVE_STEP_SCALE = 100f

internal fun selectiveStepToFloat(step: Int): Float = step / SELECTIVE_STEP_SCALE
internal fun selectiveFloatToStep(value: Float): Int =
    (value * SELECTIVE_STEP_SCALE).toInt().coerceIn(0, SELECTIVE_STEPS)

/**
 * 把滑条上的四个值收敛成一个**合法**的区间键。
 *
 * 它存在的理由是 [RangeKey.of] 会抛：羽化低于下限、`from > to`、上拐点越过该轴的容量，
 * 这三样用户用滑条随手就能拖出来（两条区间滑条交叉、或者在亮部把羽化拉满）。
 * 一个「滑到某个位置就崩」的编辑器比一个会自己收敛的差得多。
 *
 * 收敛之后保证：[from, to] 非空（`to > from`）、羽化不小于下限、上拐点不越界。
 * 用 `coerceIn` 时**必须先保证 min ≤ max**——Kotlin 的 `coerceIn(min, max)` 在 min > max 时
 * 抛 IllegalArgumentException，也就是这个函数自己会崩在它本该防的那种地方。
 */
internal fun rangeKeyOf(
    axis: KeyAxis,
    from: Float,
    to: Float,
    feather: Float,
    inverted: Boolean,
): RangeKey {
    val cap = if (axis == KeyAxis.HUE) 2f else 1f
    val f = feather.coerceAtLeast(RangeKey.MIN_FEATHER)
    val top = cap - f
    val safeTo = to.coerceIn(f, top.coerceAtLeast(f))
    val safeFrom = from.coerceIn(0f, (safeTo - f).coerceAtLeast(0f))
    return RangeKey.of(axis, safeFrom, safeTo, f, inverted)
}

/**
 * 换轴时给的一段起手区间。**每条轴各给各的**：把亮度那条原样搬到色相上，
 * 出来的是一个合法但几乎肯定不是用户想要的「只键黄色」。
 */
internal fun defaultRangeOf(axis: KeyAxis): Pair<Float, Float> = when (axis) {
    // 亮部偏中调：最常被局部调整的是天空与墙面
    KeyAxis.LUMA -> 0.35f to 0.75f
    // 色相跨过红端接缝：这是「只键红色」唯一能命中的写法（终点 > 1）
    KeyAxis.HUE -> 0.90f to 1.15f
    KeyAxis.SATURATION -> 0.30f to 0.80f
}

/**
 * 把一份预设套到当前配方上：留下这张照片自己的几何，**换掉颜色、局部与滤镜**。
 *
 * 局部调整也一起换，是一条裁定而不是「rank > 0 顺手带上的」：存了局部调整的预设套到别的照片上
 * 却只还原全局调色，用户没有任何办法察觉少了什么（PhotoEditRecipeStateTest 有对应的钉）。
 * 几何保留、颜色与滤镜同类替换而不是叠加这两条不变量仍然成立。
 *
 * 抽成纯函数是因为这里守着计划点名的两条不变量——**几何保留**、**同类替换而不是叠加**——
 * 而 `applyPreset` 本体要 ViewModel 实例，JVM 里起不来（同 [carriedColour] 的理由）。
 * 线格式带的是整份配方（含几何四步），所以这里按 rank 滤掉几何；走 [EditRecipe.with]
 * 而不是直接拼列表，是为了让「同一身份至多一步」仍由配方自己保证——手拼会叠出两条 Color，
 * 读的时候只看得见后一条，前一条白留在配方里。
 */
internal fun presetAppliedTo(current: EditRecipe, preset: EditRecipe): EditRecipe =
    preset.steps.filter { it.rank > 0 }
        .fold(EditRecipe(current.steps.filter { it.rank == 0 })) { acc, step -> acc.with(step) }

/**
 * 这份预设要的滤镜，在 [knownKeys] 里找不到吗？
 *
 * 抽成纯函数是因为这条规则本身终于能被测到：留在 ViewModel 里时它跟 JVM 起不来的构造绑在一起
 * （这条边界记在 PhotoEditRecipeStateTest 的类注释里，不在本文件）。
 * 说清楚买到的是什么——**测到的是规则，不是接线**：`applyPreset` 里那一句调用仍然没有测试能守
 * （ViewModel 在 JVM 里实例化不了，本文件头部记着这条已知边界，与滑条松手提交同一类）。
 *
 * 为什么要有这条规则：`applyCurrentFilter` 解析 key 用的域正是滤镜表的 key 集合，找不到就
 * `?: return` 静默什么都不做——所以「这里判过、那里还能落空」不该存在。
 *
 * 没有滤镜步骤（或 key 是「原图」占位）返回 false：那种预设本来就不需要任何资产。
 */
internal fun presetLookMissing(preset: EditRecipe, knownKeys: Set<String>): Boolean {
    val key = preset.lut?.key ?: return false
    return key !in knownKeys
}

/**
 * 翻转开关的写入侧：当前开着就删掉**那一个方向**，关着就占住那一份槽位。
 *
 * 关掉时用的是 `EditRecipe.without(step)`（按身份删），不能用 reified 的
 * `without<EditStep.Flip>()`（按类型删）——后者会把另一个方向一起清掉，而水平与垂直是两枚
 * 独立的 chip、可以同时开着（模拟器 debug 包上实跑过：两道都开，关掉水平，垂直仍然亮着；
 * 真机 + release 那一遍还没走，见 CHANGELOG 的未验证清单）。
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

/**
 * 「一键重置」写进 state 的那一份。
 *
 * 抽成纯函数是为了能在 JVM 里钉住它：镜头校正那一格是后加的，而它**不进配方**——
 * 只对着配方断言的重置测试看不见它，于是「重置之后预览仍然变形、而按钮已经把界面标回
 * 没改动」这种不一致可以照绿。`:app` 的测试依赖只有 junit4，起不来 ViewModel，
 * 所以纯算术与纯构造都得走这个口子（与 rotatedRecipe / committedTransform 同一个理由）。
 */
internal fun resetStateOf(state: PhotoEditState): PhotoEditState = state.copy(
    recipe = EditRecipe.EMPTY,
    cropAspect = CropAspect.FREE,
    // 镜头校正也要清：它影响导出成品却不在配方里
    lens = LensCorrectionParams(),
    // 走 record 而不是直接换配方：重置自己就该是一格可撤销的历史
    history = state.history.record(EditRecipe.EMPTY),
    message = null,
)

@HiltViewModel
class PhotoEditViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val processor: LutProcessor,
    private val userLutStore: UserLutStore,
    private val presetStore: EditRecipePresetStore,
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
     * 已存预设的名字列表（`filesDir/edit_presets`，见 `EditRecipePresetStore`）。
     *
     * 与 [filters] 同一类待遇：它来自磁盘，所以只在 [refreshPresets] 里于 Dispatchers.IO
     * 上填充，界面上读的是这一份快照——组合路径上不碰文件系统。
     */
    private val _presets = MutableStateFlow<List<String>>(emptyList())
    val presets: StateFlow<List<String>> = _presets.asStateFlow()

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
                val sourceHasGainMap = decoded!!.hasGainmapSafely()
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
                        // HDR 只在「换一张也还做得成」时带着走：增益图是**每张照片各一份**的，
                        // 上一张能做不代表这一张能做。带着走就是「开关亮着、导出被拒」
                        hdr = it.hdr && sourceHasGainMap,
                        sourceHasGainMap = sourceHasGainMap,
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

    /**
     * `hasGainmap()` / `getGainmap()` / `setGainmap()` 都是 API 34 才有的方法。
     * 低版本上直接调不是返回错值，是 `NoSuchMethodError`——所以这三处都必须先过版本闸。
     */
    /**
     * 镜头校正：**预览与导出共用的唯一入口**。
     *
     * 走位图前处理而不是塞进 `:lut` 那一趟，是因为 CPU 双线性与 GPU 硬件双线性对不到
     * 1 LSB——塞进去会直接打破本仓库最贵的那条同值不变量，而打破它的表现是
     * 「同一张照片在不同机器上边缘差半个像素」，极难察觉（理由见 image 模块的
     * LensResample.kt 顶上那段「为什么它不在 :lut 那一趟里」）。
     *
     * 零系数时 [correctLensDistortion] **原样返回入参**，所以这条在没开校正时零成本。
     * 非零系数时它返回新建的位图且**不改**入参，`original` 不会被踩（踩了就成二次校正了）。
     *
     * 下游必须继续满足两条：**不原地写** processSource（恒等时它就是 `previewSource`，
     * 被踩就等于踩了缓存的预览源），以及**及时回收**它（校正产生的位图生命周期只归这一次渲染）。
     */
    private fun applyLensCorrection(source: Bitmap, params: LensCorrectionParams): Bitmap =
        correctLensDistortion(source, params)

    private fun Bitmap.hasGainmapSafely(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasGainmap()

    private fun Bitmap.gainmapOrNull(): Gainmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) gainmap else null

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun Bitmap.attachGainmap(map: Gainmap) {
        gainmap = map
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

    /**
     * HDR 导出开关。界面上的开关在不可用时是灰的，正常走不到这里的拒绝分支；
     * 留着它是因为「能带 HDR 的格式」这一位会随格式选择而变——用户可以先把 HDR 打开、
     * 再把格式改成 PNG，那时这一位就失效了，而这里正是那条回执该出的地方。
     */
    fun setHdr(value: Boolean) {
        if (value) {
            val s = _state.value
            val unavailable = HdrExport.availability(
                Build.VERSION.SDK_INT, s.exportConfig.format, s.sourceHasGainMap
            )
            if (unavailable != null) {
                snackbarController.show(unavailable.message)
                return
            }
        }
        _state.update { it.copy(hdr = value) }
    }

    /** 局部区间 / 反选 / 模式是离散点击：前后各记一格（与翻转、点比例同一条规矩） */
    fun setSelectiveKey(key: RangeKey) {
        _state.update { s ->
            val next = s.recipe.with(EditStep.Selective(key, s.selective))
            s.copy(recipe = next, history = committedTransform(s.history, s.recipe, next))
        }
        applyCurrentFilter()
    }

    /**
     * 局部三轴：防抖重渲染，松手由界面调 [commitEdit]。
     *
     * 没有 Selective 步骤时先落一个默认键，而不是把这三个值丢掉：界面初始就选中
     * 「亮度」，所以正常路径上不存在这种状态；这个兜底只防直接调 API 的顺序错。
     */
    fun setSelectiveAdjust(adjust: SelectiveAdjust) {
        _state.update { s ->
            val key = s.selectiveKey ?: RangeKey.of(KeyAxis.LUMA, 0.4f, 0.6f, feather = DEFAULT_FEATHER)
            s.copy(recipe = s.recipe.with(EditStep.Selective(key, adjust)))
        }
        scheduleApply()
    }

    /**
     * 改镜头校正的参数。防抖重渲染——拖 k1 滑条不该每帧都重采样一整张图。
     *
     * **不进配方、不进历史**（理由见 [PhotoEditState.lens]）：它不是用户做的一步编辑。
     */
    fun setLens(params: LensCorrectionParams) {
        _state.update { it.copy(lens = params) }
        scheduleApply()
    }

    /** 关掉局部调整：把这一步从配方里摘掉。
     *
     * 需要它是因为**进得去出不来**：打开局部调整后把三轴拖回 0，那一步仍然在配方里
     * （对照片没有任何影响，所以 `hasEdits` 为假、编辑器那个「重置」是灰的），
     * 而拖滑条也摘不掉它——已经是 0 了。没有这个出口，这一格就永远留在配方里。
     */
    fun clearSelective() {
        _state.update { s ->
            val next = s.recipe.without<EditStep.Selective>()
            s.copy(recipe = next, history = committedTransform(s.history, s.recipe, next))
        }
        applyCurrentFilter()
    }

    /**
     * 「显示作用范围」直连 [applyCurrentFilter]，**不走防抖**：
     * 那是 200ms 的延迟，而开关该是即时的——用户按下去等半秒才变，读起来就是「没生效」。
     */
    fun setShowKeyMask(show: Boolean) {
        _state.update { it.copy(showKeyMask = show) }
        applyCurrentFilter()
    }

    /** 基础调色变化（增益/分区/对比度/饱和度任一，防抖同强度） */
    fun setAdjust(adjust: ColorAdjust) {
        _state.update { it.copy(recipe = it.recipe.with(EditStep.Color(adjust)), message = null) }
        scheduleApply()
    }

    /** 一键重置：回到原图（清掉滤镜、强度、全部调色、几何与镜头校正，裁剪框跟着配方一起清空） */
    fun resetEdits() {
        applyJob?.cancel()
        _state.update { resetStateOf(it) }
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

    // ── 编辑预设（存为 / 套用 / 删除）──────────────────────────────
    // 列表本身（_presets / presets）与 filters 并排声明在类体开头，这里只放动作

    /** 列目录是磁盘 IO，不许发生在组合路径上（LaunchedEffect 里直接调就是主线程读盘） */
    fun refreshPresets() {
        viewModelScope.launch(Dispatchers.IO) { _presets.value = presetStore.list() }
    }

    /**
     * 存的是「调色 + 滤镜」，不含几何：几何属于这张照片的构图，换一张就没意义。
     *
     * 回执走 [SnackbarController] 而不是 `state.message`：预设区排在调色分区底部，
     * 而 message 渲染在预览上方——用户在按「存为预设」的时候根本看不到那一屏，
     * 一个被拒的名字就表现为按钮没反应。
     */
    /**
     * 这个名字是否已被占用——**问磁盘，不比界面上的列表**。
     *
     * 界面那份 `presets` 既晚一步（异步列目录，进页面才填、每次存完再刷），又是**归一化之后**
     * 的名字；用户在输入框里打的是归一化之前的原文。拿原文去比列表，「我的.v2」与已经存在的
     * 「我的v2」就不相等 → 走「不撞名」分支直接覆盖，正是覆盖确认要防的那件事。
     * 存储层按落盘身份判等，一次 `isFile` 换回正确的撞名判断。
     */
    fun presetNameTaken(name: String): Boolean = presetStore.exists(name)

    fun savePreset(name: String) {
        val colourOnly = EditRecipe(_state.value.recipe.steps.filter { it.rank > 0 })
        // 写侧也要有读侧那道上限：预设的 lut key 来自磁盘文件名，一个超过 MAX_KEY_CHARS 的
        // .cube 文件名会「存得进去、每次套用都被解码拒掉」——正是本层反复要防的
        // 「存在了却永远用不了」，只是这次换了指针够不到的地方
        val lutKey = colourOnly.lut?.key
        if (lutKey != null && lutKey.length > EditRecipeDocument.MAX_KEY_CHARS) {
            snackbarController.show("滤镜名超过 ${EditRecipeDocument.MAX_KEY_CHARS} 字符，存不进预设")
            return
        }
        presetStore.save(name, colourOnly)
            .onSuccess {
                refreshPresets()
                snackbarController.show("已存为预设「${sanitizePresetName(name)}」")
            }
            .onFailure { error ->
                snackbarController.show("预设保存失败：${error.message ?: error.javaClass.simpleName}")
            }
    }

    /**
     * 套用预设：保留当前照片的几何步骤，只换颜色与滤镜（合并规则见 [presetAppliedTo]）。
     *
     * 走 [committedTransform] 而不是「前后各调一次 commitEdit」：那两次提交与 rotate / 翻转 /
     * 点比例是同一件事（一次离散改动、前后各一格），用同一个函数才不会出现第二种历史形状。
     * 中间再读一次 `_state.value` 更是白送一个「改过配方却没进历史」的窗口。
     *
     * **先验滤镜在不在**（[presetLookMissing]）：预设里的 LUT key 来自磁盘，而用户可能在
     * 设置 → LUT 管理 里删掉或改名那个 .cube。`applyCurrentFilter` 找不到 key 时是 `?: return`——
     * 于是配方与历史都推进了、预览还是旧的那张、`hasEdits` 翻成「有改动」，而导出读的是配方：
     * **预览与成品分家**。这条路径上唯一正确的做法是先拒掉。
     */
    fun applyPreset(name: String) {
        val result = presetStore.read(name)
        val preset = result.recipe
        if (preset == null) {
            snackbarController.show(result.failure ?: "预设读取失败")
            return
        }
        if (presetLookMissing(preset, _filters.value.map { it.key }.toSet())) {
            // 话要说得准：滤镜表是 init 里异步装配的，刚进页面那一瞬按下去，
            // key 也可能只是「还没装完」而不是「不在了」。分不清就不断言原因。
            snackbarController.show("这个预设用的滤镜当前不可用，套用取消")
            return
        }
        _state.update { s ->
            val next = presetAppliedTo(s.recipe, preset)
            s.copy(
                recipe = next,
                history = committedTransform(s.history, s.recipe, next),
                message = null
            )
        }
        applyCurrentFilter()
    }

    /**
     * 删除预设。**不动配方**：预设是库，不是这张照片上的一步编辑，所以它不进历史。
     *
     * 先 `exists` 再删，是为了让「不存在」与「删不掉」是两条不同的回执——`delete` 只回
     * Boolean，两者会塌成一句。多一次 stat 换一个看得懂的结果，值得。
     * 两条 stat 之间文件可能消失（只有并发的另一次删除会做到），所以失败那条**只说删不掉**，
     * 不额外断言「文件还在」——那一刻我们并不知道。
     */
    fun deletePreset(name: String) {
        val safe = sanitizePresetName(name)
        val outcome = when {
            !presetStore.exists(name) -> "预设「$safe」不存在"
            presetStore.delete(name) -> "已删除预设「$safe」"
            else -> "预设「$safe」删不掉"
        }
        snackbarController.show(outcome)
        refreshPresets()
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
        val option = _filters.value.firstOrNull { it.key == snapshot.selectedKey }
            ?: return
        val lut = option.lut ?: lutCache[option.key]
        val adjust = snapshot.adjust
        val strength = snapshot.strength
        val crop = snapshot.crop
        // 裁剪模式的底图：几何但**不**裁剪，框要画在它上面
        val geoSteps = snapshot.recipe.geometryOnly
        // 成品那一份几何不在这里整链重跑：几何先跑一遍（下面那张 geometryOnly），裁剪从它身上裁。
        // 拼出来的那条 Crop 出自 snapshot.crop，而它是配方里那**同一份** EditStep.Crop 的派生值，
        // 所以「预览与导出各拿一个框」这个分家来源仍然不存在
        // 本次渲染是按这份输入起的头；防抖窗口内输入又被改了一次时，旧参数的结果不许进 state。
        // 比的是 renderInputsOf（不含裁剪框），理由与代价见它的 KDoc
        val requestInputs = renderInputsOf(snapshot.recipe)
        // 换图判废比的是**进闸那一刻的 previewSource 快照**，不是 processSource：
        // 镜头校正非恒等时 processSource 是 applyLensCorrection 新建的位图，拿它比会让
        // 每一趟带校正的渲染都在闸前被丢掉——预览永不更新，而 [PhotoEditState.processing]
        // 只在采纳与异常两条路径里复位，于是转圈永久卡住。
        val expectedPreview = previewSource
        val request = renderGeneration.incrementAndGet()
        applyJob?.cancel()
        _state.update { it.copy(processing = true) }
        applyJob = viewModelScope.launch(Dispatchers.Default) {
            var processSource: Bitmap? = null
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
                        renderGeneration.get() != request || previewSource !== expectedPreview ||
                        requestInputs != renderInputsOf(_state.value.recipe)
                    ) return@withLock
                    // 0) 镜头校正。预览源优先：交互式处理在降采样副本上跑，比全分辨率快约 6 倍。
                    // 它**在几何之前**——先校正再裁剪，裁剪框才对得上最终画面；放在几何之后
                    // 会浪费四角，而且裁剪框指的是畸变图上的位置。
                    //
                    // 它放在闸**之后**、Dispatchers.Default **之上**：被判废的一趟不必为一次
                    // 全图重采样付出代价，而它抛出的异常也终于落进下面那个 catch，
                    // 不再是直接崩在主线程上。幂等性靠的是它不改动入参。
                    val corrected = applyLensCorrection(expectedPreview ?: original, snapshot.lens)
                    processSource = corrected
                    // 1) 几何：拉直 → 旋转 → 翻转（**不裁剪**，裁剪模式的底图要用它）
                    geometryOnly = if (geoSteps.isEmpty()) {
                        corrected
                    } else {
                        ImagePipeline(geoSteps).renderGeometry(corrected)
                    }
                    coroutineContext.ensureActive()
                    // 2) 裁剪：配方里没有 Crop 步骤就等于全图（写入侧见 croppedRecipe 的删除分支），
                    //    此时复用底图那一张，省一次全图裁剪与一张中间位图
                    cropped = if (crop.isFull) {
                        geometryOnly
                    } else {
                        // 改动前这里是拿 allSteps 从 processSource **再跑一遍**整条几何链：
                        // 输出逐像素相同，但同一段拉直/旋转/翻转被栅格化两次，
                        // 与本文件自己的口径（「为一次拖框跑整轮遍历是纯烧」）相冲
                        ImagePipeline(listOf(EditStep.Crop(crop))).renderGeometry(geometryOnly)
                    }
                    // 3) 颜色：调色 + LUT（一次像素遍历）
                    coloredCropped = applyPipelineTo(
                        cropped, lut, strength, adjust,
                        requestInputs.selectiveSpec(), snapshot.showKeyMask
                    )
                    coroutineContext.ensureActive()
                    // 裁剪模式的底图 = 几何 + 颜色（让用户带着最终观感去框选）
                    coloredFull = if (cropped === geometryOnly) {
                        coloredCropped
                    } else {
                        applyPipelineTo(
                            geometryOnly, lut, strength, adjust,
                            requestInputs.selectiveSpec(), snapshot.showKeyMask
                        )
                    }
                    coroutineContext.ensureActive()
                    if (sourceGeneration.get() != expectedGeneration ||
                        renderGeneration.get() != request || previewSource !== expectedPreview ||
                        requestInputs != renderInputsOf(_state.value.recipe)
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
                // `expectedPreview` 是排除项而不是 `processSource`：镜头校正恒等时
                // processSource **就是** 缓存的预览源，回收它等于回收 loadPicked 的东西；
                // 非恒等时它是我们这一趟造的，该回收。判据必须是进闸那一刻的同一个快照。
                recycleUnique(
                    listOfNotNull(geometryOnly, cropped, processSource)
                        .filter { it !== expectedPreview && it !== heldByState }
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
            // 已选比例预设时才重贴比例；自由比例下**不能**重置用户的裁剪框（alpha08 起就是这条规则）。
            //
            // 记一格，但只记**改动后**那一格（不是 rotate 那种前后成对）：贴合前的框本来就是
            // 上一次提交记进去的，再记一遍就等于凭空多出一格「与当前画面一模一样」的历史——
            // 用户点一次撤销看起来什么都没发生。而完全不记更糟：那是全类里唯一一处
            // 「改了配方却不进历史」的写入，history.current 与 state.recipe 一旦脱钩，
            // 下一次成对提交就会先记出一份陈旧的「之前」，同样是一格死步骤。
            // 同值时 HistoryList.record 自己的去重会把这一下变成空操作，所以自由比例下不产生任何格子。
            _state.update { s ->
                val ratio = s.cropAspect.ratio ?: return@update s
                val next = croppedRecipe(s.recipe, Geometry.maxRectForAspect(effectiveImageAspect(s), ratio))
                s.copy(recipe = next, history = s.history.record(next))
            }
        }
        // 统一重渲染：裁剪框/几何可能在别的分区被改过，预览与底图都要跟上
        applyCurrentFilter()
    }

    /**
     * 拖动裁剪框：写进配方里那一份 Crop 槽位，**不进历史**（一次拖动的提交在手势结束时，
     * 由界面调 [commitEdit]；理由与用例见 [croppedRecipe]），也**不发起渲染**。
     *
     * 不发起渲染是回到 alpha08 的行为，也是这一格本该有的代价：裁剪页显示的是不含裁剪的
     * `cropBase`，拖框对它没有任何影响，为一次拖框跑几何 + 两趟像素遍历是纯烧。
     * 配套的是闸不再把裁剪框当输入（见 [renderInputsOf]）——若闸仍因拖框作废在途渲染，
     * 而拖框自己又不发起渲染，`processing` 就没人复位。
     */
    fun setCropRect(rect: NormRect) {
        _state.update { it.copy(recipe = croppedRecipe(it.recipe, rect)) }
    }

    /**
     * 选择裁剪比例：把裁剪框收成该比例能占满画面的最大矩形，写进配方并提交一格。
     *
     * 框现在在配方里，所以点比例是一颗离散编辑（与旋转、翻转同类）：改动前后各记一格，
     * 撤销退得回上一个框。不这么做的后果不是「退不回」，而是这一格被卷进**下一次无关的提交**
     * ——用户点完 16:9 再去动曝光，撤销曝光会连框一起搬回去。
     * 与 [setCropRect] 同理不发起渲染。
     *
     * 没被一起记住的是那枚点亮着的比例 chip：`cropAspect` 是界面选择、不是编辑步骤，
     * 所以撤销会把框搬回去而 chip 仍停在 16:9；此时若切走再回裁剪页，[setTab] 会按 chip
     * 重新贴合（alpha08 起就是这条规则），把撤销回来的框覆盖掉。已知不对称，不是这次的改动。
     */
    fun setCropAspect(aspect: CropAspect) {
        _state.update { s ->
            // 画面比例与配方都从**同一个** s 里读：分两次读 _state.value，第二次可能读到别人写过的
            // state（渲染协程也在写），框就会按上一个画面的比例算——不报错，只是框偏了
            val forced = Geometry.maxRectForAspect(effectiveImageAspect(s), aspect.ratio)
            val next = croppedRecipe(s.recipe, forced)
            s.copy(
                cropAspect = aspect,
                recipe = next,
                history = committedTransform(s.history, s.recipe, next)
            )
        }
    }

    /** 顺时针/逆时针 90°；裁剪框跟着换到新坐标系，锁定比例时按新画面重新贴合该比例 */
    fun rotate(clockwise: Boolean) {
        val step = if (clockwise) 1 else -1
        val snapshot = _state.value
        val next = rotatedGeometry(
            recipe = snapshot.recipe,
            step = step,
            aspectRatio = snapshot.cropAspect.ratio,
            baseImageAspect = baseImageAspect(snapshot)
        )
        _state.update { s ->
            s.copy(
                recipe = next,
                history = committedTransform(s.history, s.recipe, next),
                comparing = false
            )
        }
        applyCurrentFilter()
    }

    /** 左右翻转：开→写进那一份 flip:true 槽位；关→只删 flip:true，另一个方向不动 */
    fun toggleFlipHorizontal() {
        val next = flippedGeometry(_state.value.recipe, horizontal = true)
        _state.update { s ->
            s.copy(recipe = next, history = committedTransform(s.history, s.recipe, next))
        }
        applyCurrentFilter()
    }

    /** 上下翻转：与左右翻转各自独立一份槽位，可同时开着 */
    fun toggleFlipVertical() {
        val next = flippedGeometry(_state.value.recipe, horizontal = false)
        _state.update { s ->
            s.copy(recipe = next, history = committedTransform(s.history, s.recipe, next))
        }
        applyCurrentFilter()
    }

    /**
     * 拉直角度（-45..45），防抖后重渲染；进历史由调用方在操作结束时调 commitEdit。
     *
     * 与旋转、翻转不同，这里**不搬裁剪框**，而且是刻意的。框归一化在「拉直之后、尚未裁剪」的画面上
     * （几何顺序：拉直 → 旋转 → 翻转 → 裁剪），所以转正时内容从固定的框下经过，用户看着画面正过来；
     * 把框按角度差搬一遍反而会把用户刚对准的那一格挪走，还得为转出画面的部分做 clamp，
     * 顺带改掉框的面积。这条不搬也不会造成「看到的不是导出的」——预览与成品同读一份配方。
     */
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

    /** 重置几何（保留调色与滤镜）；裁剪框在配方里，跟着整段几何一起清 */
    fun resetGeometry() {
        _state.update { s ->
            s.copy(
                // 只留 rank 非 0 的步骤（调色与滤镜），几何整段清空
                recipe = EditRecipe(s.recipe.steps.filter { it.rank != 0 }),
                cropAspect = CropAspect.FREE
            )
        }
        applyCurrentFilter()
        commitEdit()
    }

    /** 只重置裁剪（保留旋转/翻转/拉直与调色）；与 [setCropAspect] 同类，离散一格、前后各提交 */
    fun resetCrop() {
        _state.update { s ->
            val next = croppedRecipe(s.recipe, NormRect.FULL)
            s.copy(
                cropAspect = CropAspect.FREE,
                recipe = next,
                history = committedTransform(s.history, s.recipe, next)
            )
        }
    }

    /** 未经几何变换的画面宽高比（宽/高）：预览源优先，它本来就是原图的等比缩略 */
    private fun baseImageAspect(s: PhotoEditState): Float =
        (previewSource ?: s.original)?.let { it.width.toFloat() / it.height } ?: 4f / 3f

    /** 当前几何（旋转/拉直）之后画面的宽高比（宽/高） */
    private fun effectiveImageAspect(s: PhotoEditState): Float =
        aspectAfterGeometry(baseImageAspect(s), s.quarterTurns)

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
        selective: SelectiveSpec?,
        maskMode: Boolean,
    ): Bitmap {
        // GPU 直通路径：一次完成「调色 + LUT」，省掉 Bitmap→IntArray→RGBA→IntArray→Bitmap
        // 的两趟 CPU 拷贝（GPU 实现见 GpuLutProcessor；不支持时返回 null 走下面的 CPU 实现）
        if (processor.supportsBitmapPath) {
            val gpu = processor.applyToBitmap(
                src,
                lut?.data ?: LutProcessor.EMPTY_LUT,
                lut?.size ?: 0,
                strength,
                adjust,
                selective = selective,
                maskMode = maskMode,
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
        // 无 LUT 那一支**不能**再用 applyAdjustOnly：它没有 selective 参数，
        // 「没选滤镜」时局部调整会被整段丢掉，而且不报错
        val out = if (lut == null) {
            processor.apply(
                rgba, w, h, LutProcessor.EMPTY_LUT, 0, 100, adjust,
                selective = selective, maskMode = maskMode,
            )
        } else {
            processor.apply(
                rgba, w, h, lut.data, lut.size, strength, adjust,
                selective = selective, maskMode = maskMode,
            )
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
                        // 增益图必须在**解码之后、几何之前**取：applyRotation 若没把它带过来，
                        // 这里就是 null，导出按 HdrExport 的规则拒绝——失败即封闭，
                        // 不能「没有就算了」导出一张悄悄少了 HDR 的文件
                        val sourceGainMap = decoded.gainmapOrNull()
                        coroutineContext.ensureActive()
                        val option = _filters.value.firstOrNull { it.key == editSnapshot.selectedKey }
                        val lut = option?.lut ?: option?.key?.let { lutCache[it] }
                        val adjust = editSnapshot.adjust
                        val strength = editSnapshot.strength
                        // 几何（含裁剪框）在全分辨率上先做，再按条带调色。
                        // 这一步不许再另外拼一条 Crop：裁剪框就在配方里。这里曾是
                        // `geometryOnly + 现拼的 live 裁剪框`，而撤销只搬配方、搬不动那个 live 值，
                        // 框在配方里，撤销一次旋转会把「几何 + 框」一起搬回去，成品裁的才是用户
                        // 当初框住的那块内容（说明见 geometryToRender）
                        val steps = geometryToRender(editSnapshot.recipe)
                        // 与预览**同一个** helper、同一份参数：两条路径各写一遍的结果是
                        // 「预览里校正了、成品里没有」，而两边都不报错
                        val corrected = applyLensCorrection(decoded, editSnapshot.lens)
                        val transformed = ImagePipeline(steps).renderGeometry(corrected)
                        geometryApplied = transformed
                        coroutineContext.ensureActive()
                        val result = renderFullResolution(
                            transformed, lut, strength, adjust,
                            // 与预览读同一个函数（见 selectiveSpec）：两条路径各读一次配方，
                            // 就会出现「预览里有、成品里没有」而两边都不报错
                            editSnapshot.recipe.selectiveSpec(),
                        )
                        rendered = result
                        coroutineContext.ensureActive()

                        // 2) 交给 ExportManager 落盘（格式/质量/元数据策略都在那里）→ 提交相册
                        // 格式 / 质量 / 元数据策略交给 ExportManager 统一处理（与分享导出同一套），
                        // 文件名扩展名必须跟着格式走，否则相册里会出现一个后缀是 .jpg 的 WebP
                        val config = editSnapshot.exportConfig
                        // HDR：只把源照片自带的那份增益图挂到成品上。
                        // 判定与界面灰掉开关时用的是**同一份**规则，所以这里拒了，
                        // 界面上不可能是亮的——两处各判一次就会出现那种情况
                        if (editSnapshot.hdr) {
                            val unavailable = HdrExport.availability(
                                Build.VERSION.SDK_INT, config.format, sourceGainMap != null
                            )
                            if (unavailable != null) throw IllegalStateException(unavailable.message)
                            // 这一句版本判断看着多余——上面 availability() 里已经判过同一件事。
                            // 它是给 lint 看的：NewApi 认不出「我们自己的判断函数」，
                            // 只认写在调用点上的 SDK_INT 比较。删掉它 lint 会红，
                            // 而把 NewApi 压掉等于让低版本真跑到 setGainmap 上崩
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                result.attachGainmap(sourceGainMap!!)
                            }
                        }
                        val exported = ExportManager(context).exportRendered(
                            bitmap = result,
                            exifSource = uri,
                            // HDR 打开时不许用 ExifInterface 重写那个文件：
                            // 增益图在 MPF 段里，而重写会不会保住它没有依据可查（见 ExportManager）
                            skipMetadataRewrite = editSnapshot.hdr,
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
     * 实现已挪到 [ExportLimits]：编辑调节与边框水印是两条导出路径，但**同一条内存策略**，
     * 各自抄一份的结果就是同一个上限在两个文件里慢慢漂移。内存可注入的版本带 JVM 单测。
     */
    private fun exportMaxDim(): Int = ExportLimits.maxLongEdge()

    /**
     * 全分辨率处理：**按水平条带**跑，避免一次性分配整图的三块缓冲。
     * 条带高度 256 行时，6000px 宽的临时缓冲约 256×6000×(4+4+4) ≈ 18MB。
     */
    private suspend fun renderFullResolution(
        src: Bitmap,
        lut: CubeLut?,
        strength: Int,
        adjust: ColorAdjust,
        selective: SelectiveSpec?,
    ): Bitmap {
        // GPU 直通：大图正是 GPU 收益最大的场景（实现内部按条带渲染，避免一次性申请两张全尺寸纹理）
        if (processor.supportsBitmapPath) {
            val gpu = processor.applyToBitmap(
                src,
                lut?.data ?: LutProcessor.EMPTY_LUT,
                lut?.size ?: 0,
                strength,
                adjust,
                selective = selective,
                // 掩码**不进导出**：showKeyMask 是 state 字段、不在配方里。
                // 导出的是配方，不是屏幕上那张灰度图
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
                processor.apply(
                    rgba, w, rows, LutProcessor.EMPTY_LUT, 0, 100, adjust,
                    selective = selective,
                )
            } else {
                processor.apply(
                    rgba, w, rows, lut.data, lut.size, strength, adjust,
                    selective = selective,
                )
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
