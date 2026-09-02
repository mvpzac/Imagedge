package com.imagedge.camera.feature.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.image.ImagePipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * 基础调整（曝光 / 对比度 / 饱和度 / 色温 / 旋转）。
 *
 * 基于 `:image` 的非破坏性管线：界面只改 EditStep 列表，
 * 预览走降采样渲染，确认后才渲染全尺寸——大图也不会卡顿。
 *
 * alpha 范围：先打通「调整 → 分享」链路；保存到相册、裁剪、批处理在后续迭代补齐。
 */
/** 四项调整的当前取值（均为 -1..1，0 表示未调整） */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f
)

@OptIn(FlowPreview::class)
@HiltViewModel
class BasicEditViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private var source: Bitmap? = null

    private val _steps = MutableStateFlow<List<EditStep>>(emptyList())
    val steps: StateFlow<List<EditStep>> = _steps.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 渲染完成的成品 Uri（供分享） */
    private val _result = MutableStateFlow<Uri?>(null)
    val result: StateFlow<Uri?> = _result.asStateFlow()

    init {
        // 参数变化后延迟渲染：拖动滑块时避免每一帧都重绘
        viewModelScope.launch {
            _steps
                .debounce(120L)
                .collectLatest { renderPreview() }
        }
    }

    /** 载入待编辑照片 */
    fun load(uri: Uri) {
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                val bmp = decode(uri, MAX_EDIT_EDGE)
                source = bmp
                _preview.value = bmp
            }
            _loading.value = false
        }
    }

    /** 调整某一项（同类只保留最新值） */
    fun adjust(step: EditStep) {
        val pipeline = ImagePipeline(_steps.value).replacingSameKind(step)
        _steps.value = pipeline.steps
    }

    /** 各类调整的当前取值（供滑块回显，由 steps 派生，避免两份状态） */
    val adjustments: StateFlow<Adjustments> = _steps
        .map { steps ->
            Adjustments(
                brightness = steps.filterIsInstance<EditStep.Brightness>().lastOrNull()?.value ?: 0f,
                contrast = steps.filterIsInstance<EditStep.Contrast>().lastOrNull()?.value ?: 0f,
                saturation = steps.filterIsInstance<EditStep.Saturation>().lastOrNull()?.value ?: 0f,
                temperature = steps.filterIsInstance<EditStep.Temperature>().lastOrNull()?.value ?: 0f
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Adjustments())

    fun rotate90() {
        val pipeline = ImagePipeline(_steps.value)
            .withStep(EditStep.Rotate(90f))
        _steps.value = pipeline.steps
    }

    fun reset() {
        _steps.value = emptyList()
    }

    /** 渲染全尺寸成品并产出可分享 Uri */
    fun finish() {
        val src = source ?: return
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val rendered = ImagePipeline(_steps.value).render(src)
                try {
                    val file = File(context.cacheDir, EDIT_OUT_DIR).apply { mkdirs() }
                        .resolve("edit_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { out ->
                        rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.sharefileprovider",
                        file
                    )
                } finally {
                    if (rendered !== src) rendered.recycle()
                }
            }.onSuccess { _result.value = it }
                .onFailure { _result.value = null }
            _busy.value = false
        }
    }

    private suspend fun renderPreview() {
        val src = source ?: return
        val rendered = runCatching {
            ImagePipeline(_steps.value).renderPreview(src, PREVIEW_EDGE)
        }.getOrNull() ?: return
        _preview.value = rendered
    }

    /**
     * 按目标长边解码（避免把 6000px 原图整个读进内存）。
     *
     * 注意 ImageDecoder 的回调签名是 `(decoder, info, source)`，
     * 尺寸要从 `info.size` 取——不是直接给宽高。
     */
    private fun decode(uri: Uri, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = (maxEdge.toFloat() / maxOf(width, height)).coerceAtMost(1f)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    }

    /** 清理渲染产生的临时文件（页面退出时） */
    fun clearOutputs() {
        runCatching {
            File(context.cacheDir, EDIT_OUT_DIR).deleteRecursively()
        }
    }

    override fun onCleared() {
        super.onCleared()
        source?.recycle()
        source = null
        clearOutputs()
    }

    private companion object {
        /** 编辑时的源图上限：全分辨率 RAW 内嵌 JPEG 太大，编辑场景没必要 */
        const val MAX_EDIT_EDGE = 2560

        /** 预览长边 */
        const val PREVIEW_EDGE = 1080

        const val EDIT_OUT_DIR = "edit_output"
    }
}
