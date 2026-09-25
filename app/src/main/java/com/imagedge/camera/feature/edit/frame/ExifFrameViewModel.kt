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

    /** 单个可编辑字段（EXIF 预填 + 手动覆盖 + 是否显示） */
    data class FrameField(val label: String, val value: String, val enabled: Boolean = true)

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
        val exporting: Boolean = false,
        val message: String? = null,
        val success: Boolean = false,
    ) {
        /**
         * 有没有可撤销的改动。编辑器骨架据此决定「重置」能不能点——
         * 一张刚选好的照片本来就在默认样式上，摆一个「重置」只会让人以为动了什么。
         */
        val hasEdits: Boolean
            get() = template != FrameTemplate.CLASSIC_WHITE ||
                customText.isNotBlank() || rounded || !keepLogo ||
                fields.any { !it.enabled }
    }

    private val _state = MutableStateFlow(ExifFrameState())
    val state: StateFlow<ExifFrameState> = _state.asStateFlow()

    /** 基准原图（预览/导出共用，1600px 长边降采样） */
    private var sourceBitmap: Bitmap? = null
    /** EXIF 刚读出来时的字段快照：「重置」回到这里，而不是回到空白 */
    private var baselineFields: List<FrameField> = emptyList()
    private var sourceMotionVideo: File? = null
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
        val exposure = exifOf(ExifInterface.TAG_EXPOSURE_TIME).toDoubleOrNull()
            ?.let { if (it >= 1) "%.0fs".format(it) else "1/%.0f".format(1 / it) }
            .orEmpty()
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
            borderRatio = 0.045f
        )
        FrameTemplate.DARK_BAR -> renderFramed(
            source, fields, state,
            Palette(
                hex("#0B0C0E"), hex("#F4F5F7"),
                hex("#9BA1A9"), hex("#26282C")
            ),
            borderRatio = 0.045f
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
        borderRatio: Float
    ): Bitmap {
        val w = source.width
        val h = source.height
        val border = (w * borderRatio).toInt().coerceAtLeast(16)
        val barH = (w * 0.155f).toInt().coerceAtLeast(110)
        val outW = w + border * 2
        val outH = border + h + barH
        val result = createBitmap(outW, outH)
        val canvas = Canvas(result)
        canvas.drawColor(palette.bg)

        drawPhoto(canvas, source, border.toFloat(), border.toFloat(), state.rounded, w * 0.025f)

        val padX = border + w * 0.045f
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
        if (modelText.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.fg
                typeface = fontMedium
                textSize = barH * 0.26f
            }
            fitTextSize(paint, modelText, outW - padX - cursor - w * 0.02f, barH * 0.26f, barH * 0.16f)
            canvas.drawText(modelText, cursor, baselineFor(paint, line1), paint)
        }
        val custom = state.customText.trim()
        if (custom.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                typeface = fontRegular
                textSize = barH * 0.20f
                textAlign = Paint.Align.RIGHT
            }
            fitTextSize(paint, custom, outW - padX * 2, barH * 0.20f, barH * 0.13f)
            canvas.drawText(custom, outW - padX, baselineFor(paint, line1), paint)
        }
        if (params.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                typeface = fontRegular
                textSize = barH * 0.19f
            }
            fitTextSize(paint, params, outW - padX * 2, barH * 0.19f, barH * 0.12f)
            canvas.drawText(params, padX, baselineFor(paint, line2), paint)
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
        val marginX = (w * 0.085f).toInt().coerceAtLeast(24)
        val marginTop = (w * 0.085f).toInt().coerceAtLeast(24)
        val bottomH = (w * 0.20f).toInt().coerceAtLeast(72)
        val outW = w + marginX * 2
        val outH = marginTop + h + bottomH
        val result = createBitmap(outW, outH)
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
        if (modelText.isNotEmpty()) firstLineW += modelPaint.measureText(modelText)
        val line1Y = marginTop + h + bottomH * 0.38f
        val line2Y = marginTop + h + bottomH * 0.74f
        var x = (outW - firstLineW) / 2f
        if (state.keepLogo) {
            drawBrand(canvas, mark, x, line1Y - brandH / 2f, brandH, darkBg = false)
            x += brandLogoWidth(mark, brandH) + w * 0.02f
        }
        if (modelText.isNotEmpty()) {
            fitTextSize(modelPaint, modelText, outW * 0.8f, bottomH * 0.22f, bottomH * 0.14f)
            canvas.drawText(modelText, x, baselineFor(modelPaint, line1Y), modelPaint)
        }
        val custom = state.customText.trim()
        val second = listOf(params, custom).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (second.isNotEmpty()) {
            paramPaint.textAlign = Paint.Align.CENTER
            fitTextSize(paramPaint, second, outW * 0.82f, bottomH * 0.17f, bottomH * 0.11f)
            canvas.drawText(second, outW / 2f, baselineFor(paramPaint, line2Y), paramPaint)
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
        val barH = (w * 0.17f).toInt().coerceAtLeast(120)
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
            fitTextSize(modelPaint, modelText, w - textX - padX, barH * 0.28f, barH * 0.16f)
            canvas.drawText(modelText, textX, baselineFor(modelPaint, line1), modelPaint)
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
            fitTextSize(paramPaint, second, w - textX - padX, barH * 0.18f, barH * 0.11f)
            canvas.drawText(second, textX, baselineFor(paramPaint, line2), paramPaint)
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
            fitTextSize(modelPaint, modelText, w - padX - cursor, modelPaint.textSize, modelPaint.textSize * 0.6f)
            canvas.drawText(modelText, cursor, baseline1, modelPaint)
        }
        val second = listOf(params, custom).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (second.isNotEmpty()) {
            fitTextSize(paramPaint, second, w - padX * 2, paramPaint.textSize, paramPaint.textSize * 0.7f)
            canvas.drawText(second, padX, baseline2, paramPaint)
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

    /** 自适应字号：先按基准字号量宽，超出则等比缩小（不低于 minSize），避免溢出截断 */
    private fun fitTextSize(paint: Paint, text: String, maxWidth: Float, baseSize: Float, minSize: Float) {
        paint.textSize = baseSize
        if (maxWidth <= 0f) return
        val measured = paint.measureText(text)
        if (measured <= maxWidth) return
        paint.textSize = (baseSize * maxWidth / measured).coerceAtLeast(minSize)
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

    /** 导出：全尺寸渲染 → 普通照片落盘（保留 EXIF）；实况图提取视频重新合成 */
    fun export() {
        val state = _state.value
        val sourceUri = state.sourceUri ?: return
        if (state.exporting) return
        val src = sourceBitmap
        if (src == null) {
            // 源图未加载成功（解码失败等）——明确提示而非 NPE 出 "导出失败：null"
            _state.update { it.copy(message = "图片尚未加载成功，请重新选择或更换图片") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(exporting = true, message = null) }
            try {
                val renderedFile = withContext(Dispatchers.IO) {
                    // 用发起时捕获的局部引用：导出期间用户重新选择会置空字段，
                    // 此处再用 sourceBitmap!! 会 NPE
                    val bitmap = renderFrame(src, state.template, state.fields, state)
                    File.createTempFile("exifframe", ".jpg", context.cacheDir).apply {
                        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        bitmap.recycle()
                    }
                }
                val motionVideo = sourceMotionVideo
                if (motionVideo != null) {
                    // 实况图：画框静态图 + 原视频重新合成（视频内原封面帧时间戳保持不变）。
                    // exifSourceUri = 原图：画框成品同样保留原拍摄信息
                    val result = MotionPhotoComposer.compose(
                        context = context,
                        imageUri = Uri.fromFile(renderedFile),
                        videoUri = Uri.fromFile(motionVideo),
                        exifSourceUri = sourceUri,
                    )
                    MotionPhotoComposer.saveToGallery(context, result)
                    AppLog.i("exifframe", "实况画框已导出：${result.displayName}")
                } else {
                    saveStill(renderedFile, sourceUri)
                    AppLog.i("exifframe", "画框照片已导出")
                }
                renderedFile.delete()
                _state.update {
                    it.copy(exporting = false, success = true, message = "已保存到相册（DCIM/Imagedge）")
                }
                haptics.thud()
            } catch (e: Exception) {
                AppLog.w("exifframe", "导出失败：${e.message}")
                _state.update { it.copy(exporting = false, message = "导出失败：${e.message}") }
                haptics.double()
            }
        }
    }

    /**
     * 普通照片落盘：MediaStore DCIM/Imagedge。
     *
     * 修复 v1 的三处缺陷：文件名拼写（IMGDEGE → IMAGEDGE）、丢 EXIF（现在复制拍摄参数与
     * 时间）、落盘缺 `IS_PENDING`/`DATE_TAKEN`（相册会看到半成品，且按保存时间而非拍摄时间排序）。
     */
    private fun saveStill(rendered: File, sourceUri: Uri) {
        val resolver = context.contentResolver
        val sourceExif = runCatching {
            resolver.openFileDescriptor(sourceUri, "r")?.use { ExifInterface(it.fileDescriptor) }
        }.getOrNull()
        val dateMillis = runCatching {
            (sourceExif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: sourceExif?.getAttribute(ExifInterface.TAG_DATETIME))?.let(::parseExifDate)
        }.getOrNull()

        // EXIF 复制在缓存文件上完成（MediaStore 目标流不可随机读写）
        runCatching {
            val dst = ExifInterface(rendered.absolutePath)
            sourceExif?.let { src ->
                for (tag in COPY_EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        }.onFailure { AppLog.w("exifframe", "EXIF 复制失败（成品仍可用）：${it.message}") }

        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                "IMAGEDGE_${System.currentTimeMillis()}.jpg"
            )
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
                rendered.inputStream().use { it.copyTo(out) }
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
    }

    /** EXIF 时间格式 `yyyy:MM:dd HH:mm:ss` → 毫秒时间戳 */
    private fun parseExifDate(value: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .apply { isLenient = true }
            .parse(value)
            ?.time
    }.getOrNull()

    /** 回初始态（结果页「继续」） */
    fun reset() {
        sourceBitmap = null
        sourceMotionVideo = null
        sourceBrand = null
        pngCache.clear()
        _state.update { ExifFrameState() }
    }

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
}
