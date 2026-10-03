package com.imagedge.camera.feature.edit.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.R
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.core.export.ExportLimits
import com.imagedge.camera.image.FrameGeometry
import com.imagedge.camera.motionphoto.MotionPhotoComposer
import com.imagedge.camera.motionphoto.MotionPhotoParser
import com.imagedge.camera.share.ExportConfig
import com.imagedge.camera.share.ExportFormat
import com.imagedge.camera.share.ExportManager
import com.imagedge.camera.share.ExifPolicy
import com.imagedge.camera.ui.feedback.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 边框水印 ViewModel。
 *
 * 流程：选照片 → EXIF 自动读取（型号/等效焦距/快门/ISO/光圈/拍摄时间，可逐项开关与改写）
 * → 选模板实时渲染预览 → 导出（普通照片画框落盘并保留 EXIF；实况图提取视频后与画框图重新合成）。
 *
 * ## 渲染要点（v2 重做）
 *
 * v1 的「效果差」集中在渲染层，v2 逐条修掉：
 * 1. **排版**：v1 把「品牌 + 型号 + 全部参数」挤在一行用空格分隔，参数一多就整体缩到 0.6 倍
 *    再省略号截断。v2 改为**两行信息层级**——第一行品牌 LOGO + 型号（Medium 字重、主色），
 *    第二行参数（`·` 分隔、次级灰），字号/颜色/基线各自独立。
 * 2. **字体**：v1 用系统 `Typeface.DEFAULT` 与 `MONOSPACE`（各机型字形不一、等宽下中文字距难看）。
 *    v2 统一用应用自带的 Inter（Regular/Medium），与应用整体视觉一致。
 * 3. **经典白边**：v1 的「经典白边」其实是「照片贴边 + 底部一条白栏」，没有白边。
 *    v2 是真正的**四边留白**（照片四周缩进），并可开圆角。
 * 4. **极简单行**：v1 把半透明黑条画在照片**下方**，白底上就是一条灰带。
 *    v2 改为照片**底部渐变遮罩**（透明 → 黑）后叠字，才是真正的极简叠字。
 * 5. **可编辑性**：新增拍摄时间与自定义文字（署名/地点/版权）字段，并可逐项显示/隐藏。
 *
 * 另修复两处导出缺陷：成品**保留原图 EXIF**（v1 全丢，相册排序与拍摄信息都没了）、
 * 落盘走 `IS_PENDING` + `DATE_TAKEN`（与传输链路一致：不留半成品、按拍摄时间排序）。
 */
@HiltViewModel
class ExifFrameViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val haptics: Haptics
) : ViewModel() {

    /** 内置模板（5 套，视觉差异明确） */
    enum class FrameTemplate(val label: String) {
        CLASSIC_WHITE("经典白边"),
        DARK_BAR("暗色底栏"),
        POLAROID("白框悬浮"),
        SIGNATURE("双行签名"),
        MINIMAL("极简叠字"),
    }

    /**
     * 单个可编辑字段（EXIF 预填 + 手动覆盖 + 是否显示）。
     *
     * @param baseline EXIF 刚读出来时的值。[ExifFrameState.hasEdits] 要靠它区分
     *   「用户改过」与「只是读到了默认值」——它必须跟着字段本身走，不能只存在
     *   ViewModel 的一个私有字段里，否则 state 单独被观察时判断不了可撤销性。
     */
    data class FrameField(
        val label: String,
        val value: String,
        val enabled: Boolean = true,
        val baseline: String = value,
    )

    data class ExifFrameState(
        val sourceUri: Uri? = null,
        /** 源是否为实况图（导出时需重组视频） */
        val isMotion: Boolean = false,
        val template: FrameTemplate = FrameTemplate.CLASSIC_WHITE,
        /** 渲染后的预览图（降采样，所见即所得） */
        val preview: Bitmap? = null,
        val rendering: Boolean = false,
        val fields: List<FrameField> = emptyList(),
        /** 自定义文字（署名 / 地点 / 版权） */
        val customText: String = "",
        /** 是否渲染品牌 LOGO */
        val keepLogo: Boolean = true,
        /** 照片圆角（经典白边 / 白框悬浮 / 暗色底栏下生效） */
        val rounded: Boolean = false,
        /** 格式 / 画质 / 元数据策略，与分享面板、编辑调节共用同一份 */
        val exportConfig: ExportConfig = ExportConfig(),
        val exporting: Boolean = false,
        val message: String? = null,
        val success: Boolean = false,
    ) {
        /**
         * 有没有可撤销的改动。编辑器骨架据此决定「重置」能不能点——
         * 一张刚选好的照片本来就在默认样式上，摆一个「重置」只会让人以为动了什么。
         *
         * **字段的 value 也要算进去**：EXIF 读错时用户会直接改内容（改完提示的模型名、
         * 补一条被压缩丢掉的拍摄时间），而 `resetStyle()` 本来就会把值还原。
         * 此前只判 enabled，于是"只改了内容没动开关"的用户看着「重置」是灰的，
         * 改了就回不来——这正是那个按钮存在的意义所在。
         */
        val hasEdits: Boolean
            get() = template != FrameTemplate.CLASSIC_WHITE ||
                customText.isNotBlank() || rounded || !keepLogo ||
                fields.any { !it.enabled } ||
                fields.any { it.value.trim() != it.baseline.trim() }
    }

    private val _state = MutableStateFlow(ExifFrameState())
    val state: StateFlow<ExifFrameState> = _state.asStateFlow()

    /**
     * 交互用的基准原图（1600px 长边降采样）。
     *
     * **它只服务预览，不参与导出。** 导出按 [ExportLimits] 重新解码到接近原分辨率——
     * 早先版本直接拿这张图落盘，于是 6000px 的照片导出成 1600px。同一个 bug 在
     * 编辑调节里已经修过（见 CHANGELOG「导出分辨率腰斩」），修法是逐个 feature 落的，
     * 漏了这里。版式按源图宽取比例（见 [FrameGeometry.proportionalAbove]），
     * 所以预览与全分辨率成品的版式是同构的。
     */
    private var sourceBitmap: Bitmap? = null
    /** EXIF 刚读出来时的字段快照：「重置」回到这里，而不是回到空白 */
    private var baselineFields: List<FrameField> = emptyList()
    private var sourceMotionVideo: File? = null
    /**
     * 本次编辑占用的磁盘目录，页面销毁时清掉。
     *
     * 登记的是 [MotionPhotoParser] 的**整个会话目录**而不是单个 videoFile：
     * 目录里还躺着源文件的一份完整拷贝（`source.motion`）与 gain map，
     * 那才是占空间的大头。约定与 LiveTriptychViewModel 一致：谁创建谁登记。
     *
     * 用 CopyOnWriteArrayList：登记发生在 `loadSource` 的 IO 线程，
     * 遍历发生在主线程的 `onCleared`，普通 ArrayList 会在这种交错下抛
     * ConcurrentModificationException——而那正是「返回上一页」的时刻。
     */
    private val tempFiles = CopyOnWriteArrayList<File>()
    /** [onCleared] 之后到达的解析结果无处可去，登记时直接就地删掉 */
    private val cleared = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 从 EXIF Make/Model 检测出的相机品牌（渲染商标图片/字标） */
    private var sourceBrand: BrandMark? = null
    /** assets 内品牌 PNG 的解码缓存（避免导出时重复解码） */
    private val pngCache = HashMap<String, Bitmap>()

    /** 应用内自带字体（Inter）：与整体视觉一致，且不受机型系统字体差异影响 */
    private val fontRegular: Typeface by lazy {
        ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT
    }
    private val fontMedium: Typeface by lazy {
        ResourcesCompat.getFont(context, R.font.inter_medium) ?: Typeface.DEFAULT_BOLD
    }

    /**
     * 品牌标识：优先 assets/brand_logos 下的 PNG（用户自维护，自带品牌色 / 留白边距），
     * 缺失时回退特征文字字标。
     * - badge = true：原图就是带纯色底的官方徽章（gopro 黑底 / realme 黄底），
     *   深色底上不再做 SRC_IN 白色染色（否则整块底色被染白）。
     */
    private data class BrandMark(
        val text: String,
        val color: Int,
        val assetPng: String? = null,
        val spacing: Float = 0.05f,
        val badge: Boolean = false,
    )

    /** 单套模板的配色 */
    private data class Palette(
        val bg: Int,
        val fg: Int,
        val muted: Int,
        val divider: Int,
    )

    /**
     * 从 EXIF Make/Model 检测品牌。注意顺序：REDMI 判定必须在 XIAOMI 之前
     * （红米机型的 Make 上报为 "Xiaomi"、Model 上报为 "REDMI ..."）。
     * 全部走 assets/brand_logos 下的 PNG（用户自维护，25 个品牌统一格式）；
     * 无开源图片的品牌（PENTAX）与未知品牌回退文字字标。
     */
    private fun detectBrand(make: String, model: String): BrandMark? {
        val s = "${make} $model".uppercase()
        return when {
            "SONY" in s -> BrandMark("SONY", 0xFF000000.toInt(), "sony.png", spacing = 0.14f)
            "REDMI" in s -> BrandMark("REDMI", 0xFFE4002B.toInt(), "redmi.png", spacing = 0.10f)
            "XIAOMI" in s -> BrandMark("XIAOMI", 0xFFFF6900.toInt(), "xiaomi.png", spacing = 0.10f)
            "HUAWEI" in s -> BrandMark("HUAWEI", 0xFFCF0A2C.toInt(), "huawei.png", spacing = 0.08f)
            "HONOR" in s -> BrandMark("HONOR", 0xFF00A0E9.toInt(), "honor.png", spacing = 0.10f)
            "VIVO" in s -> BrandMark("vivo", 0xFF415FFF.toInt(), "vivo.png")
            "OPPO" in s -> BrandMark("OPPO", 0xFF046A38.toInt(), "oppo.png", spacing = 0.10f)
            "ONEPLUS" in s -> BrandMark("ONEPLUS", 0xFFEB0028.toInt(), "oneplus.png", spacing = 0.06f)
            "CANON" in s -> BrandMark("Canon", 0xFFBF0000.toInt(), "canon.png")
            "NIKON" in s -> BrandMark("Nikon", 0xFF000000.toInt(), "nikon.png")
            "FUJIFILM" in s || "FUJI" in s -> BrandMark("FUJIFILM", 0xFF00A651.toInt(), "fujifilm.png", spacing = 0.04f)
            "LUMIX" in s -> BrandMark("LUMIX", 0xFF0B4EA2.toInt(), "lumix.png", spacing = 0.12f)
            "PANASONIC" in s -> BrandMark("Panasonic", 0xFF0B4EA2.toInt(), "panasonic.png")
            "APPLE" in s || "IPHONE" in s -> BrandMark("iPhone", 0xFF000000.toInt(), "apple.png", spacing = 0.08f)
            "SAMSUNG" in s -> BrandMark("SAMSUNG", 0xFF1428A0.toInt(), "samsung.png", spacing = 0.08f)
            "DJI" in s -> BrandMark("DJI", 0xFF000000.toInt(), "dji.png", spacing = 0.16f)
            "LEICA" in s -> BrandMark("LEICA", 0xFFE20612.toInt(), "leica.png", spacing = 0.16f)
            "PIXEL" in s || "GOOGLE" in s -> BrandMark("Google", 0xFF5F6368.toInt(), "google.png")
            "RICOH" in s -> BrandMark("RICOH", 0xFF00A0B0.toInt(), "ricoh.png", spacing = 0.10f)
            "PENTAX" in s -> BrandMark("PENTAX", 0xFF00A54F.toInt(), spacing = 0.10f)
            "SIGMA" in s -> BrandMark("SIGMA", 0xFF000000.toInt(), "sigma.png", spacing = 0.14f)
            "GOPRO" in s -> BrandMark("GoPro", 0xFF000000.toInt(), "gopro.png", badge = true)
            "OM SYSTEM" in s || "OLYMPUS" in s -> BrandMark("OM SYSTEM", 0xFF0068B7.toInt(), "olympus.png", spacing = 0.08f)
            "NUBIA" in s -> BrandMark("nubia", 0xFFE60012.toInt(), "nubia.png")
            "MEIZU" in s -> BrandMark("MEIZU", 0xFF008CFF.toInt(), "meizu.png", spacing = 0.10f)
            "REALME" in s -> BrandMark("realme", 0xFFFFC915.toInt(), "realme.png", spacing = 0.04f, badge = true)
            else -> null
        }
    }

    /**
     * 图片选择回调：两级加载——快速通道先出 800px 预览（用户马上看到东西），
     * 完整通道再补 EXIF + 1600px 基准图 + 实况检测。
     */
    fun onImagePicked(uri: Uri) {
        AppLog.i("exifframe", "已选择待处理媒体")
        // 选中即取持久化读权限（系统照片选择器授予的临时权限在进程重启后会失效）
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { AppLog.w("exifframe", "takePersistableUriPermission 失败（可忽略）：${it.message}") }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    exporting = false, success = false, message = null,
                    rendering = true, sourceUri = uri, preview = null,
                )
            }
            // 快速通道：小图秒出
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val quick = decodeScaled(uri, targetLong = 800) ?: return@runCatching
                    if (_state.value.sourceUri == uri && sourceBitmap == null) {
                        val s = _state.value
                        val rendered = renderFrame(quick, s.template, s.fields, s)
                        _state.update { st ->
                            if (st.sourceUri == uri && st.preview == null) st.copy(preview = rendered) else st
                        }
                    }
                }
            }
            val ok = runCatching { loadSource(uri) }
                .onFailure { e ->
                    AppLog.w("exifframe", "加载失败：${e.message}")
                    _state.update {
                        it.copy(rendering = false, message = "加载失败：${e.message}")
                    }
                }
                .isSuccess
            if (ok) {
                // 关键：loadSource 阶段设置的 rendering 标志必须在此复位，
                // 否则 renderPreview 的防重入检查会永久拦截渲染（真机卡死根因）
                _state.update { it.copy(rendering = false) }
                renderPreview()
            }
        }
    }

    /**
     * 流式采样解码，**三级降级**：
     * 1. fd 路径（**主路径**）：openFileDescriptor + seekTo(0) 复位 + BitmapFactory 采样，
     *    解码后按 EXIF rotation 旋转（照片选择器 URI 的 openInputStream 在部分 provider
     *    上恒为 null，fd 更稳；seekTo(0) 修复 bounds 探测与正式解码共用 fd 的偏移错位）；
     * 2. 流路径（openInputStream）：部分 provider 的 fd 读取有兼容问题时的兜底；
     * 3. ImageDecoder：HEIF/HDR/动图等 BitmapFactory 解不了的格式（自动应用 EXIF 旋转）。
     */
    private fun decodeScaled(uri: Uri, targetLong: Int): Bitmap? {
        // ── 1. fd 路径（主路径）──
        val fromFd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetLong) sample *= 2
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val rotation = runCatching {
                    ExifInterface(pfd.fileDescriptor).rotationDegrees
                }.getOrNull() ?: 0
                applyRotation(decoded, rotation)
            }
        }.onFailure { e ->
            AppLog.w("exifframe", "fd 路径异常：${e::class.simpleName}: ${e.message}")
        }.getOrNull()
        if (fromFd != null && fromFd.width > 0 && fromFd.height > 0) {
            AppLog.w("exifframe", "fd 路径解码 ${fromFd.width}x${fromFd.height} (target=$targetLong)")
            return fromFd
        }

        // ── 2. 流路径兜底 ──
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var streamOpened = false
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                streamOpened = true
                // 注意：inJustDecodeBounds=true 时 decodeStream 恒返回 null（只填充 bounds），
                // 不能用返回值判断是否成功
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.onFailure {
            AppLog.w("exifframe", "openInputStream 异常：${it::class.simpleName}: ${it.message}")
        }
        if (streamOpened && bounds.outWidth > 0) {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetLong) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
            if (decoded != null) {
                val rotation = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        ExifInterface(it.fileDescriptor).rotationDegrees
                    }
                }.getOrNull() ?: 0
                return applyRotation(decoded, rotation)
            }
        }
        AppLog.w(
            "exifframe",
            "fd/流路径失败（opened=$streamOpened bounds=${bounds.outWidth}x${bounds.outHeight} " +
                "mime=${bounds.outMimeType}），降级 ImageDecoder"
        )

        // ── 3. ImageDecoder 路径（自动应用 EXIF 旋转，支持 HEIC/HDR）──
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
        }.onFailure { AppLog.w("exifframe", "ImageDecoder 也失败：${it::class.simpleName}: ${it.message}") }
            .getOrNull()
    }

    /** 按 EXIF 旋转角度旋转位图（90/180/270），0 度原样返回 */
    private fun applyRotation(src: Bitmap?, degrees: Int): Bitmap? {
        if (src == null || degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }.getOrNull()
        if (rotated != null && rotated != src) {
            runCatching { src.recycle() }
            return rotated
        }
        return rotated ?: src
    }

    private suspend fun loadSource(uri: Uri) = withContext(Dispatchers.IO) {
        // 基准图：1600px 流式采样解码（含 EXIF 旋转）
        val bitmap = decodeScaled(uri, targetLong = 1600)
            ?: throw IllegalStateException("图片解码失败")
        sourceBitmap = bitmap

        // EXIF 预填：**fd 路径优先**（真机实锤：照片选择器 URI 的 openInputStream 恒为
        // null，fd 路径稳定），失败/读不到时流路径兜底
        var exif: ExifInterface? = null
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                exif = ExifInterface(it.fileDescriptor)
            }
        }.onFailure { AppLog.w("exifframe", "EXIF fd 读取异常：${it.message}") }
        if (exif?.getAttribute(ExifInterface.TAG_MODEL).isNullOrEmpty()) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { exif = ExifInterface(it) }
            }.onFailure { AppLog.w("exifframe", "EXIF 流读取异常：${it.message}") }
        }
        fun exifOf(tag: String) = exif?.getAttribute(tag).orEmpty().trim()
        val make = exifOf(ExifInterface.TAG_MAKE)
        val model = exifOf(ExifInterface.TAG_MODEL)
        val focal = exifOf(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)
            .ifEmpty { exifOf(ExifInterface.TAG_FOCAL_LENGTH) }
        val exposure = FrameGeometry.formatShutter(
            exifOf(ExifInterface.TAG_EXPOSURE_TIME).toDoubleOrNull(),
            exifOf(ExifInterface.TAG_SHUTTER_SPEED_VALUE).toDoubleOrNull(),
        )
        val iso = exifOf(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            .ifEmpty { exifOf(ExifInterface.TAG_ISO_SPEED_RATINGS) }
            .let { if (it.isNotEmpty()) "ISO$it" else "" }
        val fNumber = exifOf(ExifInterface.TAG_F_NUMBER).toDoubleOrNull()
            ?.let { "f/%.1f".format(it) }.orEmpty()
        val takenAt = formatExifDate(
            exifOf(ExifInterface.TAG_DATETIME_ORIGINAL).ifEmpty { exifOf(ExifInterface.TAG_DATETIME) }
        )
        AppLog.w(
            "exifframe",
            "EXIF 结果：make=$make model=$model focal=$focal exposure=$exposure iso=$iso f=$fNumber date=$takenAt"
        )
        sourceBrand = detectBrand(make, model)

        // 实况图检测：能被 MotionPhotoParser 解析出视频即视为实况
        val motionVideo = runCatching {
            MotionPhotoParser.parse(context, uri).videoFile
        }.getOrNull()
        // 每次解析登记一份，退出时统一删。不在换图时立刻删：解析器可能按 uri 复用同一个文件，
        // 先删后用会让下一次导出拿到一个已被删除的路径。
        // 登记整个解析会话目录（含源文件拷贝与 gain map），不是单个 videoFile
        motionVideo?.parentFile?.let { dir ->
            if (cleared.get()) {
                dir.deleteRecursively()
            } else {
                tempFiles += dir
            }
        }
        sourceMotionVideo = motionVideo

        _state.update {
            it.copy(
                isMotion = motionVideo != null,
                // 型号读不到 = 照片缺完整 EXIF（被编辑/压缩过），提示用户手动补填
                message = if (model.isEmpty()) {
                    "未读取到完整拍摄信息（照片可能经编辑或压缩），可在下方手动填写后导出"
                } else null,
                fields = listOf(
                    FrameField("相机型号", model),
                    FrameField("等效焦距", if (focal.isNotEmpty()) "${focal}mm" else ""),
                    FrameField("快门", exposure),
                    FrameField("ISO", iso),
                    FrameField("光圈", fNumber),
                    FrameField("拍摄时间", takenAt),
                )
            )
        }
        baselineFields = _state.value.fields
    }

    /**
     * 回到这台相机的初始样式：模板/自定义文字/LOGO/圆角与字段显示全部复位，
     * 拍摄信息回到刚读出来的 EXIF 快照。
     *
     * **不动源图**——「重新选择照片」是另一个动作，原来它和重置混在同一个 `reset()` 里，
     * 用户想撤销一次模板切换就会连照片一起丢。
     */
    fun resetStyle() {
        _state.update {
            it.copy(
                template = FrameTemplate.CLASSIC_WHITE,
                customText = "",
                keepLogo = true,
                rounded = false,
                fields = baselineFields
            )
        }
        renderPreview()
    }

    /** EXIF 时间 `2026:09:11 20:31:05` → 显示用 `2026-09-11 20:31`（解析失败原样返回） */
    private fun formatExifDate(raw: String): String {
        if (raw.isEmpty()) return ""
        val parsed = runCatching {
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .apply { isLenient = true }
                .parse(raw)
        }.getOrNull() ?: return raw
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(parsed)
    }

    fun setTemplate(template: FrameTemplate) {
        _state.update { it.copy(template = template) }
        renderPreview()
    }

    /** 自定义文字（署名/地点/版权） */
    fun setCustomText(text: String) {
        _state.update { it.copy(customText = text) }
        scheduleRender()
    }

    fun setKeepLogo(keep: Boolean) {
        _state.update { it.copy(keepLogo = keep) }
        renderPreview()
    }

    fun setRounded(rounded: Boolean) {
        _state.update { it.copy(rounded = rounded) }
        renderPreview()
    }

    /** 字段显示开关（例如不想暴露快门速度） */
    fun toggleField(label: String, enabled: Boolean) {
        _state.update { s ->
            s.copy(fields = s.fields.map { if (it.label == label) it.copy(enabled = enabled) else it })
        }
        renderPreview()
    }

    /** 导出配置（格式 / 画质 / 元数据策略）。不触发重渲染——它不影响画面，只影响落盘。 */
    fun setExportConfig(config: ExportConfig) {
        _state.update { it.copy(exportConfig = config) }
    }

    private var fieldDebounceJob: kotlinx.coroutines.Job? = null

    /** 字段编辑：300ms 防抖后重渲染——逐字全量渲染 1600px 位图会造成连续卡顿 */
    fun setField(label: String, value: String) {
        _state.update { s ->
            s.copy(fields = s.fields.map { if (it.label == label) it.copy(value = value) else it })
        }
        scheduleRender()
    }

    private fun scheduleRender() {
        fieldDebounceJob?.cancel()
        fieldDebounceJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            renderPreview()
        }
    }

    /** 按当前模板与字段渲染预览（IO 线程） */
    private fun renderPreview() {
        val state = _state.value
        val source = sourceBitmap ?: return
        if (state.rendering) return
        _state.update { it.copy(rendering = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val rendered = runCatching {
                renderFrame(source, state.template, state.fields, state)
            }.onFailure { e ->
                AppLog.w("exifframe", "渲染失败：${e::class.simpleName}: ${e.message}")
            }.getOrNull()
            _state.update { s ->
                // 渲染失败时保留旧预览并给出提示，避免界面突然空白
                if (rendered != null) {
                    s.copy(preview = rendered, rendering = false, message = null)
                } else {
                    s.copy(
                        rendering = false,
                        message = s.message ?: "预览渲染失败，请尝试更换模板或重新选择照片"
                    )
                }
            }
        }
    }

    // ────────────────────────── 画框渲染 ──────────────────────────

    /** 渲染入口：按模板分派到三种布局（带边框信息栏 / 白框悬浮 / 照片叠字） */
    private fun renderFrame(
        source: Bitmap,
        template: FrameTemplate,
        fields: List<FrameField>,
        state: ExifFrameState
    ): Bitmap = when (template) {
        FrameTemplate.POLAROID -> renderPolaroid(source, fields, state)
        FrameTemplate.MINIMAL -> renderMinimal(source, fields, state)
        FrameTemplate.CLASSIC_WHITE -> renderFramed(
            source, fields, state,
            Palette(
                Color.WHITE, hex("#111214"),
                hex("#8A8F98"), hex("#E6E8EB")
            ),
            FrameGeometry.FRAMED
        )
        FrameTemplate.DARK_BAR -> renderFramed(
            source, fields, state,
            Palette(
                hex("#0B0C0E"), hex("#F4F5F7"),
                hex("#9BA1A9"), hex("#26282C")
            ),
            FrameGeometry.FRAMED
        )
        FrameTemplate.SIGNATURE -> renderSignature(source, fields, state)
    }

    /**
     * 带边框 + 底部信息栏（经典白边 / 暗色底栏共用）。
     *
     * 版式：四边留白 [borderRatio]×宽 → 照片（可选圆角）→ 底部信息区（占宽约 0.155）
     * → 第一行「品牌 LOGO + 型号」（Medium、主色），第二行「参数 · 参数」（Regular、次级色）。
     */
    private fun renderFramed(
        source: Bitmap,
        fields: List<FrameField>,
        state: ExifFrameState,
        palette: Palette,
        spec: FrameGeometry.FrameSpec
    ): Bitmap {
        val w = source.width
        val h = source.height
        val out = FrameGeometry.output(spec, w, h)
        val border = out.sideMargin
        val barH = out.barHeight
        val outW = out.width
        val result = createBitmap(outW, out.height)
        val canvas = Canvas(result)
        canvas.drawColor(palette.bg)

        drawPhoto(canvas, source, border.toFloat(), border.toFloat(), state.rounded, w * 0.025f)

        val padX = border + w * 0.045f
        val rowWidth = outW - padX * 2
        val modelText = visibleValue(fields, "相机型号")
        val params = paramLine(fields)
        val barTop = (border + h).toFloat()
        val line1 = barTop + barH * 0.44f
        val line2 = barTop + barH * 0.78f
        val brandH = barH * 0.30f
        val darkBg = !isLight(palette.bg)

        var cursor = padX
        if (state.keepLogo) {
            val mark = brandOf()
            drawBrand(canvas, mark, cursor, line1 - brandH / 2f, brandH, darkBg)
            cursor += brandLogoWidth(mark, brandH) + w * 0.02f
        }

        // 第一行是「型号（左）+ 自定义文字（右）」两段。两段各自按整行宽缩放时
        // 各自都判定放得下、合起来却超出画布——表现是两段叠在一起。
        // 所以先给右段封顶 55%，再把剩余宽度作为左段的预算。
        val custom = state.customText.trim()
        var customWidth = 0f
        if (custom.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                typeface = fontRegular
                textAlign = Paint.Align.RIGHT
            }
            val budget = rowWidth * 0.55f
            customWidth = drawFitted(
                canvas, paint, custom, outW - padX, line1,
                barH * 0.20f, barH * 0.13f, budget,
            )
        }
        // 型号从 cursor 起，到「右段文字左边缘再留一个间距」为止。
        // 这里的 totalWidth **不再**自带右侧留白：outW - padX 已经把右内边距算掉了，
        // 再减一次 w*0.02f 就是凭空多出两倍间距——不重叠，但会把放得下的型号截短。
        if (modelText.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.fg
                typeface = fontMedium
            }
            val budget = FrameGeometry.leftBudgetOf(
                outW - padX - cursor, customWidth, w * 0.02f,
            )
            drawFitted(
                canvas, paint, modelText, cursor, line1,
                barH * 0.26f, barH * 0.16f, budget,
            )
        }
        if (params.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                typeface = fontRegular
            }
            drawFitted(
                canvas, paint, params, padX, line2,
                barH * 0.19f, barH * 0.12f, outW - padX * 2,
            )
        }
        // 分隔线：信息区上沿一条细线，弱化「贴了一条色块」的观感
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.divider
            strokeWidth = hairlineFor(w)
        }
        canvas.drawLine(padX, barTop + barH * 0.16f, outW - padX, barTop + barH * 0.16f, divider)
        return result
    }

    /** 白框悬浮（拍立得）：四周白纸留白更大，底部信息居中，照片下方带柔和投影 */
    private fun renderPolaroid(source: Bitmap, fields: List<FrameField>, state: ExifFrameState): Bitmap {
        val w = source.width
        val h = source.height
        val out = FrameGeometry.output(FrameGeometry.POLAROID, w, h)
        val marginX = out.sideMargin
        val marginTop = marginX
        val bottomH = out.barHeight
        val outW = out.width
        val result = createBitmap(outW, out.height)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        // 悬浮投影：先画一张照片形状的模糊黑块（带偏移），再画照片本体
        val blur = (w * 0.022f).coerceAtLeast(6f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            color = Color.argb(120, 0, 0, 0)
        }
        val shadowPath = Path().apply {
            addRoundRect(
                RectF(
                    marginX + w * 0.012f, marginTop + h * 0.022f,
                    marginX + w * 1.012f, marginTop + h * 1.022f
                ),
                w * 0.012f, w * 0.012f, Path.Direction.CW
            )
        }
        canvas.drawPath(shadowPath, shadowPaint)
        drawPhoto(canvas, source, marginX.toFloat(), marginTop.toFloat(), state.rounded, w * 0.025f)

        // 底部居中：第一行「品牌 + 型号」，第二行「参数 · 自定义文字」
        val modelText = visibleValue(fields, "相机型号")
        val params = paramLine(fields)
        val mark = brandOf()
        val brandH = bottomH * 0.26f
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hex("#111214")
            typeface = fontMedium
            textSize = bottomH * 0.22f
        }
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hex("#8A8F98")
            typeface = fontRegular
            textSize = bottomH * 0.17f
        }
        var firstLineW = 0f
        if (state.keepLogo) firstLineW += brandLogoWidth(mark, brandH) + w * 0.02f
        // 先适配再算居中位置：拿缩放前的宽度定位，字号一缩整行就偏
        val fittedModel = if (modelText.isNotEmpty()) {
            fit(modelPaint, modelText, bottomH * 0.22f, bottomH * 0.14f, outW * 0.8f)
        } else null
        if (fittedModel != null) firstLineW += fittedModel.width
        val line1Y = marginTop + h + bottomH * 0.38f
        val line2Y = marginTop + h + bottomH * 0.74f
        var x = (outW - firstLineW) / 2f
        if (state.keepLogo) {
            drawBrand(canvas, mark, x, line1Y - brandH / 2f, brandH, darkBg = false)
            x += brandLogoWidth(mark, brandH) + w * 0.02f
        }
        if (fittedModel != null) {
            canvas.drawText(fittedModel.text, x, baselineFor(modelPaint, line1Y), modelPaint)
        }
        val custom = state.customText.trim()
        val second = listOf(params, custom).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (second.isNotEmpty()) {
            val fitted = fit(paramPaint, second, bottomH * 0.17f, bottomH * 0.11f, outW * 0.82f)
            canvas.drawText(
                fitted.text,
                outW / 2f - fitted.width / 2f,
                baselineFor(paramPaint, line2Y),
                paramPaint,
            )
        }
        return result
    }

    /**
     * 双行签名：贴边（无白边）+ 底部深色信息区 + 左侧强调竖线。
     * 与经典白边的差别是「编辑感」——强调线 + 大写字距的型号，适合发布用。
     */
    private fun renderSignature(source: Bitmap, fields: List<FrameField>, state: ExifFrameState): Bitmap {
        val w = source.width
        val h = source.height
        val barH = FrameGeometry.output(FrameGeometry.SIGNATURE, w, h).barHeight
        val result = createBitmap(w, h + barH)
        val canvas = Canvas(result)
        canvas.drawColor(hex("#0B0C0E"))
        canvas.drawBitmap(source, 0f, 0f, null)

        val padX = w * 0.055f
        val accentW = (w * 0.006f).coerceAtLeast(2f)
        val barTop = h.toFloat()
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hex("#E8B75A") }
        canvas.drawRect(padX, barTop + barH * 0.26f, padX + accentW, barTop + barH * 0.78f, accent)

        val textX = padX + accentW + w * 0.03f
        val modelText = visibleValue(fields, "相机型号").uppercase()
        val params = paramLine(fields)
        val line1 = barTop + barH * 0.44f
        val line2 = barTop + barH * 0.76f
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hex("#F4F5F7")
            typeface = fontMedium
            textSize = barH * 0.28f
            letterSpacing = 0.02f
        }
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hex("#9BA1A9")
            typeface = fontRegular
            textSize = barH * 0.18f
            letterSpacing = 0.05f
        }
        if (modelText.isNotEmpty()) {
            // 右侧要留给品牌 LOGO，两者各自按整行缩放会叠在一起：先扣掉 LOGO 的位置
            val mark = brandOf()
            val brandH = barH * 0.26f
            val logoReserve = if (state.keepLogo) brandLogoWidth(mark, brandH) + padX else 0f
            drawFitted(
                canvas, modelPaint, modelText, textX, line1,
                barH * 0.28f, barH * 0.16f, w - textX - padX - logoReserve,
            )
        }
        if (state.keepLogo) {
            val mark = brandOf()
            val brandH = barH * 0.26f
            drawBrand(
                canvas, mark,
                w - padX - brandLogoWidth(mark, brandH),
                line1 - brandH / 2f, brandH, darkBg = true
            )
        }
        val custom = state.customText.trim()
        val second = listOf(params, custom).filter { it.isNotEmpty() }.joinToString("   ")
        if (second.isNotEmpty()) {
            drawFitted(
                canvas, paramPaint, second, textX, line2,
                barH * 0.18f, barH * 0.11f, w - textX - padX,
            )
        }
        return result
    }

    /**
     * 极简叠字：不改变画面尺寸，在照片底部叠加渐变遮罩后写信息。
     *
     * v1 是把半透明黑条画在照片**下方**（白底上就是一条灰带），v2 改为照片内部叠字。
     */
    private fun renderMinimal(source: Bitmap, fields: List<FrameField>, state: ExifFrameState): Bitmap {
        val w = source.width
        val h = source.height
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val scrimH = (h * 0.30f).coerceAtLeast(60f)

        // 自下而上的渐变遮罩：底部不透明黑 → 顶部完全透明
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h - scrimH, 0f, h.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Color.argb(215, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h - scrimH, w.toFloat(), h.toFloat(), scrim)

        val padX = w * 0.05f
        val padBottom = h * 0.045f
        val modelText = visibleValue(fields, "相机型号")
        val params = paramLine(fields)
        val custom = state.customText.trim()
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = fontMedium
            textSize = (w * 0.032f).coerceAtLeast(14f)
        }
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = hex("#E4E6EA")
            typeface = fontRegular
            textSize = (w * 0.022f).coerceAtLeast(11f)
        }
        val baseline2 = h - padBottom
        val baseline1 = baseline2 - modelPaint.textSize * 1.25f
        var cursor = padX
        if (state.keepLogo) {
            val mark = brandOf()
            val brandH = modelPaint.textSize * 0.95f
            drawBrand(canvas, mark, cursor, baseline1 - brandH * 0.78f, brandH, darkBg = true)
            cursor += brandLogoWidth(mark, brandH) + w * 0.02f
        }
        if (modelText.isNotEmpty()) {
            val base = modelPaint.textSize
            drawFittedAtBaseline(
                canvas, modelPaint, modelText, cursor, baseline1,
                base, base * 0.6f, w - padX - cursor,
            )
        }
        val second = listOf(params, custom).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (second.isNotEmpty()) {
            val base = paramPaint.textSize
            drawFittedAtBaseline(
                canvas, paramPaint, second, padX, baseline2,
                base, base * 0.7f, w - padX * 2,
            )
        }
        return result
    }

    /** 画照片：可选圆角（用裁剪路径实现，避免额外分配一张圆角位图） */
    private fun drawPhoto(
        canvas: Canvas,
        source: Bitmap,
        left: Float,
        top: Float,
        rounded: Boolean,
        radius: Float
    ) {
        if (!rounded) {
            canvas.drawBitmap(source, left, top, null)
            return
        }
        val path = Path().apply {
            addRoundRect(
                RectF(left, top, left + source.width, top + source.height),
                radius, radius, Path.Direction.CW
            )
        }
        canvas.withSave {
            clipPath(path)
            drawBitmap(source, left, top, null)
        }
    }

    /** 字段显示值：等效焦距渲染为「等效50mm」而非裸 "50mm" */
    private fun displayValue(f: FrameField): String =
        if (f.label == "等效焦距" && f.value.isNotEmpty()) "等效${f.value}" else f.value

    private fun visibleValue(fields: List<FrameField>, label: String): String =
        fields.firstOrNull { it.label == label && it.enabled }?.value?.trim().orEmpty()

    /** 第二行参数：除型号外的所有启用字段，` · ` 分隔（空值自动跳过） */
    private fun paramLine(fields: List<FrameField>): String =
        fields
            .filter { it.label != "相机型号" && it.enabled && it.value.isNotBlank() }
            .joinToString("  ·  ") { displayValue(it).trim() }

    private fun brandOf(): BrandMark =
        sourceBrand ?: BrandMark("IMAGEDGE", hex("#333333"))

    /** 文字视觉中心 → baseline（用 FontMetrics 精确换算，避免不同字体的基线偏移） */
    private fun baselineFor(paint: Paint, centerY: Float): Float {
        val fm = paint.fontMetrics
        return centerY - (fm.ascent + fm.descent) / 2f
    }

    /**
 * 一段已经适配好宽度的文字。[width] 是缩放与截断**之后**的真实占位，
     * 居中排版必须用它——先前拍立得模板拿缩放前的宽度算 `x`，于是字号一缩整行就偏。
     */
    private class Fitted(val text: String, val width: Float)

    /**
     * 把一段文字放进 [maxWidth]：先按基准字号量宽，超出则等比缩小，**缩到下限仍放不下就截断加省略号**。
     *
     * 截断这一层是后补的。原先只 `coerceAtLeast(minSize)` 就结束，于是长型号名与长署名
     * 会画到画布外，或与同一行的另一段**静默重叠**——两处都不报错，表现为"某个名字显得怪"。
     * 纯算术在 [FrameGeometry]（可 JVM 单测），这里只把 `Paint.measureText` 接上去。
     */
    private fun fit(paint: Paint, text: String, baseSize: Float, minSize: Float, maxWidth: Float): Fitted {
        paint.textSize = FrameGeometry.fitTextSize(baseSize, minSize, maxWidth) {
            paint.textSize = it
            paint.measureText(text)
        }
        val clipped = FrameGeometry.ellipsize(text, maxWidth) { paint.measureText(it.toString()) }
        return Fitted(clipped, paint.measureText(clipped))
    }

    /** [fit] 的直接绘制版；**返回实际占用宽度**，调用方据此给同行另一段让位。 */
    private fun drawFitted(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        centerY: Float,
        baseSize: Float,
        minSize: Float,
        maxWidth: Float,
    ): Float {
        val fitted = fit(paint, text, baseSize, minSize, maxWidth)
        canvas.drawText(fitted.text, x, baselineFor(paint, centerY), paint)
        return fitted.width
    }

    /**
     * 同 [drawFitted]，但 [baselineY] 直接就是基线。
     *
     * 极简叠字的两行位置是从画面底边倒推的**基线**（`h - padBottom`），不是视觉中心。
     * 把它当中心线喂进 [baselineFor] 需要一个"ascent/descent 均值 ÷ 字号"的折算系数，
     * 而那个系数依赖字体度量、且字号在自适应之后还会变——猜出来的结果随字体而异。
     * 需要基线的地方就用这个重载，不要折算。
     */
    private fun drawFittedAtBaseline(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baselineY: Float,
        baseSize: Float,
        minSize: Float,
        maxWidth: Float,
    ): Float {
        val fitted = fit(paint, text, baseSize, minSize, maxWidth)
        canvas.drawText(fitted.text, x, baselineY, paint)
        return fitted.width
    }

    /** 细线线宽：按位图宽度取，避免高分辨率下 1px 细线消失 */
    private fun hairlineFor(bitmapWidth: Int): Float = (bitmapWidth / 1000f).coerceAtLeast(1f)

    private fun isLight(color: Int): Boolean {
        val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return luminance > 160
    }

    /** 十六进制颜色 → ARGB（统一走 KTX，避免逐处 Color.parseColor） */
    private fun hex(value: String): Int = value.toColorInt()

    /**
     * 品牌 LOGO 渲染：优先 assets/brand_logos 下的 PNG，缺失时回退特征文字字标。
     * - 透明底 PNG：深色底（DARK/MINIMAL/SIGNATURE）下用 SRC_IN 染白保证可见。
     * - badge = true（gopro 黑底 / realme 黄底）：官方徽章带纯色底，不参与染色。
     */
    private fun drawBrand(
        canvas: Canvas,
        mark: BrandMark,
        left: Float,
        top: Float,
        height: Float,
        darkBg: Boolean,
    ) {
        val png = mark.assetPng?.let(::pngBitmap)
        if (png != null) {
            val ratio = png.width.toFloat() / maxOf(1, png.height)
            var w = height * ratio
            if (w > height * 4.5f) w = height * 4.5f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (darkBg && !mark.badge) {
                p.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(png, null, RectF(left, top, left + w, top + height), p)
            return
        }
        // 文字字标回退（PENTAX / 未知品牌）
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkBg) Color.WHITE else mark.color
            typeface = fontMedium
            textSize = height
            letterSpacing = mark.spacing
        }
        canvas.drawText(mark.text, left, top + height * 0.85f, p)
    }

    /** 品牌 LOGO 的渲染宽度（与 [drawBrand] 的宽高比一致，供布局计算） */
    private fun brandLogoWidth(mark: BrandMark, height: Float): Float {
        val png = mark.assetPng?.let(::pngBitmap)
        if (png != null) {
            val ratio = png.width.toFloat() / maxOf(1, png.height)
            var w = height * ratio
            if (w > height * 4.5f) w = height * 4.5f
            return w
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fontMedium
            textSize = height
            letterSpacing = mark.spacing
        }.measureText(mark.text)
    }

    /** 解码 assets/brand_logos 下的品牌 PNG（带缓存） */
    private fun pngBitmap(name: String): Bitmap? {
        pngCache[name]?.let { return it }
        val bmp = runCatching {
            context.assets.open("brand_logos/$name").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (bmp != null) pngCache[name] = bmp
        return bmp
    }

    /**
     * 导出：按原分辨率重算 → [ExportManager] 落盘（格式 / 画质 / 元数据策略）→ 提交相册；
     * 实况图再把视频与画框静态图重新合成。
     *
     * **修复导出分辨率腰斩。** 原实现拿预览用的 1600px 基准图直接落盘，
     * 于是 6000px 的照片导出成 1600px——像素丢掉约 93%。同一个 bug 在编辑调节里
     * 已经修过（CHANGELOG「导出分辨率腰斩」），修法是逐个 feature 落的，漏了这里。
     *
     * 版式不受影响：所有尺寸按源图宽取比例，且 [FrameGeometry.proportionalAbove]
     * 把「1600px 以上不再触及下限」钉成了单测，所以预览与成品是同构的。
     */
    fun export() {
        val state = _state.value
        val sourceUri = state.sourceUri ?: return
        if (state.exporting) return
        // 源图没加载成功（解码失败等）时明确提示，而不是让 export 内部解一次全分辨率
        // 再失败——后者会把「源图坏了」显示成「导出失败」，误导用户重试。
        if (sourceBitmap == null) {
            _state.update { it.copy(message = "图片尚未加载成功，请重新选择或更换图片", success = false) }
            return
        }
        // 判定一次、出口两处：界面提示与导出拒绝用同一句话。
        // 两处各判一次就会出现「开关是亮的、点下去失败」。
        motionFormatReason(state.isMotion, state.exportConfig.format)?.let { reason ->
            _state.update { it.copy(message = reason, success = false) }
            return
        }
        // 导出期间可能改格式，所以这里定下的 config 要一路带到落盘——
        // commitToGallery 再读一次 _state 就会与文件名/实际编码不一致
        val config = state.exportConfig
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null) }
            // 在任何挂起之前就抓住：导出期间用户点「重新选择照片」会把字段置空，
            // 抓到之后再读就只剩一份普通照片，实况的那段视频白解了。
            val motionVideo = sourceMotionVideo
            var exported: File? = null
            try {
                exported = withContext(Dispatchers.IO) {
                    val full = decodeScaled(sourceUri, ExportLimits.maxLongEdge())
                        ?: throw IllegalStateException("源图解码失败，请重新选择照片")
                    val rendered = try {
                        renderFrame(full, state.template, state.fields, state)
                    } finally {
                        // 全分辨率那张比预览那张大一个量级，画完立刻放掉；
                        // 否则它会和成品位图、导出缓冲一起压在峰值上
                        full.recycle()
                    }
                    try {
                        ExportManager(context).exportRendered(
                            bitmap = rendered,
                            exifSource = sourceUri,
                            config = config,
                            nameBase = "IMAGEDGE_${System.currentTimeMillis()}",
                        )
                    } finally {
                        // 必须 finally 而不是 `.also`：编码器写一半失败时 exportRendered
                        // 会抛，而 `.also` 只在成功时跑。6000px 宽的成品位图约 136MB，
                        // 漏掉这一次就是一次实打实的内存尖峰。
                        rendered.recycle()
                    }
                }
                if (motionVideo != null) {
                    // 实况图：画框静态图 + 原视频重新合成（视频内原封面帧时间戳保持不变）。
                    //
                    // EXIF 策略在这里**必须自己判一次**：MotionPhotoComposer 走的是
                    // MotionPhotoExifPreserver，它无条件把源的拍摄参数注回封面，
                    // 不知道 ExifPolicy 是什么。不传就是「保留全部」——
                    // 于是「清除全部信息」这个选项在实况图上是个摆设。
                    // （STRIP_LOCATION 无需额外处理：那个 preserver 本来就不复制 GPS。）
                    val exifForMotion =
                        if (config.exif == ExifPolicy.STRIP_ALL) null else sourceUri
                    val result = MotionPhotoComposer.compose(
                        context = context,
                        imageUri = Uri.fromFile(exported!!),
                        videoUri = Uri.fromFile(motionVideo),
                        exifSourceUri = exifForMotion,
                    )
                    MotionPhotoComposer.saveToGallery(context, result)
                    AppLog.i("exifframe", "实况画框已导出：${result.displayName}")
                } else {
                    commitToGallery(exported, sourceUri, config.format)
                    AppLog.i("exifframe", "画框照片已导出")
                }
                _state.update {
                    it.copy(exporting = false, success = true, message = "已保存到相册（DCIM/Imagedge）")
                }
                haptics.thud()
            } catch (e: Exception) {
                AppLog.w("exifframe", "导出失败：${e.message}")
                _state.update { it.copy(exporting = false, message = "导出失败：${e.message}") }
                haptics.double()
            } finally {
                // 失败路径同样要删：只写在成功分支上，导出失败一次就漏一份成品图
                runCatching { exported?.delete() }
            }
        }
    }

    /**
     * 普通照片落盘：MediaStore DCIM/Imagedge。
     *
     * 修复 v1 的三处缺陷：文件名拼写（IMGDEGE → IMAGEDGE）、丢 EXIF（现在复制拍摄参数与
     * 时间）、落盘缺 `IS_PENDING`/`DATE_TAKEN`（相册会看到半成品，且按保存时间而非拍摄时间排序）。
     */
    private fun commitToGallery(exported: File, sourceUri: Uri, format: ExportFormat) {
        val resolver = context.contentResolver
        val dateMillis = runCatching {
            resolver.openFileDescriptor(sourceUri, "r")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.let(::parseExifDate)
            }
        }.getOrNull()

        // 拍摄时间读**源图**而不是导出件：策略选「清除全部」或格式选 PNG 时，
        // 导出件里根本没有 EXIF，这时 DATE_TAKEN 写不进去，
        // 去年拍的照片修完就插进相册「今天」那一堆里。
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, exported.name)
            // MIME 与扩展名必须跟着实际编码走。写死 image/jpeg 会让相册把一个
            // WebP/PNG 索引成 JPEG，接收方按 MIME 分派解码器时直接解不出来。
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
                exported.inputStream().use { it.copyTo(out) }
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
            // 半成品留在相册里就是一个打不开的条目，删掉再抛
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** EXIF 时间格式 `yyyy:MM:dd HH:mm:ss` → 毫秒时间戳 */
    private fun parseExifDate(value: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .apply { isLenient = true }
            .parse(value)
            ?.time
    }.getOrNull()

    /**
     * 回初始态（结果页「继续」、重新选择照片）。
     *
     * 刻意**不删文件**：导出可能正在读这些视频，而「重新选择照片」在导出期间也是可点的。
     * 在这里删会把一次「静默的实况图」变成一次「导出失败」。删除只发生在 [onCleared]。
     */
    fun reset() {
        sourceBitmap = null
        sourceMotionVideo = null
        sourceBrand = null
        pngCache.clear()
        _state.update { ExifFrameState() }
    }

    /** 删掉本次编辑在 cache 里占下的所有解析会话目录 */
    private fun cleanup() {
        tempFiles.forEach { runCatching { it.deleteRecursively() } }
        tempFiles.clear()
    }

    override fun onCleared() {
        // 先立旗标：此刻之后才返回的解析结果会在登记处自己删掉自己，
        // 否则「解析中离开页面」会把会话目录留在 cache 里。
        cleared.set(true)
        cleanup()
        super.onCleared()
    }

    companion object {
        /**
         * 实况图的格式限制：**只能 JPEG**。
         *
         * Motion Photo 的规格是「一张 JPEG + 一段视频」——相册是靠 JPEG 里的
         * XMP 与 MPF 段认出它是动态图的。换成 PNG/WebP，画框静态图与视频就再也拼不成
         * 动态照片：合成要么失败，要么产出一个相册不认、用户看着"动不了"的文件。
         *
         * 所以这条不能只靠"点下去会失败"——那是用户在相册里才发现的失败。
         * 判定一次、出口两处：界面提示与 [export] 的拒绝用同一句话。
         *
         * 放在 companion 而不是实例上：它不碰任何状态，而挂在实例上就没法在
         * JVM 上直接验收（构造 ViewModel 需要 Context）。
         *
         * @return 一句给用户看的原因；null 表示这个组合可以导出。
         */
        fun motionFormatReason(isMotion: Boolean, format: ExportFormat): String? = when {
            !isMotion -> null
            format != ExportFormat.JPEG ->
                "实况图只能导出为 JPEG：动态照片依赖 JPEG 里的 XMP 与 MPF 段，换格式后视频会与静态图失去关联"
            else -> null
        }

        /**
         * 这里曾有一份 25 个 tag 的 `COPY_EXIF_TAGS`，无条件全拷、含 GPS。
         *
         * 删掉的理由不是"重复"，是**它让用户无从选择**：`:share` 早就有
         * `ExifPolicy`（保留全部 / 仅清除位置 / 清除全部），分享面板和编辑调节都接了，
         * 只有这里一份自带常量把坐标焊死在成品里。相机照片的 EXIF 带 GPS，
         * 发到公开平台等于公开拍摄地点——这不是隐私洁癖，是这个功能本来的用途要求的。
         *
         * 元数据复制现在由 [ExportManager.exportRendered] 按 [ExportConfig.exif] 统一处理。
         */
    }
}
