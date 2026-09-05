package com.imagedge.camera.feature.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.glass.GlassSwitch
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.PageHeader
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.components.ResultMessage
import com.imagedge.camera.ui.theme.Radius

/**
 * LIVE 图三拼（批次 B，对标 DJI Mimo「Live 三拼」，两阶段流程）：
 *
 * **阶段一 · 归一化编辑**：三张实况图（横竖屏可混选）统一裁切比例（16:9/1:1/4:5），
 * 逐张重选封面帧、开关声音、调整对齐与顺序；
 * **阶段二 · 拼接预览**：三格竖排无缝拼图（所见即所得）+ 预估导出大小 → 生成。
 *
 * 整页可滚动（修复横竖屏混选时布局变形无法滑动的问题）。
 */
@Composable
fun LiveTriptychScreen(
    onBack: () -> Unit = {},
    viewModel: LiveTriptychViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 3)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onImagesPicked(uris)
    }

    Scaffold(
        topBar = { PageHeader(title = "LIVE 图三拼", onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                state.parsing || state.exporting -> {
                    ProcessingView(message = state.progressText ?: "处理中…")
                }

                state.slots.size == 3 && state.phase == LiveTriptychViewModel.Phase.PREVIEW -> {
                    PreviewStage(state, viewModel)
                }

                state.slots.size == 3 -> {
                    EditStage(state, viewModel)
                }

                else -> {
                    if (state.message != null) {
                        ResultMessage(text = state.message.orEmpty(), ok = state.success)
                    }
                    EmptyState(
                        title = "LIVE 图三拼",
                        icon = Lucide.Images,
                        desc = "选择 3 张实况图（横竖屏均可），先统一裁切长宽比、重选封面、开关声音，再拼接为一张 LIVE 图",
                        actionLabel = "选择实况图",
                        onAction = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }
            }
        }
    }
}

/** 阶段一：统一比例 + 逐张封面/声音/对齐 */
@Composable
private fun EditStage(
    state: LiveTriptychViewModel.UiState,
    viewModel: LiveTriptychViewModel
) {
    // ── 全局统一长宽比 ──
    Text("统一长宽比（三张都将裁切到此比例）", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveTriptychViewModel.Aspect.entries.forEach { aspect ->
            FilterChip(
                selected = state.aspect == aspect,
                onClick = { viewModel.setAspect(aspect) },
                label = { Text(aspect.label) }
            )
        }
    }

    // ── 三张槽位：封面重选 / 声音 / 对齐 / 顺序 ──
    state.slots.forEachIndexed { index, slot ->
        SlotCard(index = index, slot = slot, viewModel = viewModel)
    }

    AppButton(
        text = "进入拼接预览",
        onClick = { viewModel.enterPreview() }
    )
}

/** 阶段二：拼图预览 + 预估大小 + 生成 */
@Composable
private fun PreviewStage(
    state: LiveTriptychViewModel.UiState,
    viewModel: LiveTriptychViewModel
) {
    val aspect = state.aspect
    val preview = state.previewBitmap
    if (preview != null) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = "三拼预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.targetW.toFloat() / (aspect.targetH * 3))
                .clip(RoundedCornerShape(Radius.Card))
        )
    } else if (state.previewLoading) {
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
    Text(
        text = "预估导出大小 ≈ %.1f MB".format(state.estimatedBytes / 1024.0 / 1024.0),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    AppButton(
        text = "生成三拼 LIVE 图",
        onClick = { viewModel.export() }
    )
    AppButton(
        text = "返回调整",
        onClick = { viewModel.backToEdit() },
        type = AppButtonType.SECONDARY
    )
}

@Composable
private fun SlotCard(
    index: Int,
    slot: LiveTriptychViewModel.TriptychSlot,
    viewModel: LiveTriptychViewModel
) {
    // 进入可视区时懒加载封面候选帧
    LaunchedEffect(index) { viewModel.loadCoverThumbs(index) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("第 ${index + 1} 格 · ${slot.displayName}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "源画面 ${slot.videoWidth}×${slot.videoHeight} · %.1fs".format(slot.videoDurationMs / 1000f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 顺序调整
                androidx.compose.material3.IconButton(onClick = { viewModel.moveUp(index) }, enabled = index > 0) {
                    Text("↑", style = MaterialTheme.typography.titleMedium)
                }
                androidx.compose.material3.IconButton(onClick = { viewModel.moveDown(index) }, enabled = index < 2) {
                    Text("↓", style = MaterialTheme.typography.titleMedium)
                }
            }

            // ── 封面候选帧条带（点选重选封面；高亮当前选择）──
            if (slot.coverThumbsLoading) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else if (slot.coverThumbs.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(slot.coverThumbs) { thumb ->
                        val selected = slot.coverTimeMs == thumb.timeMs
                        Image(
                            bitmap = thumb.bitmap.asImageBitmap(),
                            contentDescription = "封面候选帧",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 72.dp, height = 44.dp)
                                .clip(RoundedCornerShape(Radius.Tag))
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.Tag)
                                    ) else Modifier
                                )
                                .clickable { viewModel.setCover(index, thumb.timeMs) }
                        )
                    }
                }
            }
            // 当前封面来源提示 + 恢复原图封面
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (slot.coverTimeMs == null) "封面：原图静态画面" else "封面：已重选帧（候选条带高亮项）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (slot.coverTimeMs != null) {
                    Text(
                        text = "恢复原图",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.Tag))
                            .clickable { viewModel.resetCover(index) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }

            // ── 声音 + 对齐 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("声音", style = MaterialTheme.typography.bodySmall)
                GlassSwitch(
                    checked = slot.audioOn,
                    onCheckedChange = { viewModel.setAudioOn(index, it) },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
                listOf(
                    LiveTriptychViewModel.Alignment.TOP to "顶",
                    LiveTriptychViewModel.Alignment.CENTER to "中",
                    LiveTriptychViewModel.Alignment.BOTTOM to "底"
                ).forEach { (alignment, label) ->
                    FilterChip(
                        selected = slot.alignment == alignment,
                        onClick = { viewModel.setAlignment(index, alignment) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
    }
}
