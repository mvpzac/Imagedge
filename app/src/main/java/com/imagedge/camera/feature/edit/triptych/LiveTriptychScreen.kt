package com.imagedge.camera.feature.edit.triptych

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.TaskResultPanel
import com.imagedge.camera.ui.layout.EditorBusy
import com.imagedge.camera.ui.layout.EditorFrame
import com.imagedge.camera.ui.layout.EditorFrameState
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.glass.GlassSwitch
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.components.ResultMessage
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.components.AppIconButton

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

    // 三拼是分段生成的，骨架的主按钮写的就是「当前那一步」。
    // 各阶段自己再摆一个主按钮会撞出两个下一步，且点骨架那个会从阶段一直接导出、
    // 在完成页再生成一份——因为 export() 只认「三张素材齐了」，不认阶段。
    val inEditStage = state.slots.size == 3 && state.phase == LiveTriptychViewModel.Phase.EDIT
    val inPreviewStage = state.slots.size == 3 && state.phase == LiveTriptychViewModel.Phase.PREVIEW

    EditorFrame(
        title = "LIVE 图三拼",
        state = EditorFrameState(
            hasSubject = state.slots.isNotEmpty(),
            busy = when {
                state.exporting -> EditorBusy.Exporting
                state.parsing || state.previewLoading -> EditorBusy.Preparing
                else -> EditorBusy.None
            },
            // 三拼没有「只清调整不清素材」这一档：清空就是重新来过，
            // 所以标题栏不放重置，完成页上的「再拼一张」才是它的后继动作
            // 选图页与完成页没有「下一步」，主按钮因此不出现
            saveVisible = inEditStage || inPreviewStage,
            result = state.message,
            resultOk = state.success
        ),
        // 导出期间把文案让回骨架的「正在生成…」：那时这一步已经按下了，
        // 再写「生成三拼 LIVE 图」会让人以为没点到
        saveLabel = when {
            state.exporting -> ""
            inPreviewStage -> "生成三拼 LIVE 图"
            else -> "进入拼接预览"
        },
        onSave = { if (inPreviewStage) viewModel.export() else viewModel.enterPreview() },
        onBack = onBack
    ) {
            when {
                // 结果页：导出成功/失败都必须停留展示——v1 复用了预览页且不渲染 message，
                // 用户生成成功后看不到任何确认，失败也看不到原因，只能感觉「点了没反应」
                state.phase == LiveTriptychViewModel.Phase.DONE -> {
                    TaskResultPanel(
                        ok = state.success,
                        headline = state.message ?: if (state.success) "已生成 LIVE 图" else "生成失败",
                        location = state.exportName,
                        note = if (state.success) null else "三张素材与裁切都还留着，可以直接重试",
                        primaryLabel = "再拼一张",
                        onPrimary = { viewModel.startOver() }
                    )
                    val donePreview = state.previewBitmap
                    if (donePreview != null) {
                        Image(
                            bitmap = donePreview.asImageBitmap(),
                            contentDescription = "三拼结果",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(state.aspect.targetW.toFloat() / (state.aspect.targetH * 3))
                                .clip(RoundedCornerShape(Radius.Card))
                        )
                    }

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

/** 阶段一：统一比例 + 逐张封面/声音/对齐 */
@Composable
private fun EditStage(
    state: LiveTriptychViewModel.UiState,
    viewModel: LiveTriptychViewModel
) {
    // ── 全局统一长宽比 ──
    AppSection(title = "统一长宽比") {
        AppChipRow(
            items = LiveTriptychViewModel.Aspect.entries.toList(),
            selected = state.aspect,
            label = { it.label },
            onSelect = { viewModel.setAspect(it) }
        )
    }

    // ── 三张槽位：封面重选 / 声音 / 对齐 / 顺序 ──
    state.slots.forEachIndexed { index, slot ->
        SlotCard(index = index, slot = slot, viewModel = viewModel)
    }
}

/** 阶段二：拼图预览 + 预估大小 + 生成 */
@Composable
private fun PreviewStage(
    state: LiveTriptychViewModel.UiState,
    viewModel: LiveTriptychViewModel
) {
    val aspect = state.aspect
    val preview = state.previewBitmap
    // 失败信息必须在预览页也能看到（原实现只在空态分支渲染 message）
    state.message?.let { ResultMessage(text = it, ok = state.success) }
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

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.M),
            verticalArrangement = Arrangement.spacedBy(Spacing.S)
        ) {
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
                AppIconButton(
                    icon = Lucide.ArrowUp,
                    contentDescription = "上移该格",
                    onClick = { viewModel.moveUp(index) },
                    enabled = index > 0
                )
                AppIconButton(
                    icon = Lucide.ArrowDown,
                    contentDescription = "下移该格",
                    onClick = { viewModel.moveDown(index) },
                    enabled = index < 2
                )
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
                                // 触控目标 ≥ 48dp（规范 §8.1）：高度从 44 提到 48
                                .size(width = 72.dp, height = 48.dp)
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
                    AppLink(
                        text = "恢复原图",
                        onClick = { viewModel.resetCover(index) }
                    )
                }
            }

            // ── 声音 + 对齐 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("声音", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                GlassSwitch(
                    checked = slot.audioOn,
                    onCheckedChange = { viewModel.setAudioOn(index, it) }
                )
            }
            // 裁切对齐（该格在统一比例下保上/中/下）
            AppChipRow(
                items = LiveTriptychViewModel.Alignment.entries.toList(),
                selected = slot.alignment,
                label = { alignment -> alignment.label },
                onSelect = { viewModel.setAlignment(index, it) }
            )
        }
    }
}
