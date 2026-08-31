package com.imagedge.camera.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.data.lut.UserLutStore
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.data.model.ConnectionState
import com.imagedge.camera.data.model.ConnectionStateHolder
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.ui.theme.BrandColor
import com.imagedge.camera.ui.theme.ThemeController
import com.imagedge.camera.ui.theme.ThemeMode
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
 *     desc   : 设置页——主题外观（持久化）、手动 IP 连接、下载信息、关于。
 *     version: 1.0
 * </pre>
 */
data class ManualConnectState(
    val connecting: Boolean = false,
    val message: String? = null,
    val connected: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CameraRepository,
    private val stateHolder: ConnectionStateHolder,
    private val userLutStore: UserLutStore,
    val themeController: ThemeController
) : ViewModel() {

    // ── LUT 文件管理 ──
    private val _userLuts = MutableStateFlow<List<String>>(emptyList())
    val userLuts: StateFlow<List<String>> = _userLuts.asStateFlow()

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

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 自定义下载目录（SAF tree uri；null = 默认 DCIM/Imagedge） */
    val downloadTreeUri: String? get() = prefs.getString(KEY_DOWNLOAD_TREE, null)

    fun onDirPicked(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs.edit().putString(KEY_DOWNLOAD_TREE, uri.toString()).apply()
        _downloadDirLabel.value = describeDir(uri.toString())
    }

    fun restoreDefaultDir() {
        prefs.edit().remove(KEY_DOWNLOAD_TREE).apply()
        _downloadDirLabel.value = DEFAULT_LABEL
    }

    private val _downloadDirLabel = MutableStateFlow(describeDir(downloadTreeUri))
    val downloadDirLabel: StateFlow<String> = _downloadDirLabel.asStateFlow()

    private fun describeDir(uriStr: String?): String {
        if (uriStr == null) return DEFAULT_LABEL
        return runCatching {
            "已选择：" + android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
        }.getOrDefault("已选择自定义目录")
    }

    companion object {
        private const val KEY_DOWNLOAD_TREE = "download_tree_uri"
        private const val DEFAULT_LABEL = "默认：DCIM/Imagedge（系统相册）"
    }

    val themeMode: StateFlow<ThemeMode> = themeController.mode
    val brandColor: StateFlow<BrandColor> = themeController.brandColor

    fun setThemeMode(mode: ThemeMode) = themeController.setMode(mode)

    fun setBrandColor(color: BrandColor) = themeController.setBrandColor(color)

    // ── Material You 动态取色（默认关闭，Android 12+ 才生效）──
    val dynamicColor: StateFlow<Boolean> = themeController.dynamicColor

    fun setDynamicColor(enabled: Boolean) = themeController.setDynamicColor(enabled)

    private val _manual = MutableStateFlow(ManualConnectState())
    val manual: StateFlow<ManualConnectState> = _manual.asStateFlow()

    /** 手动 IP 连接（AP 模式下网关发现的兜底路径；相机固定 192.168.122.1） */
    fun manualConnect(host: String) {
        val ip = host.trim()
        if (_manual.value.connecting) return
        if (!ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            _manual.value = ManualConnectState(message = "IP 格式不正确（示例 192.168.122.1）")
            return
        }
        viewModelScope.launch {
            _manual.value = ManualConnectState(connecting = true)
            stateHolder.update { ConnectionState(ConnectionPhase.CONNECTING) }
            try {
                val result = repository.connect(ip)
                stateHolder.update {
                    ConnectionState(
                        phase = ConnectionPhase.CONNECTED,
                        channelType = result.channelType,
                        cameraModel = result.deviceModel
                    )
                }
                _manual.value = ManualConnectState(
                    connected = true,
                    message = "已连接 ${result.deviceModel}（${result.channelType}）"
                )
            } catch (e: Exception) {
                stateHolder.update {
                    ConnectionState(ConnectionPhase.ERROR, errorMessage = e.message ?: "连接失败")
                }
                _manual.value = ManualConnectState(message = "连接失败：${e.message}")
            }
        }
    }
}
