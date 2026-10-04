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
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.TaskResultPanel
import com.imagedge.camera.ui.layout.EditorBusy
import com.imagedge.camera.ui.layout.EditorFrame
import com.imagedge.camera.ui.layout.EditorFrameState
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize
import com.imagedge.camera.ui.glass.GlassSwitch
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.components.AppIconButton

/**
 * LIVE 图三拼（批次 B，对标 DJI Mimo「Live 三拼」，单阶段 + 常驻预览）：
 *
 * 三张实况图（横竖屏可混选）统一裁切比例（16:9/1:1/4:5/27:16），逐张重选封面帧、
 * 开关声音、调整对齐与顺序；三格竖排拼图常驻在参数区上方，参数一改就重建，
 * 主按钮直接导出。没有「先编辑、再进入预览」这一道门。
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

    // 三拼是分段生成的（解析 → 裁切转码 → 拼接 → 合成），骨架的主按钮写的就是「当前那一步」。
    // 参数区自己再摆一个主按钮会撞出两个下一步。
    val ready = state.slots.size == 3
    // 结果页**不出现**骨架主按钮：那里已经有一个 PRIMARY（「再拼一张」），
    // 再摆一个「生成三拼 LIVE 图」就是同屏两个主操作（UI-SPEC §2），
    // 而且那一个还会在同一批素材上再跑一遍流水线、相册里多出一份文件。
    // 上一轮同一个缺陷复发过一次（CHANGELOG「三拼的骨架主按钮和阶段主按钮撞车」）。
    // `export()` 里也有一道同样的拒绝（`done` 即拒绝），不是只靠这一行挡。
    val editing = ready && !state.done

    EditorFrame(
        title = "LIVE 图三拼",
        state = EditorFrameState(
            hasSubject = state.slots.isNotEmpty(),
            // 选好的三张素材与裁切就是「没存盘的改动」；导出完成后产物已经落盘，离开不必再问
            hasEdits = state.slots.isNotEmpty() && !state.done,
            busy = when {
                state.exporting -> EditorBusy.Exporting
                state.parsing || state.previewLoading -> EditorBusy.Preparing
                else -> EditorBusy.None
            },
            // 三拼没有「只清调整不清素材」这一档：清空就是重新来过，
            // 所以标题栏不放重置，完成页上的「再拼一张」才是它的后继动作
            // 选图页与完成页没有「下一步」，主按钮因此不出现
            saveVisible = editing,
            result = state.message,
            resultOk = state.success
        ),
        // 导出期间把文案让回骨架的「正在生成…」：那时这一步已经按下了，
        // 再写「生成三拼 LIVE 图」会让人以为没点到
        saveLabel = if (state.exporting) "" else "生成三拼 LIVE 图",
        onSave = { viewModel.export() },
        onBack = onBack
    ) {
            when {
                // 结果页：导出成功/失败都必须停留展示——v1 复用了预览页且不渲染 message，
                // 用户生成成功后看不到任何确认，失败也看不到原因，只能感觉「点了没反应」
                state.done -> {
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
                                .aspectRatio(canvasAspect(state))
                                .clip(RoundedCornerShape(Radius.Card))
                        )
                    }

                }

                ready -> {
                    ResidentPreview(state)
                    EditStage(state, viewModel)
                }

                else -> {
                    // 同样不渲染 state.message——骨架已经渲染（见 ResidentPreview 里的同一条说明）
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

/**
 * 成品的长宽比。走 [triptychCanvasSize] 而不是 `aspect.refW / (aspect.refH * 3)`：
 * 画布尺寸由 `cellSize(quality)` 决定，UI 再抄一份 `refW/refH` 就是在两处各写一个真源。
 */
private fun canvasAspect(state: LiveTriptychViewModel.UiState): Float {
    val canvas = triptychCanvasSize(state.aspect, state.quality)
    return canvas.width.toFloat() / canvas.height
}

/** 参数区：统一比例 + 逐格封面/声音/对齐/顺序。预览常驻在它上面 */
@Composable
private fun EditStage(
    state: LiveTriptychViewModel.UiState,
    viewModel: LiveTriptychViewModel
) {
    // ── 全局统一长宽比 ──
    AppSection(title = "统一长宽比") {
        AppChipRow(
            items = Aspect.entries.toList(),
            selected = state.aspect,
            // 三格固定：标签要连成品比例一起标，否则用户只能自己算纵向堆叠的结果。
            // 标签变长了就必须改横向滚动：四个长文案挤在一行等宽分栏里会各自截断
            label = { it.label(3) },
            onSelect = { viewModel.setAspect(it) },
            scrollable = true
        )
    }

    // ── 三张槽位：封面重选 / 声音 / 对齐 / 顺序 ──
    state.slots.forEachIndexed { index, slot ->
        SlotCard(index = index, slot = slot, viewModel = viewModel)
    }
}

/**
 * 常驻预览：所见即所得的三格拼图 + 预估大小。**没有「返回调整」**——
 * 它本来就和参数区在同一页上，退回去是无处可退的第二状态。
 */
@Composable
private fun ResidentPreview(state: LiveTriptychViewModel.UiState) {
    val preview = state.previewBitmap
    // 这里**不**再渲染 state.message：骨架的 `EditorFrameState.result` 已经把它渲染在
    // 内容之上（EditorFrame.kt:165），页面里再渲染一次就是同屏两条一样的失败信息
    if (preview != null) {
        Image(
            bitmap = preview.asImageBitmap(),
            contentDescription = "三拼预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(canvasAspect(state))
                .clip(RoundedCornerShape(Radius.Card))
        )
    } else if (state.previewLoading) {
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
    // 估算值，不是编码器实测：码率还没落到编码器（Task 7），画质档当前只改分辨率。
    // 推导见 `estimateTriptychBytes`，那句「只按分辨率估」是这个数唯一能保证的事
    Text(
        text = "预估导出大小 ≈ %.1f MB（估算；当前画质档只改分辨率，码率尚未落到编码器）"
            .format(state.estimatedBytes / 1024.0 / 1024.0),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            // 高亮判据是 `coverMs == 该帧时刻`：`coverMs` 为 null（用原静态图）时
            // 九张都不高亮——包括时刻恰为 0 的第一张，那也是一个可被选中的合法时刻
            if (slot.coverThumbsLoading) {
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else if (slot.coverThumbs.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(slot.coverThumbs) { thumb ->
                        val selected = slot.clip.coverMs == thumb.timeMs
                        Image(
                            bitmap = thumb.bitmap.asImageBitmap(),
                            contentDescription = "封面候选帧",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                // 触控目标 ≥ 48dp（规范 §8.1）：高度从 44 提到 48
                                .size(width = 72.dp, height = UiSize.TouchMin)
                                .clip(RoundedCornerShape(Radius.Tag))
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.Tag)
                                    ) else Modifier
                                )
                                .clickable { viewModel.setSpec(index, slot.clip.copy(coverMs = thumb.timeMs)) }
                        )
                    }
                }
            }
            // 当前封面来源提示 + 恢复原图封面。
            // 判据是 `null`（未重选）而不是 0：0 是候选条带第一格的合法封面时刻，
            // 拿它当「未重选」会让点中第一张的用户看到「已恢复原图」的结果
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (slot.clip.coverMs == null) "封面：原图静态画面" else "封面：已重选帧（候选条带高亮项）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (slot.clip.coverMs != null) {
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
                    checked = slot.clip.audioOn,
                    onCheckedChange = { viewModel.setSpec(index, slot.clip.copy(audioOn = it)) }
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
