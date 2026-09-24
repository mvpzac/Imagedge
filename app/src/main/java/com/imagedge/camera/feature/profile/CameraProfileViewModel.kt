package com.imagedge.camera.feature.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.profile.CameraProfile
import com.imagedge.camera.data.profile.CameraProfileStore
import com.imagedge.camera.data.profile.ParameterPreset
import com.imagedge.camera.data.profile.PresetDocument
import com.imagedge.camera.data.profile.PresetItemOutcome
import com.imagedge.camera.data.profile.PresetItemStatus
import com.imagedge.camera.data.profile.PresetParameters
import com.imagedge.camera.data.profile.PresetPlanItem
import com.imagedge.camera.data.profile.PresetPlanner
import com.imagedge.camera.data.profile.RecentConnection
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.CameraSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机档案页 ViewModel（档案 / 最近连接 / 能力展示 / 命名预设的存取与应用）
 *     version: 1.0
 * </pre>
 */

/**
 * 一次预设应用的逐项结果。
 *
 * 只有 [applied] 里那些**读回一致**的项算成功；其余必须出现在 [failed] 中并被界面逐条
 * 说明——「界面上一个勾、相机上什么也没变」是预设功能最坏的失败方式。
 */
data class PresetApplyReport(
    val presetName: String,
    val outcomes: List<PresetItemOutcome>
) {
    val applied: List<PresetItemOutcome>
        get() = outcomes.filter { it.status == PresetItemStatus.APPLIED }
    val failed: List<PresetItemOutcome>
        get() = outcomes.filter { it.status != PresetItemStatus.APPLIED }
    val fullyApplied: Boolean get() = failed.isEmpty()
}

data class ProfileUiState(
    /** 当前连接的相机身份，未连接为 [CameraIdentity.UNKNOWN] */
    val identity: CameraIdentity = CameraIdentity.UNKNOWN,
    /** 本轮是否真的读到了 0x9209 描述符（档案里的历史快照永远不算） */
    val liveProbed: Boolean = false,
    /** 当前可存进预设的参数：相机当次上报「可写」**且**该值在当次上报的档位里 */
    val liveValues: Map<CameraCapability, Long> = emptyMap(),
    val probing: Boolean = false,
    val applying: Boolean = false,
    val report: PresetApplyReport? = null,
    val message: String? = null
)

@HiltViewModel
class CameraProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: CameraProfileStore,
    private val repository: CameraRepository
) : ViewModel() {

    val profiles: StateFlow<List<CameraProfile>> = profileStore.observeProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentConnections: StateFlow<List<RecentConnection>> = profileStore.observeRecentConnections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val presets: StateFlow<List<ParameterPreset>> = profileStore.observePresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        // 进页面先读一次：档案页要回答「现在这台相机能调什么」，
        // 而这个问题只有相机能回答，缓存与历史快照都不算证据
        refreshLive()
    }

    /**
     * 重新探测当前连接的相机。
     *
     * 未连接时 [CameraRepository.refreshCapabilities] 返回 UNKNOWN 快照，这里如实落到
     * 「未探测」——不保留上一次的 liveValues，否则断线后那个「存为预设」的按钮还在放行。
     */
    fun refreshLive() {
        if (_state.value.probing) return
        viewModelScope.launch {
            _state.update { it.copy(probing = true) }
            val snapshot = runCatching { repository.refreshCapabilities() }
                .onFailure { AppLog.w(TAG, "能力探测失败：${it.message}") }
                .getOrNull()
            applySnapshot(snapshot)
            _state.update { it.copy(probing = false) }
        }
    }

    private fun applySnapshot(snapshot: CameraSnapshot?) {
        if (snapshot == null) {
            _state.update {
                it.copy(identity = CameraIdentity.UNKNOWN, liveProbed = false, liveValues = emptyMap())
            }
            return
        }
        val capabilities = snapshot.capabilities
        val trusted = capabilities.descriptorRead && !capabilities.stale
        _state.update {
            it.copy(
                identity = snapshot.identity,
                liveProbed = trusted,
                liveValues = if (trusted) collectWritable(snapshot) else emptyMap()
            )
        }
    }

    /** 只收「相机当次说可写，且当前值就在当次上报的档位里」的参数 */
    private fun collectWritable(snapshot: CameraSnapshot): Map<CameraCapability, Long> =
        PresetParameters.order.mapNotNull { capability ->
            val raw = PresetPlanner.actualOf(capability, snapshot.settings) ?: return@mapNotNull null
            if (snapshot.capabilities.accepts(capability, raw)) capability to raw else null
        }.toMap()

    // ── 命名预设 ─────────────────────────────────────────────────────

    /** 把当前参数存成命名预设；同名同档案覆盖更新 */
    fun saveCurrentAsPreset(name: String) {
        val current = _state.value
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (!current.liveProbed || current.liveValues.isEmpty()) {
            _state.update { it.copy(message = "当前没有可读写的参数，请先连上相机并进入遥控页") }
            return
        }
        viewModelScope.launch {
            val saved = profileStore.savePreset(current.identity.profileKey, trimmed, current.liveValues)
            _state.update { it.copy(message = "已存为预设「${saved.name}」（${saved.parameters.size} 项）") }
        }
    }

    fun renamePreset(preset: ParameterPreset, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == preset.name) return
        viewModelScope.launch {
            profileStore.savePreset(preset.profileKey, trimmed, preset.values, preset.id)
            _state.update { it.copy(message = "已重命名为「${trimmed.take(CameraProfileStore.MAX_PRESET_NAME_CHARS)}」") }
        }
    }

    fun deletePreset(preset: ParameterPreset) {
        viewModelScope.launch {
            profileStore.deletePreset(preset.id)
            _state.update { it.copy(message = "已删除预设「${preset.name}」") }
        }
    }

    /**
     * 应用一份预设：重读描述符 → 逐项判定 → 下发 → 再读回 → 逐项结论。
     *
     * 下发前**强制重新探测**而不复用页面上那份缓存：档案页可能开着很久，期间用户换了镜头、
     * 相机自己切了模式，缓存里的「可写」就成了过期许可。
     */
    fun applyPreset(preset: ParameterPreset) {
        if (_state.value.applying) return
        viewModelScope.launch {
            _state.update { it.copy(applying = true, report = null, message = null) }
            val probe = runCatching { repository.refreshCapabilities() }.getOrNull()
            if (probe == null) {
                _state.update {
                    it.copy(
                        applying = false,
                        message = "读取相机参数失败，未下发任何命令"
                    )
                }
                return@launch
            }
            val plan = PresetPlanner.plan(preset, probe.capabilities)
            val writes = plan.filterIsInstance<PresetPlanItem.Write>()
            val results = writes.associate { item ->
                item.capability to runCatching { send(item.capability, item.raw) }.getOrDefault(false)
            }
            // 只在下发过命令后才重读；全被跳过时上一次读的结果就是最新事实，不必空跑一趟
            val readBack = if (writes.isEmpty()) probe.settings
            else repository.refreshCapabilities().settings
            val outcomes = PresetPlanner.outcomes(plan, results, readBack)
            applySnapshot(probe)
            _state.update {
                it.copy(
                    applying = false,
                    report = PresetApplyReport(preset.name, outcomes)
                )
            }
        }
    }

    /**
     * 按能力派发到仓库的公开设置方法。
     *
     * 不复用仓库里的私有 `writeProperty`：那会让这里绕过「能力闸门」自己拼命令。
     * 走公开 setter，闸门与日志和遥控页完全一致。
     */
    private suspend fun send(capability: CameraCapability, raw: Long): Boolean = when (capability) {
        CameraCapability.ISO -> repository.setIso(raw)
        CameraCapability.F_NUMBER -> repository.setFNumber(raw)
        CameraCapability.SHUTTER_SPEED -> repository.setShutterSpeed(raw)
        CameraCapability.EXPOSURE_PROGRAM_MODE -> repository.setExposureProgramMode(raw)
        CameraCapability.WHITE_BALANCE -> repository.setWhiteBalance(raw)
        CameraCapability.EXPOSURE_BIAS -> repository.setExposureBias(raw)
        // CAPTURE 是动作不是参数，预设里不该出现它；真出现了宁可不下发
        CameraCapability.CAPTURE -> false
    }

    fun dismissReport() = _state.update { it.copy(report = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ── 档案与最近连接 ───────────────────────────────────────────────

    fun deleteProfile(profile: CameraProfile) {
        viewModelScope.launch {
            profileStore.deleteProfile(profile.profileKey)
            _state.update { it.copy(message = "已删除档案 ${profile.model}") }
        }
    }

    fun clearRecentConnections() {
        viewModelScope.launch { profileStore.clearRecentConnections() }
    }

    // ── 导出 / 导入 ──────────────────────────────────────────────────

    /**
     * 导出某个档案的全部预设。
     *
     * 文件按档案（型号 + 固件）成组，不做大杂烩：预设离开那台机器就没有意义，
     * 混在一起导出会把「这份给谁用」这件事丢掉。
     */
    fun exportPresets(profileKey: String, target: Uri) {
        viewModelScope.launch {
            val list = profileStore.presetsFor(profileKey)
            if (list.isEmpty()) {
                _state.update { it.copy(message = "这台相机还没有预设可导出") }
                return@launch
            }
            val text = PresetDocument.encode(list, identityOf(profileKey), System.currentTimeMillis())
            writeText(target, text)
                .onSuccess { _state.update { s -> s.copy(message = "已导出 ${list.size} 份预设") } }
                .onFailure {
                    AppLog.w(TAG, "预设导出失败：${it.message}")
                    _state.update { s -> s.copy(message = "导出失败：${it.message ?: "未知原因"}") }
                }
        }
    }

    /** 导入一份预设文件：整份可信才入库，逐条被拒的原因一并报告 */
    fun importPresets(source: Uri) {
        viewModelScope.launch {
            val text = readBoundedText(source)
            if (text == null) {
                _state.update { it.copy(message = "读取失败：文件过大或无法打开") }
                return@launch
            }
            // 没连上相机时传 null：此时没有任何对照物，不能断言这批预设「不属于当前相机」
            val connectedKey = _state.value.identity
                .takeIf { it.isKnown }
                ?.profileKey
            val result = PresetDocument.decode(text, connectedKey)
            result.failure?.let {
                _state.update { s -> s.copy(message = "导入被拒绝：$it") }
                return@launch
            }
            if (result.presets.isEmpty()) {
                val reason = if (result.rejected.isEmpty()) {
                    "文件里没有可导入的预设"
                } else {
                    "没有预设通过校验：${result.rejected.joinToString("；")}"
                }
                _state.update { s -> s.copy(message = reason) }
                return@launch
            }
            val written = profileStore.importPresets(result.presets)
            _state.update {
                it.copy(
                    message = buildString {
                        append("已导入 $written 份预设")
                        if (result.rejected.isNotEmpty()) append("；跳过 ${result.rejected.size} 条")
                    }
                )
            }
        }
    }

    /** 导出文件名建议（型号可能含空格与斜杠，必须清洗后才能当扩展名用） */
    fun suggestedFileName(profileKey: String): String {
        val model = profileKey.substringBefore('|').ifBlank { "camera" }
        val firmware = profileKey.substringAfter('|', "")
        val safe = model.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val suffix = if (firmware.isBlank()) "" else "-${firmware.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        return "imagedge-presets-$safe$suffix.json"
    }

    /**
     * 由档案键还原导出头部的身份。
     *
     * 导出只需要型号 + 固件（档案键本身），传输方式与功能模式留空：
     * 一份预设文件不属于某次连接，它属于这台机器。
     */
    private fun identityOf(profileKey: String): CameraIdentity = CameraIdentity(
        model = profileKey.substringBefore('|'),
        firmware = profileKey.substringAfter('|', ""),
        transport = null,
        mode = CameraIdentity.MODE_UNKNOWN
    )

    private suspend fun readBoundedText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 有界读取：文件来自外部存储，被换成一个巨型文件时不能先全部读进堆再判定
                val limit = PresetDocument.MAX_FILE_CHARS + 1L
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    total += read
                    if (total > limit) return@withContext null
                }
                buffer.toByteArray().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private suspend fun writeText(uri: Uri, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("无法写入所选文件")
            output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    private companion object {
        const val TAG = "profile-ui"
    }
}
