package com.imagedge.camera.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.data.lut.UserLutStore
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.data.model.ConnectionState
import com.imagedge.camera.ui.feedback.Haptics
import com.imagedge.camera.ui.theme.ThemeController
import com.imagedge.camera.ui.theme.ThemeMode
import com.imagedge.camera.data.guidance.GuidanceStore
import com.imagedge.camera.data.transfer.DownloadLocation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 设置页——主题外观（持久化）、下载信息、关于。
 *              手动 IP 连接原来在这里有一份**没人调用**的实现（批次 D 删除）：
 *              设置页从来没有连接 UI，那条路后来收进了连接向导的「其他连接方式」。
 *     version: 1.0
 * </pre>
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userLutStore: UserLutStore,
    val themeController: ThemeController,
    private val haptics: Haptics,
    private val guidanceStore: GuidanceStore
) : ViewModel() {

    // ── LUT 文件管理 ──
    private val _userLuts = MutableStateFlow<List<String>>(emptyList())
    val userLuts: StateFlow<List<String>> = _userLuts.asStateFlow()

    /**
     * 重新打开新手引导（新手手册 §5：设置里的「使用帮助」要能召回已关掉的指导）。
     *
     * 这里不自己发通知也不写文案——界面负责措辞，这里只负责那一次存储动作。
     */
    fun reopenGuides() {
        guidanceStore.reopenGuides()
        haptics.tick()
    }

    private val _lutMessage = MutableStateFlow<String?>(null)
    val lutMessage: StateFlow<String?> = _lutMessage.asStateFlow()

    /**
     * 导入后待用户声明适用类型的 LUT：文件名 → 按文件名推断的推荐类型。
     * LUT 的输入曲线分三类（普通照片 / S-Log2 / S-Log3），套错会让画面发灰或过冲，
     * 而 .cube 文件本身不记录这件事，所以必须由导入者声明。
     */
    private val _pendingLutType = MutableStateFlow<Pair<String, LutType>?>(null)
    val pendingLutType: StateFlow<Pair<String, LutType>?> = _pendingLutType.asStateFlow()

    fun refreshLuts() {
        _userLuts.value = userLutStore.list()
    }

    /** 确认导入 LUT 的适用类型 */
    fun confirmLutType(type: LutType) {
        val name = _pendingLutType.value?.first ?: return
        userLutStore.setType(name, type)
        _pendingLutType.value = null
        _lutMessage.value = "已导入 $name（${type.name}）"
        refreshLuts()
    }

    /** 跳过声明：沿用按文件名推断的类型 */
    fun dismissLutType() {
        val (name, suggested) = _pendingLutType.value ?: return
        userLutStore.setType(name, suggested)
        _pendingLutType.value = null
        _lutMessage.value = "已导入 $name（按名称推断为 ${suggested.name}）"
        refreshLuts()
    }

    /** 已导入 LUT 的适用类型（列表展示用） */
    fun lutTypeOf(name: String): LutType = userLutStore.typeOf(name)

    fun importLut(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = userLutStore.import(uri)
            result.onSuccess { name ->
                // 先按文件名推断一个推荐值，再由用户在弹窗里确认/修正
                _pendingLutType.value = name to userLutStore.typeOf(name)
            }.onFailure { _lutMessage.value = "导入失败：${it.message}" }
            refreshLuts()
        }
    }

    fun deleteLut(name: String) {
        userLutStore.delete(name)
        _lutMessage.value = "已删除 $name"
        refreshLuts()
    }

    fun exportLut(name: String, target: android.net.Uri) {
        viewModelScope.launch {
            runCatching { userLutStore.exportTo(name, target) }
                .onSuccess { _lutMessage.value = "已导出 $name" }
                .onFailure { _lutMessage.value = "导出失败：${it.message}" }
        }
    }

    /**
     * 自定义下载目录（SAF tree uri；null = 默认 DCIM/Imagedge）。
     * 键与措辞归 `DownloadLocation`——写盘的相机仓库和照片编辑器读的是同一个地方，
     * 这里再存一份键就会漂（四处硬写同一个字符串就是上一版的样子）。
     */
    val downloadTreeUri: String? get() = DownloadLocation.treeUri(context)

    fun onDirPicked(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        DownloadLocation.setTreeUri(context, uri)
        _downloadDirLabel.value = DownloadLocation.label(context)
    }

    fun restoreDefaultDir() {
        DownloadLocation.clearTreeUri(context)
        _downloadDirLabel.value = DownloadLocation.label(context)
    }

    private val _downloadDirLabel = MutableStateFlow(DownloadLocation.label(context))
    val downloadDirLabel: StateFlow<String> = _downloadDirLabel.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = themeController.mode

    fun setThemeMode(mode: ThemeMode) = themeController.setMode(mode)

    val hapticsEnabled: StateFlow<Boolean> = haptics.enabled
    fun setHapticsEnabled(enabled: Boolean) {
        // 先持久化再震动：开→关时震动会被 Haptics 的应用开关闸拦截（符合预期，关闭后不再震）；不要调换顺序
        haptics.setEnabled(enabled)
        haptics.click()
    }
}
