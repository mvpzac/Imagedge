package com.imagedge.camera.feature.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.PageHeader
import com.imagedge.camera.ui.components.ResultMessage
import com.imagedge.camera.feature.edit.FILTER_NONE

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 相册编辑（LUT 调色）页——选图 → 滤镜（内置/导入 .cube）→ 强度 → 保存。
 *     version: 1.0
 * </pre>
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LutEditScreen(
    onBack: () -> Unit = {},
    viewModel: LutEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.loadPicked(it) } }

    // 说明弹窗开关：打开时标题栏与内容一起模糊，点背景退出。
    // 状态提到 Scaffold 之外，topBar 与 content 才能共用同一个模糊修饰；
    // 遮罩层同样要放到最外层，否则它只盖住 content、标题栏缺一层灰，视觉割裂。
    var showHelp by remember { mutableStateOf(false) }
    val blurModifier = if (showHelp) Modifier.blur(12.dp) else Modifier

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                PageHeader(
                    title = stringResource(R.string.edit_lut_title),
                    onBack = onBack,
                    modifier = blurModifier
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // 页面可上下滚动：滤镜分三排后内容变高，避免底部「保存」被挤出屏幕
                    .verticalScroll(rememberScrollState())
                    .then(blurModifier)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // ── 预览区 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
                contentAlignment = Alignment.Center
            ) {
                val shown = state.filtered ?: state.original
                if (shown != null) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = stringResource(R.string.edit_title),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    if (state.processing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                } else {
                    EmptyState(
                        title = stringResource(R.string.edit_pick_hint),
                        actionLabel = stringResource(R.string.edit_pick_image),
                        onAction = { imagePicker.launch(arrayOf("image/*")) }
                    )
                }
            }

            state.message?.let {
                ResultMessage(text = it, ok = state.saved)
            }

            if (state.hasImage) {
                // ── 滤镜选择：按适用类型分三排 ──
                // LUT 的输入曲线分三类，互不相通：普通照片（sRGB）套 Log 还原 LUT 会发灰，
                // Log 灰片套创意 LUT 会过冲。完整说明收进右上角的问号，点开才显示。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.edit_lut_filter_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { showHelp = true }) {
                        LucideIcon(
                            Lucide.CircleQuestionMark,
                            contentDescription = stringResource(R.string.edit_lut_help),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                LutFilterGroup(
                    title = stringResource(R.string.edit_lut_group_creative),
                    options = filters.filter { it.type == LutType.CREATIVE },
                    selectedKey = state.selectedKey,
                    onSelect = viewModel::selectFilter
                )
                LutFilterGroup(
                    title = stringResource(R.string.edit_lut_group_slog2),
                    options = filters.filter { it.type == LutType.SLOG2 },
                    selectedKey = state.selectedKey,
                    onSelect = viewModel::selectFilter
                )
                LutFilterGroup(
                    title = stringResource(R.string.edit_lut_group_slog3),
                    options = filters.filter { it.type == LutType.SLOG3 },
                    selectedKey = state.selectedKey,
                    onSelect = viewModel::selectFilter
                )

                // ── 强度 ──
                if (state.selectedKey != FILTER_NONE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.edit_strength),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = state.strength.toFloat(),
                            onValueChange = { viewModel.setStrength(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                        )
                        Text(
                            text = "${state.strength}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // ── 保存 ──
                AppButton(
                    text = stringResource(R.string.edit_save),
                    onClick = { viewModel.save() },
                    enabled = state.filtered != null && !state.processing
                )
                }
            }
        }

        // 说明层：放在最外层，遮罩覆盖整屏（含标题栏），
        // 与 blur 一起作用，避免只有下半屏蒙灰、标题栏缺一层的割裂感
        if (showHelp) {
            LutHelpOverlay(onDismiss = { showHelp = false })
        }
    }
}

/**
 * LUT 使用说明浮层：半透明遮罩 + 居中说明卡片。
 * 点遮罩（背景）退出；卡片内点击不关闭，避免误触。
 */
@Composable
private fun LutHelpOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                // 吃掉卡片自身的点击，避免冒泡到背景导致误关
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.edit_lut_help_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.edit_lut_help_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 同类滤镜一排：标题 + 横向 chip 列表。
 * 三排分别对应三类输入曲线（普通照片 / S-Log2 / S-Log3），避免套错导致画面发灰或过冲。
 */
@Composable
private fun LutFilterGroup(
    title: String,
    options: List<LutFilterOption>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (options.isEmpty()) {
            Text(
                text = stringResource(R.string.edit_lut_group_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(options, key = { it.key }) { option ->
                    FilterChip(
                        selected = selectedKey == option.key,
                        onClick = { onSelect(option.key) },
                        label = { Text(option.label) }
                    )
                }
            }
        }
    }
}
