package com.imagedge.camera.feature.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.R
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
 * 边框水印 ViewModel（批次 C，对标 copicseal/可图匠的模板化思路，Apache-2.0）。
 *
 * 流程：选照片 → EXIF 自动读取（相机型号/等效焦距/快门/ISO/光圈，读不到留空可手动改）
 * → 选模板实时渲染预览 → 导出（普通照片直接画框落盘；实况图提取视频后与画框图重新合成）。
 *
 * 渲染要点（真机迭代沉淀）：
 * - **解码主路径 = fd + seekTo(0) 复位**：bounds 探测与正式解码共用同一 FileDescriptor，
 *   第二次读取前必须 seekTo(0)，否则从错误偏移解码（大图只加载顶部一行的根因）。
 * - **EXIF 旋转应用**：BitmapFactory 不自动应用 Orientation，竖拍照片需按
 *   rotationDegrees 手动旋转（否则竖屏被渲染成横向）。
 * - **品牌 LOGO = 商标图片资源**：simple-icons（CC0）VectorDrawable 为主 + Wikimedia
 *   PNG 兜底；缺失品牌（PENTAX 等）回退文字字标。
 * - **拍立得模板**：四周白纸 + 照片悬浮投影（BlurMaskFilter 软渲染）+ 底部信息。
 */
@HiltViewModel
class ExifFrameViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** 内置模板（4 套） */
    enum class FrameTemplate(val label: String) {
        CLASSIC_WHITE("经典白边"),
        DARK("暗色底栏"),
        POLAROID("白框悬浮"),
        MINIMAL("极简单行"),
    }

    /** 单个可编辑字段（EXIF 预填 + 手动覆盖） */
    data class FrameField(val label: String, val value: String)

    data class ExifFrameState(
        val sourceUri: Uri? = null,
        /** 源是否为实况图（导出时需重组视频） */
        val isMotion: Boolean = false,
        val template: FrameTemplate = FrameTemplate.CLASSIC_WHITE,
        /** 渲染后的预览图（降采样，所见即所得） */
        val preview: Bitmap? = null,
        val rendering: Boolean = false,
        val fields: List<FrameField> = emptyList(),
        val exporting: Boolean = false,
        val message: String? = null,
        val success: Boolean = false,
    )

    private val _state = MutableStateFlow(ExifFrameState())
    val state: StateFlow<ExifFrameState> = _state.asStateFlow()

    /** 基准原图（预览/导出共用，1600px 长边降采样） */
    private var sourceBitmap: Bitmap? = null
    private var sourceMotionVideo: File? = null
    /** 从 EXIF Make/Model 检测出的相机品牌（渲染商标图片/字标） */
    private var sourceBrand: BrandMark? = null
    /** assets 内品牌 PNG 的解码缓存（避免导出时重复解码） */
    private val pngCache = HashMap<String, Bitmap>()

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
     * 图片选择回调：**两级加载**（真机反馈大图导入慢的修复）——
     * 1. 快速通道：长边 ~800px 小图立即解码渲染出预览（<1s，用户马上看到东西）；
     * 2. 完整通道：EXIF 读取 + 1600px 基准图 + 实况检测，完成后替换为高清渲染。
     */
    fun onImagePicked(uri: Uri) {
        // 诊断日志（w 级保证可见）：记录选择的 URI —— openInputStream 返回 null 时
        // 需要 URI 的 scheme/authority 定位 provider 问题（MIUI 相册私有 provider 高危）
        AppLog.w("exifframe", "picked: $uri")
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
                    // 仅当完整加载尚未接管（preview 仍是本 quick 渲染的前置状态）时显示
                    if (_state.value.sourceUri == uri && sourceBitmap == null) {
                        val rendered = renderFrame(quick, _state.value.template, _state.value.fields)
                        _state.update { s ->
                            if (s.sourceUri == uri && s.preview == null) s.copy(preview = rendered) else s
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
     * 流式采样解码，**三级降级**（对标成熟相册 App 的通用做法）：
     * 1. fd 路径（**主路径**）：openFileDescriptor + seekTo(0) 复位 + BitmapFactory 采样，
     *    解码后按 EXIF rotation 旋转（照片选择器 URI 的 openInputStream 在部分 provider
     *    上恒为 null，fd 更稳；seekTo(0) 修复 bounds 探测与正式解码共用 fd 的偏移错位）；
     * 2. 流路径（openInputStream）：部分 provider 的 fd 读取有兼容问题时的兜底；
     * 3. ImageDecoder：HEIF/HDR/动图等 BitmapFactory 解不了的格式（自动应用 EXIF 旋转）。
     * 每级失败都打日志，最终失败时可从日志定位图片格式与 provider。
     */
    private fun decodeScaled(uri: Uri, targetLong: Int): Bitmap? {
        // ── 1. fd 路径（主路径）──
        val fromFd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // 首次探测尺寸（inJustDecodeBounds 只读文件头，会使 fd 偏移前移）
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetLong) sample *= 2
                // 关键：正式解码前必须把 fd 偏移复位到 0，否则从错位偏移解码
                // —— 大图「只加载顶部一小行」的根因
                android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                // 读旋转角度（BitmapFactory 不自动应用 Orientation，竖拍必旋）
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
                // 不能用返回值判断是否成功——此前用「返回值 != null」导致 streamOpened 永假、
                // 流路径兜底形同虚设（fd 失败后直接跳到 ImageDecoder，偶发解码差异）
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
        AppLog.w(
            "exifframe",
            "EXIF 结果：make=$make model=$model focal=$focal exposure=$exposure iso=$iso f=$fNumber"
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
                )
            )
        }
    }

    fun setTemplate(template: FrameTemplate) {
        _state.update { it.copy(template = template) }
        renderPreview()
    }

    private var fieldDebounceJob: kotlinx.coroutines.Job? = null

    /** 字段编辑：300ms 防抖后重渲染——逐字全量渲染 1600px 位图会造成连续卡顿 */
    fun setField(label: String, value: String) {
        _state.update { s ->
            s.copy(fields = s.fields.map { if (it.label == label) it.copy(value = value) else it })
        }
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
                renderFrame(source, state.template, state.fields)
            }.onFailure { e ->
                // 排查盲区：此前 getOrNull 吞掉异常且 preview 被清成 null，
                // 界面既不显示图也无提示——「大图不能完整显示」的疑似根因之一
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

    /** 渲染入口：拍立得（白框悬浮）走独立布局，其余走底部信息栏布局 */
    private fun renderFrame(source: Bitmap, template: FrameTemplate, fields: List<FrameField>): Bitmap =
        if (template == FrameTemplate.POLAROID) renderPolaroid(source, fields)
        else renderBar(source, template, fields)

    /**
     * 白框悬浮模板（替代原拍立得）：四周白色相纸留白，照片悬浮于纸上
     * （照片下方投影 = 照片形状的模糊阴影，软件渲染用 BlurMaskFilter），
     * 底部信息区显示「品牌 LOGO · 型号 · 参数」。
     */
    private fun renderPolaroid(source: Bitmap, fields: List<FrameField>): Bitmap {
        val w = source.width
        val h = source.height
        val marginX = (w * 0.09f).toInt().coerceAtLeast(28)   // 左右白边
        val marginTop = (w * 0.07f).toInt().coerceAtLeast(24) // 顶部白边
        val bottomH = (w * 0.16f).toInt().coerceAtLeast(56)   // 底部信息区（原 0.24 过高，真机反馈调低）
        val outW = w + marginX * 2
        val outH = marginTop + h + bottomH
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        // 悬浮投影（真机反馈「照片周围加阴影」）：偏移更大、模糊更大、透明度更深，
        // 让照片明显「浮」在纸上。软件 Canvas 不支持 Paint.setShadowLayer，
        // 阴影 = 先画一张照片形状的模糊黑色块（偏移），再画照片本体。
        val photoLeft = marginX.toFloat()
        val photoTop = marginTop.toFloat()
        val blur = (w * 0.030f).coerceAtLeast(8f)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            color = Color.argb(150, 0, 0, 0)
        }
        val shadowDx = w * 0.020f
        val shadowDy = h * 0.030f
        canvas.drawBitmap(source, photoLeft + shadowDx, photoTop + shadowDy, shadowPaint)
        canvas.drawBitmap(source, photoLeft, photoTop, null)

        // 底部信息：品牌 LOGO（图片）+ 型号 + 参数，单行居中自适应防溢出
        val modelText = fields.firstOrNull()?.value.orEmpty().trim()
        val params = fields.drop(1).filter { it.value.isNotEmpty() }
            .joinToString("   ") { displayValue(it) }
        val mark = sourceBrand ?: BrandMark("IMAGEDEGE", Color.argb(255, 0x33, 0x33, 0x33))
        val logoH = bottomH * 0.50f
        val paramTextSize = bottomH * 0.30f
        val gap = w * 0.05f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = paramTextSize
            color = Color.DKGRAY
        }
        val line = listOf(modelText, params).filter { it.isNotEmpty() }.joinToString("   ")
        val logoW = brandLogoWidth(mark, logoH)
        val totalW = logoW + (if (line.isEmpty()) 0f else gap + textPaint.measureText(line))
        val maxW = outW - w * 0.08f
        var scale = 1f
        while (totalW * scale > maxW && scale > 0.5f) scale -= 0.04f
        val centerY = photoTop + h + bottomH / 2f
        var x = (outW - totalW * scale) / 2f
        // LOGO 与文字垂直居中对齐：用 FontMetrics 精确计算 baseline，使文字视觉中心 = centerY
        drawBrand(canvas, mark, x, centerY - logoH * scale / 2f, logoH * scale, darkBg = false)
        x += logoW * scale + gap * scale
        if (line.isNotEmpty()) {
            textPaint.textSize = paramTextSize * scale
            val fm = textPaint.fontMetrics
            val baseline = centerY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(line, x, baseline, textPaint)
        }
        return result
    }

    /**
     * 底部信息栏模板（经典白边 / 暗色底栏 / 极简单行）：
     * 左区（品牌 LOGO 图片 + 型号）+ 右区（参数右对齐），整体自适应防重叠。
     */
    private fun renderBar(source: Bitmap, template: FrameTemplate, fields: List<FrameField>): Bitmap {
        val w = source.width
        val barH = when (template) {
            FrameTemplate.CLASSIC_WHITE, FrameTemplate.DARK -> (w * 0.10f).toInt().coerceAtLeast(80)
            FrameTemplate.MINIMAL -> (w * 0.07f).toInt().coerceAtLeast(60)
            FrameTemplate.POLAROID -> 0 // 不会走到
        }
        val result = Bitmap.createBitmap(w, source.height + barH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)

        val (bg, fg) = when (template) {
            FrameTemplate.CLASSIC_WHITE -> Color.WHITE to Color.BLACK
            FrameTemplate.DARK -> Color.BLACK to Color.WHITE
            FrameTemplate.MINIMAL -> Color.argb(150, 0, 0, 0) to Color.WHITE
            FrameTemplate.POLAROID -> Color.WHITE to Color.DKGRAY
        }
        canvas.drawRect(0f, source.height.toFloat(), w.toFloat(), result.height.toFloat(), Paint().apply { color = bg })
        val darkBg = template == FrameTemplate.DARK || template == FrameTemplate.MINIMAL

        val pad = w * 0.04f
        val textCenterY = source.height + barH / 2f
        val modelText = fields.firstOrNull()?.value.orEmpty().trim()
        val params = fields.drop(1).filter { it.value.isNotEmpty() }
            .joinToString("   ") { displayValue(it) }
        val mark = sourceBrand ?: BrandMark("IMAGEDEGE", Color.argb(255, 0x33, 0x33, 0x33))

        // 左区：品牌 LOGO 图片 + 型号；右区：参数
        val logoH = barH * 0.50f
        val logoW = brandLogoWidth(mark, logoH)
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = barH * 0.26f
        }
        val paramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; typeface = Typeface.MONOSPACE; textSize = barH * 0.28f
            textAlign = Paint.Align.RIGHT
        }
        val avail = w - pad * 2
        var scale = 1f
        val need = {
            logoW * scale + pad * 0.5f +
                (if (modelText.isEmpty()) 0f else modelPaint.measureText(modelText) + pad * 0.5f) +
                (if (params.isEmpty()) 0f else paramPaint.measureText(params))
        }
        while (need() > avail && scale > 0.6f) {
            scale -= 0.04f
            modelPaint.textSize = barH * 0.26f * scale
            paramPaint.textSize = barH * 0.28f * scale
        }
        var leftModel = modelText
        // 缩到底后仍溢出 → 左区省略号截断（保参数完整）
        if (need() > avail && leftModel.isNotEmpty()) {
            val budget = avail - logoW - pad * 0.5f -
                (if (params.isEmpty()) 0f else paramPaint.measureText(params) + pad * 0.5f)
            leftModel = ellipsize(modelPaint, leftModel, budget.coerceAtLeast(0f))
        }
        // 用 FontMetrics 精确计算 baseline：使文字视觉中心与 bar 中心 / LOGO 中心严格对齐
        val paramFm = paramPaint.fontMetrics
        val baseline = textCenterY - (paramFm.ascent + paramFm.descent) / 2f
        // LOGO 垂直居中于 bar 中心（之前是底对齐 baseline，导致 logo 比正文高 14% barH）
        drawBrand(canvas, mark, pad, textCenterY - logoH * scale / 2f, logoH * scale, darkBg = darkBg)
        val logoX = pad + logoW * scale + pad * 0.5f
        if (leftModel.isNotEmpty()) {
            canvas.drawText(leftModel, logoX, baseline, modelPaint)
        }
        if (params.isNotEmpty()) {
            canvas.drawText(params, w - pad, baseline, paramPaint)
        }
        return result
    }

    /** 字段显示值：等效焦距渲染为「等效50mm」而非裸 "50mm"（用户要求） */
    private fun displayValue(f: FrameField): String =
        if (f.label == "等效焦距" && f.value.isNotEmpty()) "等效${f.value}" else f.value

    /**
     * 品牌 LOGO 渲染：优先 assets/brand_logos 下的 PNG，缺失时回退特征文字字标。
     * - 透明底 PNG：深色底（DARK/MINIMAL）下用 SRC_IN 染白保证可见。
     * - badge = true（gopro 黑底 / realme 黄底）：官方徽章带纯色底，不参与染色，
     *   否则整块底色被染白导致与正文混淆。
     */
    private fun drawBrand(
        canvas: Canvas,
        mark: BrandMark,
        left: Float,
        top: Float,
        height: Float,
        darkBg: Boolean,
    ) {
        // 1. assets PNG（用户自维护，25 品牌统一格式）
        val png = mark.assetPng?.let(::pngBitmap)
        if (png != null) {
            val ratio = png.width.toFloat() / maxOf(1, png.height)
            var w = height * ratio
            if (w > height * 4.5f) w = height * 4.5f // 超宽字标限宽，防挤压正文
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (darkBg && !mark.badge) {
                p.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(png, null, RectF(left, top, left + w, top + height), p)
            return
        }
        // 2. 文字字标回退（PENTAX / 未知品牌）
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkBg) Color.WHITE else mark.color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = height * 1.2f
            letterSpacing = mark.spacing
        }
        canvas.drawText(mark.text, left, top + height * 0.88f, p)
    }

    /** 品牌 LOGO 的渲染宽度（与 [drawBrand] 的宽高比一致，供防重叠布局计算） */
    private fun brandLogoWidth(mark: BrandMark, height: Float): Float {
        val png = mark.assetPng?.let(::pngBitmap)
        if (png != null) {
            val ratio = png.width.toFloat() / maxOf(1, png.height)
            var w = height * ratio
            if (w > height * 4.5f) w = height * 4.5f
            return w
        }
        // 文字回退
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = height * 1.2f
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

    /** 省略号截断到指定宽度（二分近似：先粗裁再逐字回退） */
    private fun ellipsize(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) end -= (end / 8).coerceAtLeast(1)
        return text.take(end) + "…"
    }

    /** 导出：全尺寸渲染 → 普通照片落盘；实况图提取视频重新合成 */
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
                    val bitmap = renderFrame(src, state.template, state.fields)
                    File.createTempFile("exifframe", ".jpg", context.cacheDir).apply {
                        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    }
                }
                val motionVideo = sourceMotionVideo
                if (motionVideo != null) {
                    // 实况图：画框静态图 + 原视频重新合成（视频内原封面帧时间戳保持不变）。
                    // exifSourceUri = 原图：画框成品同样保留原拍摄信息（EXIF 保留需求）
                    val result = MotionPhotoComposer.compose(
                        context = context,
                        imageUri = Uri.fromFile(renderedFile),
                        videoUri = Uri.fromFile(motionVideo),
                        exifSourceUri = sourceUri,
                    )
                    MotionPhotoComposer.saveToGallery(context, result)
                    AppLog.i("exifframe", "实况画框已导出：${result.displayName}")
                } else {
                    saveStill(renderedFile)
                    AppLog.i("exifframe", "画框照片已导出")
                }
                renderedFile.delete()
                _state.update {
                    it.copy(exporting = false, success = true, message = "已保存到相册（DCIM/Imagedge）")
                }
            } catch (e: Exception) {
                AppLog.w("exifframe", "导出失败：${e.message}")
                _state.update { it.copy(exporting = false, message = "导出失败：${e.message}") }
            }
        }
    }

    /** 普通照片落盘：MediaStore DCIM/Imagedge */
    private fun saveStill(rendered: File) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "IMGDEGE_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_DCIM}/Imagedge"
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("创建相册条目失败")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                rendered.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入相册")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** 回初始态（结果页「继续」） */
    fun reset() {
        sourceBitmap = null
        sourceMotionVideo = null
        sourceBrand = null
        pngCache.clear()
        _state.update { ExifFrameState() }
    }
}
