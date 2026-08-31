package com.imagedge.camera.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.RadioButton
import com.imagedge.camera.data.lut.LutType
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.BuildConfig
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.theme.ThemeMode

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 设置——主题外观（浅色/深色/跟随系统，持久化）、手动 IP 连接、
 *              下载目录信息、关于（版本/许可/免责声明）。黑白灰极简风格。
 *     version: 1.0
 * </pre>
 */

@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineLarge
        )

        // ── 外观：主题模式 ──
        SectionTitle(stringResource(R.string.settings_section_appearance), Lucide.Palette)
        // 放弃卡片形式：外观项直接陈列在页面上（用户定稿）
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 主题模式：外置标签 + 分段控件（SingleChoiceSegmentedButtonRow，
                // 单选、始终有选中项，紧凑的一排整体控件，适合即时切换展示模式）
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_theme_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 未选中段给白色底：在深灰卡片上与选中段（主色容器）都清晰可辨
                    val segmentedColors = SegmentedButtonDefaults.colors(
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = themeMode == ThemeMode.LIGHT,
                            onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                            colors = segmentedColors
                        ) {
                            Text(stringResource(R.string.settings_theme_light))
                        }
                        SegmentedButton(
                            selected = themeMode == ThemeMode.DARK,
                            onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                            colors = segmentedColors
                        ) {
                            Text(stringResource(R.string.settings_theme_dark))
                        }
                        SegmentedButton(
                            selected = themeMode == ThemeMode.SYSTEM,
                            onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                            colors = segmentedColors
                        ) {
                            Text(stringResource(R.string.settings_theme_system))
                        }
                    }
                }
                // ── 触觉反馈 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_haptics_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_haptics_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hapticsEnabled,
                        onCheckedChange = { viewModel.setHapticsEnabled(it) }
                    )
                }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── LUT 管理 ──
        SectionTitle(stringResource(R.string.settings_section_lut), Lucide.SlidersHorizontal)
        val userLuts by viewModel.userLuts.collectAsStateWithLifecycle()
        val lutMessage by viewModel.lutMessage.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.refreshLuts() }

        var exportTarget by rememberSaveable { mutableStateOf<String?>(null) }
        // 待删除的 LUT（先确认再删，避免误触丢失用户自己导入的滤镜）
        var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
        val lutImportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { viewModel.importLut(it) } }
        val lutExportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri -> uri?.let { target ->
            exportTarget?.let { viewModel.exportLut(it, target) }
            exportTarget = null
        } }

        if (userLuts.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_lut_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                userLuts.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // 只去掉 .cube 扩展名（列表里的都是用户导入的文件，
                            // 不再按内置 LUT 的 "Slog3_" 前缀做特殊裁剪）
                            text = name.removeSuffix(".cube"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            exportTarget = name
                            lutExportLauncher.launch(name)
                        }) { Text(stringResource(R.string.settings_lut_export)) }
                        TextButton(onClick = { deleteTarget = name }) {
                            Text(
                                stringResource(R.string.settings_lut_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { lutImportLauncher.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.settings_lut_import))
            }
        }
        lutMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 删除确认：LUT 是用户自己导入的资产，删掉就找不回来了，二次确认避免误触
        deleteTarget?.let { name ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
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
                    TextButton(onClick = {
                        viewModel.deleteLut(name)
                        deleteTarget = null
                    }) {
                        Text(
                            stringResource(R.string.settings_lut_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(stringResource(R.string.settings_lut_delete_cancel))
                    }
                }
            )
        }

        // 导入后：让用户声明这个 LUT 适用于哪类画面（决定它归入编辑页哪一排）
        val pendingType by viewModel.pendingLutType.collectAsStateWithLifecycle()
        pendingType?.let { (name, suggested) ->
            var selected by remember(name) { mutableStateOf(suggested) }
            AlertDialog(
                onDismissRequest = { viewModel.dismissLutType() },
                title = { Text(stringResource(R.string.lut_type_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    TextButton(onClick = { viewModel.confirmLutType(selected) }) {
                        Text(stringResource(R.string.lut_type_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissLutType() }) {
                        Text(stringResource(R.string.lut_type_skip))
                    }
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── 下载 ──
        SectionTitle(stringResource(R.string.settings_section_download), Lucide.Download)
        val dirLabel by viewModel.downloadDirLabel.collectAsStateWithLifecycle()
        Text(
            text = dirLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dirPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri -> uri?.let { viewModel.onDirPicked(it) } }
            // 通用按钮外观（描边按钮），不再是纯文字按钮
            OutlinedButton(
                onClick = { dirPicker.launch(null) },
                shape = MaterialTheme.shapes.small
            ) {
                Text(stringResource(R.string.settings_pick_dir))
            }
            if (viewModel.downloadTreeUri != null) {
                TextButton(onClick = { viewModel.restoreDefaultDir() }) {
                    Text(stringResource(R.string.settings_restore_dir))
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_download_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── 权限 ──
        SectionTitle(stringResource(R.string.settings_section_permission), Lucide.ShieldCheck)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPermissions)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                LucideIcon(
                    Lucide.ShieldCheck,
                    contentDescription = null,
                    size = 18.dp,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.settings_permission_entry_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LucideIcon(
                Lucide.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── 关于 ──
        SectionTitle(stringResource(R.string.settings_section_about), Lucide.Info)
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

/** LUT 适用类型单选项 */
@Composable
private fun LutTypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String, icon: Int? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        icon?.let {
            LucideIcon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = 16.dp
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
