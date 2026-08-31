package com.imagedge.camera.feature.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.data.lut.UserLutStore
import com.imagedge.camera.lut.CubeLut
import com.imagedge.camera.lut.CubeLutParser
import com.imagedge.camera.lut.LutProcessor
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
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 相册编辑（LUT 调色）——内置程序化预设 + 导入 .cube；
 *              CPU 三线性插值（lut 模块），强度可调，导出 JPEG。
 *     version: 1.0
 * </pre>
 */

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
 * 从 2048 下调到 1600：像素量减少 39%，配合 [LutEditViewModel] 里的缓冲复用，
 * 单次滤镜应用的堆峰值从约 120MB 压到约 30MB —— 连续切换滤镜不再逼近
 * 大堆应用的 OOM 阈值。1600px 对编辑预览与导出（JPEG 95）仍完全够用。
 */
private const val LUT_DECODE_MAX_DIM = 1600

/**
 * 交互式预览的处理上限（最长边，px）。
 *
 * 预览/拖强度滑条在 [LUT_PREVIEW_MAX_DIM] 上跑，像素量约为全分辨率
 * （[LUT_DECODE_MAX_DIM]）的 1/6，单次滤镜应用从 1~2s 降到数百毫秒，拖动滑条
 * 明显跟手；导出（[LutEditViewModel.save]）时再按全分辨率重算，不牺牲成品清晰度。
 */
private const val LUT_PREVIEW_MAX_DIM = 640

data class LutEditState(
    val original: Bitmap? = null,
    val filtered: Bitmap? = null,
    val selectedKey: String = FILTER_NONE,
    val strength: Int = 80,
    val processing: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false
) {
    val hasImage: Boolean get() = original != null
}

@HiltViewModel
class LutEditViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val processor: LutProcessor,
    private val userLutStore: UserLutStore,
    private val snackbarController: SnackbarController
) : ViewModel() {

    private val _state = MutableStateFlow(LutEditState())
    val state: StateFlow<LutEditState> = _state.asStateFlow()

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

    /**
     * 预览处理源（降采样副本）。交互式滤镜/强度调整在它上面跑，比全分辨率快约 6 倍；
     * 全分辨率 [LutEditState.original] 仅用于展示与导出重算。
     */
    @Volatile
    private var previewSource: Bitmap? = null

    init {
        loadBuiltins()
        loadUserLuts()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: return@launch
                val bitmap = decodeSampled(bytes) ?: run {
                    _state.update { it.copy(message = "图片解码失败") }
                    return@launch
                }
                previewSource = createPreviewSource(bitmap)
                _state.update { LutEditState(original = bitmap, strength = it.strength) }
                applyCurrentFilter()
            } catch (e: Exception) {
                AppLog.w("lut", "选图失败：${e.message}")
                _state.update { it.copy(message = "选图失败：${e.message}") }
            }
        }
    }

    /** 切换滤镜并应用 */
    fun selectFilter(key: String) {
        _state.update { it.copy(selectedKey = key, message = null) }
        applyCurrentFilter()
    }

    /** 强度变化（防抖：变化停止 200ms 后应用） */
    fun setStrength(value: Int) {
        _state.update { it.copy(strength = value) }
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
        applyJob?.cancel()
        if (lut == null) {
            _state.update { it.copy(filtered = processSource, processing = false) }
            return
        }
        _state.update { it.copy(processing = true) }
        applyJob = viewModelScope.launch(Dispatchers.Default) {
            // 处理全程无挂起点，cancel() 停不住已在跑的任务；用互斥串行化，
            // 避免新旧任务并发读写复用的像素缓冲造成画面错乱
            applyMutex.withLock {
                if (!isActive) return@launch
                try {
                    val w = processSource.width
                    val h = processSource.height
                    // 复用转换缓冲（P1-9）：强度滑条每 200ms 防抖后都会全图重算，
                    // 原先每次新建 3 个大数组（Int 24MB + Byte 24MB + Int 24MB），
                    // 连续拖动滑条 = GC 疯狂抖动 + 短时峰值逼近 120MB。尺寸不变时直接复用。
                    val size = w * h
                    val pixels = reuseIntArray(convPixels, size) ?: IntArray(size).also { convPixels = it }
                    processSource.getPixels(pixels, 0, w, 0, 0, w, h)
                    val rgba = reuseByteArray(convRgba, size * 4) ?: ByteArray(size * 4).also { convRgba = it }
                    for (i in 0 until size) {
                        val px = pixels[i]
                        rgba[i * 4] = (px shr 16 and 0xFF).toByte()
                        rgba[i * 4 + 1] = (px shr 8 and 0xFF).toByte()
                        rgba[i * 4 + 2] = (px and 0xFF).toByte()
                        rgba[i * 4 + 3] = (px shr 24 and 0xFF).toByte()
                    }
                    val out = processor.apply(rgba, w, h, lut.data, lut.size, _state.value.strength)
                    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val outPixels = reuseIntArray(convOutPixels, size) ?: IntArray(size).also { convOutPixels = it }
                    for (i in 0 until size) {
                        outPixels[i] = (out[i * 4 + 3].toInt() and 0xFF) shl 24 or
                            ((out[i * 4].toInt() and 0xFF) shl 16) or
                            ((out[i * 4 + 1].toInt() and 0xFF) shl 8) or
                            (out[i * 4 + 2].toInt() and 0xFF)
                    }
                    result.setPixels(outPixels, 0, w, 0, 0, w, h)
                    // 被取消的旧任务不得落结果：否则会把新滤镜的 selectedKey/状态覆盖回去
                    if (isActive) {
                        _state.update { it.copy(filtered = result, processing = false) }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("lut", "滤镜处理失败：${e.message}")
                    _state.update { it.copy(processing = false, message = "处理失败：${e.message}") }
                }
            }
        }
    }

    /** 取尺寸匹配的复用缓冲；尺寸不符（换图）则返回 null 由调用方重建 */
    private fun reuseIntArray(buffer: IntArray?, expectedSize: Int): IntArray? =
        buffer?.takeIf { it.size == expectedSize }

    private fun reuseByteArray(buffer: ByteArray?, expectedSize: Int): ByteArray? =
        buffer?.takeIf { it.size == expectedSize }

    /** 导出 JPEG：优先自定义目录（SAF），否则 DCIM/Imagedge */
    fun save() {
        val fullOriginal = _state.value.original ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val treeUriStr = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                    .getString("download_tree_uri", null)
                val name = "LUT_${System.currentTimeMillis()}.jpg"
                val uri = if (treeUriStr != null) {
                    val treeUri = Uri.parse(treeUriStr)
                    val dirUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    android.provider.DocumentsContract.createDocument(resolver, dirUri, "image/jpeg", name)
                } else {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            "${android.os.Environment.DIRECTORY_DCIM}/Imagedge"
                        )
                    }
                    resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } ?: throw IllegalStateException("创建文件失败")
                // 预览的 filtered 是降采样位图，直接保存会糊：
                // 用当前 LUT + 强度在全分辨率原图上重算，保证成品清晰度
                val option = _filters.value.firstOrNull { it.key == _state.value.selectedKey }
                val lut = option?.lut ?: option?.key?.let { lutCache[it] }
                val exportBitmap = if (lut == null) {
                    fullOriginal
                } else {
                    applyLutToBitmap(fullOriginal, lut, _state.value.strength)
                }
                val outputStream = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("无法打开目标文件的输出流")
                outputStream.use {
                    exportBitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
                _state.update { it.copy(saved = true, message = "已保存：$name") }
                // 成功用轻提示（2~3s 自动消失）；失败则留在页面上的红色文字里，
                // 因为失败信息需要用户看清并据此处理，不该一闪而过。
                snackbarController.show("已保存到相册：$name")
            } catch (e: Exception) {
                _state.update { it.copy(message = "保存失败：${e.message}") }
            }
        }
    }

    /**
     * 采样解码：最长边 ≤ [maxDim]（默认 [LUT_DECODE_MAX_DIM]）。
     *
     * 顺带修正原实现的采样公式 bug：`while (w/(sample*2) >= maxDim)` 实际等价于
     * `w/sample >= 2*maxDim`，6000px 源图在 maxDim=2048 时只会采到 3000px ——
     * 超出目标上限 46%。改为直接比较 `max(w,h)/sample > maxDim`，行为与
     * PhotoViewerViewModel 的采样保持一致。
     */
    private fun decodeSampled(bytes: ByteArray, maxDim: Int = LUT_DECODE_MAX_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 预览处理源：最长边缩到 [LUT_PREVIEW_MAX_DIM] 以内（交互式处理提速约 6 倍） */
    private fun createPreviewSource(full: Bitmap): Bitmap {
        val maxSide = maxOf(full.width, full.height)
        if (maxSide <= LUT_PREVIEW_MAX_DIM) return full
        val scale = LUT_PREVIEW_MAX_DIM.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            full,
            (full.width * scale).toInt().coerceAtLeast(1),
            (full.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * 全分辨率应用 LUT（导出重算专用）。
     * 独立分配缓冲而非复用 [convPixels] 等：导出在 IO 线程，可能与预览处理
     * （Default 线程 + 互斥锁）并发，复用同一组缓冲会互相践踏。
     */
    private suspend fun applyLutToBitmap(src: Bitmap, lut: CubeLut, strength: Int): Bitmap {
        val w = src.width
        val h = src.height
        val size = w * h
        val pixels = IntArray(size)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgba = ByteArray(size * 4)
        for (i in 0 until size) {
            val px = pixels[i]
            rgba[i * 4] = (px shr 16 and 0xFF).toByte()
            rgba[i * 4 + 1] = (px shr 8 and 0xFF).toByte()
            rgba[i * 4 + 2] = (px and 0xFF).toByte()
            rgba[i * 4 + 3] = (px shr 24 and 0xFF).toByte()
        }
        val out = processor.apply(rgba, w, h, lut.data, lut.size, strength)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(size)
        for (i in 0 until size) {
            outPixels[i] = (out[i * 4 + 3].toInt() and 0xFF) shl 24 or
                ((out[i * 4].toInt() and 0xFF) shl 16) or
                ((out[i * 4 + 1].toInt() and 0xFF) shl 8) or
                (out[i * 4 + 2].toInt() and 0xFF)
        }
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

}
