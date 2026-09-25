package com.imagedge.camera.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.CapabilityDetail
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.profile.CameraProfile
import com.imagedge.camera.data.profile.ParameterPreset
import com.imagedge.camera.data.profile.PresetItemOutcome
import com.imagedge.camera.data.profile.PresetItemStatus
import com.imagedge.camera.data.profile.PresetParameters
import com.imagedge.camera.data.profile.ProfileCapability
import com.imagedge.camera.data.profile.RecentConnection
import com.imagedge.camera.feature.capture.IdentitySummary
import com.imagedge.camera.feature.capture.label
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppPage
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.components.AppTextField
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.glass.glassDialog
import com.imagedge.camera.ui.glass.glassDialogContainerColor
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机档案页（T6）——档案 / 最近连接 / 能力展示 / 命名预设的存取与应用
 *     version: 1.0
 * </pre>
 */

/** 能力项 → 标题资源（与遥控页同一批名字，两处不能各叫一套） */
@StringRes
private fun capabilityLabel(capability: CameraCapability): Int = when (capability) {
    CameraCapability.EXPOSURE_PROGRAM_MODE -> R.string.control_shoot_mode
    CameraCapability.ISO -> R.string.control_iso
    CameraCapability.F_NUMBER -> R.string.control_fnumber
    CameraCapability.SHUTTER_SPEED -> R.string.control_shutter
    CameraCapability.WHITE_BALANCE -> R.string.control_wb
    CameraCapability.EXPOSURE_BIAS -> R.string.control_eb
    CameraCapability.CAPTURE -> R.string.profile_cap_capture
}

@StringRes
private fun capabilityStateLabel(state: CapabilityState): Int = when (state) {
    CapabilityState.WRITABLE -> R.string.profile_state_writable
    CapabilityState.READ_ONLY -> R.string.profile_state_read_only
    CapabilityState.UNSUPPORTED -> R.string.profile_state_unsupported
    CapabilityState.UNKNOWN -> R.string.profile_state_unknown
}

@StringRes
private fun itemStatusLabel(status: PresetItemStatus): Int = when (status) {
    PresetItemStatus.APPLIED -> R.string.profile_item_applied
    PresetItemStatus.PROFILE_MISMATCH -> R.string.profile_item_mismatch
    PresetItemStatus.NOT_PROBED -> R.string.profile_item_not_probed
    PresetItemStatus.CAPABILITY_NOT_WRITABLE -> R.string.profile_item_not_writable
    PresetItemStatus.VALUE_NOT_ALLOWED -> R.string.profile_item_value_rejected
    PresetItemStatus.CAMERA_REFUSED -> R.string.profile_item_refused
    PresetItemStatus.READBACK_MISMATCH -> R.string.profile_item_readback_diff
}

/** 原始值 → 展示文案（与遥控页选项同一套格式化，预设里显示的值必须能读） */
private fun formatValue(capability: CameraCapability, raw: Long): String = when (capability) {
    CameraCapability.ISO -> CameraSettings.formatIso(raw)
    CameraCapability.F_NUMBER -> CameraSettings.formatFNumber(raw)
    CameraCapability.SHUTTER_SPEED -> CameraSettings.formatShutter(raw)
    CameraCapability.EXPOSURE_PROGRAM_MODE -> CameraSettings.formatProgramMode(raw)
    CameraCapability.WHITE_BALANCE -> CameraSettings.formatWhiteBalance(raw)
    CameraCapability.EXPOSURE_BIAS -> CameraSettings.formatExposureBias(raw)
    CameraCapability.CAPTURE -> "--"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(millis))

/** 档案键（型号|固件）→ 可读标题 */
private fun profileKeyLabel(profileKey: String): String {
    val model = profileKey.substringBefore('|').ifBlank { "—" }
    val firmware = profileKey.substringAfter('|', "").ifBlank { null }
    return if (firmware == null) model else "$model · $firmware"
}

@Composable
fun CameraProfileScreen(
    onBack: () -> Unit = {},
    snackbarController: SnackbarController? = null,
    viewModel: CameraProfileViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val recents by viewModel.recentConnections.collectAsStateWithLifecycle()

    // 一次性提示：交给全局顶部横幅呈现后立刻消费，避免返回再进页面时重放同一条
    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarController?.show(message)
            viewModel.consumeMessage()
        }
    }

    var namingCurrent by rememberSaveable { mutableStateOf(false) }
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePresetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteProfileKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openProfileKey by rememberSaveable { mutableStateOf<String?>(null) }
    // 导出要先把目标档案记下来：CreateDocument 的回调只给 URI，不知道用户当时点的是哪一组
    var exportProfileKey by rememberSaveable { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importPresets(it) } }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { target -> exportProfileKey?.let { viewModel.exportPresets(it, target) } }
        exportProfileKey = null
    }

    AppPage(
        title = stringResource(R.string.profile_title),
        onBack = onBack
    ) {
        CurrentCameraSection(
            state = state,
            onRefresh = viewModel::refreshLive,
            onSave = { namingCurrent = true }
        )

        PresetsSection(
            presets = presets,
            currentProfileKey = if (state.identity.isKnown) state.identity.profileKey else null,
            applying = state.applying,
            onApply = viewModel::applyPreset,
            onRename = { renameTargetId = it.id },
            onDelete = { deletePresetId = it.id },
            onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            onExport = { key ->
                exportProfileKey = key
                exportLauncher.launch(viewModel.suggestedFileName(key))
            }
        )

        ProfilesSection(
            profiles = profiles,
            expandedKey = openProfileKey,
            onToggle = { key -> openProfileKey = if (openProfileKey == key) null else key },
            onDelete = { deleteProfileKey = it.profileKey }
        )

        RecentSection(
            recents = recents,
            onClear = viewModel::clearRecentConnections
        )
    }

    if (namingCurrent) {
        val suggested = remember(state.identity.profileKey) {
            SimpleDateFormat("MM-dd HH-mm", Locale.getDefault()).format(java.util.Date())
        }
        NameDialog(
            title = stringResource(R.string.profile_save_title),
            initial = suggested,
            onDismiss = { namingCurrent = false },
            onConfirm = { name ->
                viewModel.saveCurrentAsPreset(name)
                namingCurrent = false
            }
        )
    }

    presets.firstOrNull { it.id == renameTargetId }?.let { target ->
        NameDialog(
            title = stringResource(R.string.profile_rename_title),
            initial = target.name,
            onDismiss = { renameTargetId = null },
            onConfirm = { name ->
                viewModel.renamePreset(target, name)
                renameTargetId = null
            }
        )
    }

    presets.firstOrNull { it.id == deletePresetId }?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.profile_delete_preset_title),
            body = stringResource(R.string.profile_delete_preset_body, target.name),
            confirmLabel = stringResource(R.string.profile_delete),
            onDismiss = { deletePresetId = null },
            onConfirm = {
                viewModel.deletePreset(target)
                deletePresetId = null
            }
        )
    }

    profiles.firstOrNull { it.profileKey == deleteProfileKey }?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.profile_delete_profile_title),
            body = stringResource(R.string.profile_delete_profile_body, target.model, target.presetsOf(presets).size),
            confirmLabel = stringResource(R.string.profile_delete),
            onDismiss = { deleteProfileKey = null },
            onConfirm = {
                viewModel.deleteProfile(target)
                deleteProfileKey = null
            }
        )
    }

    state.report?.let { report ->
        ApplyReportDialog(report = report, onDismiss = viewModel::dismissReport)
    }
}

/** 该档案名下的预设条数（删档案前要先说清连带删掉多少） */
private fun CameraProfile.presetsOf(all: List<ParameterPreset>): List<ParameterPreset> =
    all.filter { it.profileKey == profileKey }

// ── 当前相机 ─────────────────────────────────────────────────────────

@Composable
private fun CurrentCameraSection(
    state: ProfileUiState,
    onRefresh: () -> Unit,
    onSave: () -> Unit
) {
    AppSection(title = stringResource(R.string.profile_section_current)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.L),
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                IdentitySummary(state.identity)
                // 「探测成功」与「没有可信快照」必须分开讲：后者只说明这一轮没读到描述符，
                // 不代表这台相机不能调参数
                Text(
                    text = if (state.liveProbed) {
                        stringResource(R.string.profile_live_probed, state.liveValues.size)
                    } else {
                        stringResource(R.string.profile_live_absent)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.liveProbed) {
                    state.liveValues.forEach { (capability, raw) ->
                        LabeledValue(
                            label = stringResource(capabilityLabel(capability)),
                            value = formatValue(capability, raw)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.profile_save_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 动作排在它要操作的内容之后（UI 规范 §9：主操作在内容末端）
                if (state.probing) {
                    ProcessingView(message = stringResource(R.string.profile_probing))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        AppButton(
                            text = stringResource(R.string.profile_refresh),
                            onClick = onRefresh,
                            leadingIcon = Lucide.RefreshCw,
                            type = AppButtonType.SECONDARY,
                            fullWidth = false
                        )
                        // 全页唯一的 PRIMARY：把当前参数存成预设。没有可信快照时不提供入口，
                        // 而不是让人存下一个「看似成功、实则空表」的预设
                        AppButton(
                            text = stringResource(R.string.profile_save_current),
                            onClick = onSave,
                            enabled = state.liveProbed && state.liveValues.isNotEmpty(),
                            leadingIcon = Lucide.CircleCheck,
                            fullWidth = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

// ── 参数预设 ─────────────────────────────────────────────────────────

@Composable
private fun PresetsSection(
    presets: List<ParameterPreset>,
    currentProfileKey: String?,
    applying: Boolean,
    onApply: (ParameterPreset) -> Unit,
    onRename: (ParameterPreset) -> Unit,
    onDelete: (ParameterPreset) -> Unit,
    onImport: () -> Unit,
    onExport: (String) -> Unit
) {
    AppSection(
        title = stringResource(R.string.profile_section_presets),
        trailing = {
            AppLink(
                text = stringResource(R.string.profile_import),
                onClick = onImport,
                enabled = !applying
            )
        }
    ) {
        if (presets.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.profile_presets_empty),
                desc = stringResource(R.string.profile_presets_empty_desc)
            )
            return@AppSection
        }
        if (applying) {
            ProcessingView(message = stringResource(R.string.profile_applying))
        }
        // 按档案成组：预设只对型号 + 固件都一致的机器有意义，混在一个平铺列表里
        // 就会让人把 A 机的档位套到 B 机上
        presets.groupBy { it.profileKey }.forEach { (profileKey, group) ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = profileKeyLabel(profileKey),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (profileKey == currentProfileKey) {
                        Text(
                            text = stringResource(R.string.profile_is_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    AppLink(
                        text = stringResource(R.string.profile_export),
                        onClick = { onExport(profileKey) },
                        enabled = !applying
                    )
                }
                group.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        applicable = profileKey == currentProfileKey,
                        hasCamera = currentProfileKey != null,
                        enabled = !applying,
                        onApply = { onApply(preset) },
                        onRename = { onRename(preset) },
                        onDelete = { onDelete(preset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: ParameterPreset,
    applicable: Boolean,
    hasCamera: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(Radius.Tag)
            )
            .padding(horizontal = Spacing.M, vertical = Spacing.S),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Text(text = preset.name, style = MaterialTheme.typography.bodyLarge)
        // 每项都带参数名：光看「400 · 4.0 · -2.0」分不出哪个是光圈、哪个是 EV
        preset.parameters.forEach { capability ->
            LabeledValue(
                label = stringResource(capabilityLabel(capability)),
                value = formatValue(capability, preset.values.getValue(capability))
            )
        }
        // 档案不符与「压根没连相机」是两件事，混成一句会让用户在没连相机时以为
        // 自己的预设全废了——实际上只是此刻无从判断
        if (!applicable) {
            Text(
                text = stringResource(
                    if (hasCamera) R.string.profile_preset_other_camera
                    else R.string.profile_preset_no_camera
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
            AppLink(
                text = stringResource(R.string.profile_apply),
                onClick = onApply,
                enabled = enabled && applicable
            )
            AppLink(text = stringResource(R.string.profile_rename), onClick = onRename, enabled = enabled)
            AppLink(
                text = stringResource(R.string.profile_delete),
                onClick = onDelete,
                enabled = enabled,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ── 相机档案 ─────────────────────────────────────────────────────────

@Composable
private fun ProfilesSection(
    profiles: List<CameraProfile>,
    expandedKey: String?,
    onToggle: (String) -> Unit,
    onDelete: (CameraProfile) -> Unit
) {
    AppSection(title = stringResource(R.string.profile_section_profiles)) {
        if (profiles.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_profiles_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AppSection
        }
        profiles.forEach { profile ->
            val expanded = expandedKey == profile.profileKey
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(Radius.Card)
                    )
                    .clickable { onToggle(profile.profileKey) }
                    .padding(Spacing.L),
                verticalArrangement = Arrangement.spacedBy(Spacing.S)
            ) {
                Text(text = profile.model, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        R.string.profile_profile_meta,
                        profile.firmwareLabel,
                        profile.connectCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.profile_profile_last_seen, formatTime(profile.lastSeenAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (expanded) {
                    // 展开后先说清这份数据的性质：它是**上次实测**，不是这台相机的永久能力
                    Text(
                        text = stringResource(R.string.profile_archive_stale_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (profile.capabilities.isEmpty()) {
                        Text(
                            text = stringResource(R.string.profile_no_snapshot),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    profile.capabilities.forEach { snapshot ->
                        SnapshotBlock(snapshot)
                    }
                    AppLink(
                        text = stringResource(R.string.profile_delete),
                        onClick = { onDelete(profile) },
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 一份历史能力快照（按传输方式 + 功能模式归档） */
@Composable
private fun SnapshotBlock(snapshot: ProfileCapability) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(Radius.Tag)
            )
            .padding(Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Text(
            text = stringResource(
                R.string.profile_snapshot_title,
                snapshot.transport?.label() ?: "—",
                modeLabel(snapshot.mode),
                formatTime(snapshot.probedAt)
            ),
            style = MaterialTheme.typography.labelLarge
        )
        PresetParameters.order.forEach { capability ->
            CapabilityStateRow(
                label = stringResource(capabilityLabel(capability)),
                detail = snapshot.capabilities.detail(capability)
            )
        }
    }
}

/**
 * 单项能力的四态展示。
 *
 * 状态与依据一起给：只显示「不可用」会让人以为是 App 的问题，
 * 而 note 里是相机上报的原始事实（GetSet / IsEnabled / 缺描述符）。
 */
@Composable
private fun CapabilityStateRow(label: String, detail: CapabilityDetail) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(capabilityStateLabel(detail.state)),
            style = MaterialTheme.typography.labelSmall,
            color = if (detail.state == CapabilityState.WRITABLE) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    Text(
        text = detail.note.ifBlank { stringResource(R.string.profile_no_note) },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
    when {
        detail.supportedValues.isNotEmpty() -> Text(
            text = stringResource(R.string.profile_reported_values, detail.supportedValues.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        detail.range != null -> Text(
            text = stringResource(
                R.string.profile_reported_range,
                detail.range.min,
                detail.range.max,
                detail.range.step
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun modeLabel(mode: Int): String = when (mode) {
    CameraIdentity.MODE_REMOTE_CONTROL -> stringResource(R.string.control_identity_mode_remote)
    CameraIdentity.MODE_CONTENTS_TRANSFER -> stringResource(R.string.control_identity_mode_fullcard)
    else -> stringResource(R.string.profile_mode_unknown)
}

// ── 最近连接 ─────────────────────────────────────────────────────────

@Composable
private fun RecentSection(recents: List<RecentConnection>, onClear: () -> Unit) {
    AppSection(
        title = stringResource(R.string.profile_section_recent),
        trailing = {
            if (recents.isNotEmpty()) {
                AppLink(text = stringResource(R.string.profile_clear_recent), onClick = onClear)
            }
        }
    ) {
        if (recents.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_recent_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@AppSection
        }
        // 这里只有「哪台机器、什么时候、用什么方式」，没有 SSID 也没有密码：
        // 重连仍需重新扫码取凭据，档案页不给任何可绕过凭据的东西
        recents.forEach { record ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = record.model,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        R.string.profile_recent_meta,
                        record.transport?.label() ?: "—",
                        modeLabel(record.mode),
                        formatTime(record.connectedAt)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 对话框 ───────────────────────────────────────────────────────────

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.glassDialog(),
        containerColor = glassDialogContainerColor(),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                AppTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = stringResource(R.string.profile_preset_name_label)
                )
                Text(
                    text = stringResource(R.string.profile_preset_name_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            AppLink(
                text = stringResource(R.string.profile_confirm),
                onClick = { onConfirm(draft) },
                enabled = draft.isNotBlank()
            )
        },
        dismissButton = {
            AppLink(text = stringResource(R.string.profile_cancel), onClick = onDismiss)
        }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.glassDialog(),
        containerColor = glassDialogContainerColor(),
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            AppLink(text = confirmLabel, onClick = onConfirm, color = MaterialTheme.colorScheme.error)
        },
        dismissButton = {
            AppLink(text = stringResource(R.string.profile_cancel), onClick = onDismiss)
        }
    )
}

/**
 * 应用结果报告。
 *
 * 逐项列状态，且「已生效」只给读回一致的那些——预设部分失败时必须看得见是哪几项、为什么。
 */
@Composable
private fun ApplyReportDialog(report: PresetApplyReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.glassDialog(),
        containerColor = glassDialogContainerColor(),
        title = {
            Text(
                stringResource(
                    if (report.fullyApplied) R.string.profile_report_all
                    else R.string.profile_report_partial
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.S)
            ) {
                Text(
                    text = stringResource(
                        R.string.profile_report_count,
                        report.applied.size,
                        report.outcomes.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                report.outcomes.forEach { outcome ->
                    ReportRow(outcome)
                }
            }
        },
        confirmButton = {
            AppLink(text = stringResource(R.string.profile_confirm), onClick = onDismiss)
        }
    )
}

@Composable
private fun ReportRow(outcome: PresetItemOutcome) {
    val applied = outcome.status == PresetItemStatus.APPLIED
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(capabilityLabel(outcome.capability)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatValue(outcome.capability, outcome.expected),
                style = MaterialTheme.typography.bodySmall,
                color = if (applied) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = stringResource(itemStatusLabel(outcome.status)) +
                outcome.actual?.let { " · ${formatValue(outcome.capability, it)}" }.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
