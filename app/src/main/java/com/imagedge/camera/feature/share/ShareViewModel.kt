package com.imagedge.camera.feature.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.share.ExportConfig
import com.imagedge.camera.share.ExportFormat
import com.imagedge.camera.share.ExportManager
import com.imagedge.camera.share.ExportSize
import com.imagedge.camera.share.ExifPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 分享 / 导出（一站式闭环的最后一环）。
 *
 * 流程：选择配置 → [export] 生成副本 → 结果经 [shareEvent] 交给 UI 拉起系统分享面板。
 * ViewModel 不持有 Activity，也不直接 startActivity（避免泄漏与配置变更问题）。
 *
 * 默认配置按「发社交平台」这一最高频场景设定：
 * 1080px + JPEG + 仅清除位置——兼顾清晰度、体积与隐私。
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val exportManager = ExportManager(context)

    /** 待分享的源 Uri（在弹窗打开时设定） */
    private val pendingSources = mutableListOf<Uri>()

    private val _config = MutableStateFlow(
        ExportConfig(
            size = ExportSize.P1080,
            format = ExportFormat.JPEG,
            quality = 92,
            exif = ExifPolicy.STRIP_LOCATION
        )
    )
    val config: StateFlow<ExportConfig> = _config.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 导出完成、可拉起分享面板（一次性事件） */
    private val _shareEvent = MutableSharedFlow<SharePayload>(extraBufferCapacity = 1)
    val shareEvent: SharedFlow<SharePayload> = _shareEvent.asSharedFlow()

    /** 记录本次要分享的源（支持单张与多张） */
    fun prepare(uris: List<Uri>) {
        pendingSources.clear()
        pendingSources.addAll(uris)
    }

    fun setSize(size: ExportSize) {
        _config.value = _config.value.copy(size = size)
    }

    fun setFormat(format: ExportFormat) {
        _config.value = _config.value.copy(format = format)
    }

    fun setExif(policy: ExifPolicy) {
        _config.value = _config.value.copy(exif = policy)
    }

    fun setQuality(quality: Int) {
        _config.value = _config.value.copy(quality = quality.coerceIn(1, 100))
    }

    /** 按当前配置导出并触发分享 */
    fun export() {
        val sources = pendingSources.toList()
        if (sources.isEmpty()) return
        viewModelScope.launch {
            _exporting.value = true
            _error.value = null
            try {
                val uris = exportManager.exportAll(sources, _config.value)
                if (uris.isEmpty()) {
                    _error.value = "导出失败，请重试"
                } else {
                    _shareEvent.emit(SharePayload(uris, _config.value.format.mime))
                }
            } finally {
                _exporting.value = false
            }
        }
    }

    /** 清理导出的临时副本（页面退出时调用） */
    fun clearExports() {
        exportManager.clearExports()
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        clearExports()
    }
}

/** 导出结果：副本 Uri 列表 + 对应的 MIME */
data class SharePayload(
    val uris: List<Uri>,
    val mime: String
)
