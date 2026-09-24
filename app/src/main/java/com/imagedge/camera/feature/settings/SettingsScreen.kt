package com.imagedge.camera.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.BuildConfig
import com.imagedge.camera.R
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.navigation.LocalNavClearance
import com.imagedge.camera.ui.components.ActionRow
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppSwitchRow
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.SettingsRow
import com.imagedge.camera.ui.glass.glassDialog
import com.imagedge.camera.ui.glass.glassDialogContainerColor
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.ThemeMode

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-28
 *     desc   : 设置——外观与触觉、色彩预设(LUT)、保存位置、相机档案与权限入口、关于。
 *              批次 A 的代表页：改用 AppScreenFrame + AppPageHeader + ActionRow/SettingsRow。
 *     version: 2.0
 * </pre>
 */

@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val userLuts by viewModel.userLuts.collectAsStateWithLifecycle()
    val lutMessage by viewModel.lutMessage.collectAsStateWithLifecycle()
    val dirLabel by viewModel.downloadDirLabel.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshLuts() }

    var exportTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // 待删除的 LUT（先确认再删，避免误触丢失用户自己导入的滤镜）
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val lutImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importLut(it) } }
    val lutExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { target ->
            exportTarget?.let { viewModel.exportLut(it, target) }
            exportTarget = null
        }
    }
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.onDirPicked(it) } }

    AppScreenFrame(
        topBar = { AppPageHeader(title = stringResource(R.string.tab_settings), large = true) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 安全区由 AppScreenFrame 加过了，这里只加自己的水平边距与导航让位
                .padding(horizontal = Spacing.L)
                .padding(bottom = LocalNavClearance.current),
            verticalArrangement = Arrangement.spacedBy(Spacing.L)
        ) {
            // ──  保存位置与下载 ─
            GroupTitle(stringResource(R.string.settings_section_download), icon = Lucide.Download)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                // 常用行直接显示当前值：要点进下一层才知道存到哪，等于把状态藏起来
                SettingsRow(
                    title = stringResource(R.string.settings_save_location_title),
                    value = dirLabel,
                    onClick = { dirPicker.launch(null) }
                )
                if (viewModel.downloadTreeUri != null) {
                    AppLink(
                        text = stringResource(R.string.settings_restore_dir),
                        onClick = viewModel::restoreDefaultDir
                    )
                }
                Text(
                    text = stringResource(R.string.settings_download_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── ② 外观与触觉 ──
            GroupTitle(stringResource(R.string.settings_section_appearance), icon = Lucide.Palette)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    Text(
                        text = stringResource(R.string.settings_theme_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val segmentedColors = SegmentedButtonDefaults.colors(
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                    // 显示顺序写死，不用 ThemeMode.entries：枚举声明序是 SYSTEM/LIGHT/DARK，
                    // 而界面上「浅色 / 深色 / 跟随系统」才是既有顺序，用 entries 会静默换序
                    val displayOrder = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        displayOrder.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = displayOrder.size),
                                colors = segmentedColors
                            ) {
                                Text(stringResource(mode.labelRes()))
                            }
                        }
                    }
                }
                AppSwitchRow(
                    title = stringResource(R.string.settings_haptics_title),
                    subtitle = stringResource(R.string.settings_haptics_desc),
                    checked = hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled
                )
            }

            // ── ③ 色彩预设（LUT） ──
            GroupTitle(stringResource(R.string.settings_section_lut), icon = Lucide.SlidersHorizontal)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                if (userLuts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_lut_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    userLuts.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name.removeSuffix(".cube"),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            AppLink(
                                text = stringResource(R.string.settings_lut_export),
                                onClick = {
                                    exportTarget = name
                                    lutExportLauncher.launch(name)
                                }
                            )
                            AppLink(
                                text = stringResource(R.string.settings_lut_delete),
                                onClick = { deleteTarget = name },
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                AppButton(
                    text = stringResource(R.string.settings_lut_import),
                    onClick = { lutImportLauncher.launch(arrayOf("*/*")) },
                    type = AppButtonType.PRIMARY
                )
                lutMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── ④ 相机与权限 ──
            GroupTitle(stringResource(R.string.settings_section_camera), icon = Lucide.Camera)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                ActionRow(
                    title = stringResource(R.string.profile_title),
                    description = stringResource(R.string.profile_entry_desc),
                    icon = Lucide.Camera,
                    onClick = onOpenProfiles
                )
                ActionRow(
                    title = stringResource(R.string.permission_title),
                    description = stringResource(R.string.settings_permission_entry_desc),
                    icon = Lucide.ShieldCheck,
                    onClick = onOpenPermissions
                )
            }

            // ── ⑤ 关于 ──
            GroupTitle(stringResource(R.string.settings_section_about), icon = Lucide.Info)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                // 版本号从 BuildConfig 读取：与构建配置同源，发版永不脱节
                Text(
                    text = "Imagedge " + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 删除确认：LUT 是用户自己导入的资产，删掉就找不回来了，二次确认避免误触
    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            modifier = Modifier.glassDialog(),
            containerColor = glassDialogContainerColor(),
            title = { Text(stringResource(R.string.settings_lut_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_lut_delete_confirm_body,
                        name.removeSuffix(".cube")
                    )
                )
            },
            confirmButton = {
                AppLink(
                    text = stringResource(R.string.settings_lut_delete),
                    onClick = {
                        viewModel.deleteLut(name)
                        deleteTarget = null
                    },
                    color = MaterialTheme.colorScheme.error
                )
            },
            dismissButton = {
                AppLink(
                    text = stringResource(R.string.settings_lut_delete_cancel),
                    onClick = { deleteTarget = null }
                )
            }
        )
    }

    // 导入后：让用户声明这个 LUT 适用于哪类画面（决定它归入编辑页哪一排）
    val pendingType by viewModel.pendingLutType.collectAsStateWithLifecycle()
    pendingType?.let { (name, suggested) ->
        var selected by remember(name) { mutableStateOf(suggested) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissLutType() },
            modifier = Modifier.glassDialog(),
            containerColor = glassDialogContainerColor(),
            title = { Text(stringResource(R.string.lut_type_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    Text(
                        text = stringResource(R.string.lut_type_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LutTypeOption(
                        label = stringResource(R.string.lut_type_creative),
                        selected = selected == LutType.CREATIVE,
                        onClick = { selected = LutType.CREATIVE }
                    )
                    LutTypeOption(
                        label = stringResource(R.string.lut_type_slog2),
                        selected = selected == LutType.SLOG2,
                        onClick = { selected = LutType.SLOG2 }
                    )
                    LutTypeOption(
                        label = stringResource(R.string.lut_type_slog3),
                        selected = selected == LutType.SLOG3,
                        onClick = { selected = LutType.SLOG3 }
                    )
                }
            },
            confirmButton = {
                AppLink(
                    text = stringResource(R.string.lut_type_confirm),
                    onClick = { viewModel.confirmLutType(selected) }
                )
            },
            dismissButton = {
                AppLink(
                    text = stringResource(R.string.lut_type_skip),
                    onClick = { viewModel.dismissLutType() }
                )
            }
        )
    }
}

/** 主题档位 → 文案资源 */
@StringRes
private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
    ThemeMode.SYSTEM -> R.string.settings_theme_system
}

/** LUT 适用类型单选项 */
@Composable
private fun LutTypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
