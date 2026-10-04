package com.imagedge.camera.feature.edit.triptych

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.imagedge.camera.feature.edit.clip.ClipFilmstrip
import com.imagedge.camera.feature.edit.clip.ClipMath
import com.imagedge.camera.feature.edit.clip.FilmstripHandle
import com.imagedge.camera.feature.edit.clip.coverOutOfRangeOf
import com.imagedge.camera.feature.edit.triptych.LiveTriptychViewModel.TriptychSlot
import com.imagedge.camera.feature.edit.triptych.LiveTriptychViewModel.TriptychTab
import com.imagedge.camera.feature.edit.triptych.LiveTriptychViewModel.UiState
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.components.AppSwitchRow
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.ResultMessage
import com.imagedge.camera.ui.components.TaskResultPanel
import com.imagedge.camera.ui.layout.EditorBusy
import com.imagedge.camera.ui.layout.EditorFrame
import com.imagedge.camera.ui.layout.EditorFrameState
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

/**
 * LIVE 图三拼（批次 B，对标 DJI Mimo「Live 三拼」，单阶段 + 常驻预览）：
 *
 * 页面 = **常驻静止拼图预览**（上，参数一改就重建）+ **格选择条** + **四枚 tab**
 * （比例 / 本格 / 封面 / 声音，下）。三张实况图（横竖屏可混选）统一裁切比例
 * （16:9/1:1/4:5/27:16）；每格的选段、封面、声音、对齐**一次只编一格**，
 * 取代原先三张长卡片往下滚。主按钮（骨架那颗「生成三拼 LIVE 图」）直接导出。
 * 没有「先编辑、再进入预览」这一道门——两阶段与 `Phase` 一起删在 Task 4。
 *
 * 长按顺序播放（规格 §4.1）本轮**未接**：[TriptychPreview] 只有静止图，
 * 理由与缺的验证都写在那份 KDoc 里。整页可滚动（骨架负责；修复横竖屏混选时
 * 布局变形无法滑动的问题）。
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
        // 两阶段删掉后只剩「生成」一步，主按钮文案是常量；
        // 导出期间把文案让回骨架的「正在生成…」：那时这一步已经按下了，
        // 再写「生成三拼 LIVE 图」会让人以为没点到
        saveLabel = if (state.exporting) "" else "生成三拼 LIVE 图",
        onSave = { viewModel.export() },
        onBack = onBack
    ) {
        when {
            // 结果页：导出成功/失败都必须停留展示——v1 复用了预览页且不渲染 message，
            // 用户生成成功后看不到任何确认，失败也看不到原因，只能感觉「点了没反应」
            state.done -> ResultStage(state, viewModel)

            ready -> {
                // 缩略图是「本格」与「封面」两 tab 的 filmstrip 共用的，选定哪格就装哪格的。
                // 这里只保证**一半**：VM 的幂等只管「已经抽完或正在抽」的重复调用会早退，
                // 而 key 是 selectedIndex 的副作用在上移/下移后也会再跑一次（选中跟着格子走）。
                // 让「在途的那一路」不串格的是另一半——VM 抽完九帧按 slotId 写回
                // （`updatedBySlotId`），不是按当初那个下标；换成下标写回时，中途一次「上移」
                // 会把九帧画到交换后占据该下标的那一格上，而发起方永远停在 loading 态、
                // 下面的 `FilmstripArea` 先查 loading 就让它从此只显示转圈
                LaunchedEffect(state.selectedIndex) {
                    viewModel.loadCoverThumbs(state.selectedIndex)
                }
                TriptychPreview(
                    bitmap = state.previewBitmap,
                    loading = state.previewLoading,
                    canvas = triptychCanvasSize(state.aspect, state.quality),
                )
                CellSelector(state, viewModel)
                TabBar(selected = state.tab, onSelect = { viewModel.setTab(it) })
                when (state.tab) {
                    TriptychTab.ASPECT -> AspectTab(state, viewModel)
                    TriptychTab.CELL -> CellTab(state, viewModel)
                    TriptychTab.COVER -> CoverTab(state, viewModel)
                    TriptychTab.AUDIO -> AudioTab(state, viewModel)
                }
            }

            else -> {
                // 同样不渲染 state.message——骨架已经渲染（`EditorFrameState.result`
                // 走 EditorFrame.kt:165，排在 content 之前），同屏两条一样的话就是重复
                EmptyState(
                    title = "LIVE 图三拼",
                    icon = Lucide.Images,
                    desc = "选择 3 张实况图（横竖屏均可），统一裁切长宽比、逐格选段与选封面，再拼接为一张 LIVE 图",
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
 * 格选择条：三枚格 chip + 上移/下移（规范 §8.1 的 48dp 由 [AppIconButton] 自带）。
 *
 * **选中跟着移动的那格走**：`swapSlots` 只换槽位顺序、不动 `selectedIndex`，
 * 不跟的话刚按「上移」，下一屏编辑的就是**另一张素材**。
 * 长按拖拽排序仍留给下一轮（`Lucide` 无拖拽手柄图标，本轮不引入新图标）。
 */
@Composable
private fun CellSelector(state: UiState, viewModel: LiveTriptychViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        AppChipRow(
            items = state.slots.indices.toList(),
            selected = state.selectedIndex,
            label = { "第 ${it + 1} 格" },
            onSelect = { viewModel.select(it) },
            modifier = Modifier.weight(1f),
        )
        AppIconButton(
            icon = Lucide.ArrowUp,
            contentDescription = "上移当前格",
            onClick = {
                viewModel.moveUp(state.selectedIndex)
                viewModel.select(state.selectedIndex - 1)
            },
            enabled = state.selectedIndex > 0,
        )
        AppIconButton(
            icon = Lucide.ArrowDown,
            contentDescription = "下移当前格",
            onClick = {
                viewModel.moveDown(state.selectedIndex)
                viewModel.select(state.selectedIndex + 1)
            },
            enabled = state.selectedIndex < state.slots.lastIndex,
        )
    }
}

/** 参数区 tab 条。`TriptychTab` 此前的「暂无读取方」在本行结束（Task 5） */
@Composable
private fun TabBar(selected: TriptychTab, onSelect: (TriptychTab) -> Unit) {
    AppChipRow(
        items = TriptychTab.entries.toList(),
        selected = selected,
        label = { it.label },
        onSelect = onSelect,
    )
}

/** 全局比例 + 画质档位 + 预估。这些是**跨格**的参数，不需要选中某一格 */
@Composable
private fun AspectTab(state: UiState, viewModel: LiveTriptychViewModel) {
    AppSection(title = "统一长宽比") {
        AppChipRow(
            items = Aspect.entries.toList(),
            selected = state.aspect,
            // 三格固定：标签要连成品比例一起标，否则用户只能自己算纵向堆叠的结果。
            // 标签变长了就必须改横向滚动：四个长文案挤在一行等宽分栏里会各自截断
            label = { it.label(3) },
            onSelect = { viewModel.setAspect(it) },
            scrollable = true,
        )
    }
    AppSection(title = "导出画质") {
        AppChipRow(
            items = Quality.entries.toList(),
            selected = state.quality,
            label = { it.label },
            onSelect = { viewModel.setQuality(it) },
        )
    }
    // 读 `state.estimatedBytes`：VM 在预览构建收尾时算好存进状态（startPreviewBuild），
    // UI 不在重组里重新推导派生值——那会让这一行与拼图来自不同的取值批次。
    // 估算值，不是编码器实测：推导见 `estimateTriptychBytes`，那句「只按分辨率估」
    // 是这个数唯一能保证的事，文案不许改成更确定的说法
    Text(
        text = "预估导出大小 ≈ %.1f MB（估算；当前画质档只改分辨率，码率尚未落到编码器）"
            .format(state.estimatedBytes / 1024.0 / 1024.0),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 「本格」tab：这一格的选段 + 对齐。一次只编一格（规格 §4） */
@Composable
private fun CellTab(state: UiState, viewModel: LiveTriptychViewModel) {
    val slot = state.slots.getOrNull(state.selectedIndex) ?: return
    AppSection(title = "第 ${state.selectedIndex + 1} 格 · 选段") {
        SourceMeta(slot)
        FilmstripArea(
            slot = slot,
            index = state.selectedIndex,
            viewModel = viewModel,
            handles = setOf(FilmstripHandle.In, FilmstripHandle.Out),
        )
    }
    AlignmentRow(slot, state.aspect, state.selectedIndex, viewModel)
}

/** 源信息与当前选段：让用户知道自己在编哪一格、现在选到哪儿 */
@Composable
private fun SourceMeta(slot: TriptychSlot) {
    Text(
        text = "${slot.displayName} · 源画面 ${slot.videoWidth}×${slot.videoHeight} · %.1fs"
            .format(slot.videoDurationMs / 1000f),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 上限取 `ClipMath.MAX_CLIP_MS`（与导出侧 ClipBounds 同源），不是抄来的数字
    Text(
        text = "选段 %.1f–%.1fs（%.1fs · 上限 %.1fs）".format(
            slot.clip.startMs / 1000f,
            slot.clip.endMs / 1000f,
            slot.clip.durationMs / 1000f,
            ClipMath.MAX_CLIP_MS / 1000f,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * filmstrip 挂载区。`ClipFilmstrip` 对空缩略图**一个节点都不发**（它的 KDoc 要求
 * 调用方自带 loading/空态），所以这里必须把它挡在组件外面。
 */
@Composable
private fun FilmstripArea(
    slot: TriptychSlot,
    index: Int,
    viewModel: LiveTriptychViewModel,
    handles: Set<FilmstripHandle>,
) {
    when {
        slot.coverThumbsLoading -> Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        slot.coverThumbs.isEmpty() -> Text(
            // 9 帧全部抽不出：这条带子无法成立，也没有别的选段入口。
            // 直说不可用，而不是留一条看起来能拖的空白轨道
            text = "该素材缩略图抽帧失败，选段条暂不可用；可按初始选段直接导出，或重选素材",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> ClipFilmstrip(
            thumbs = slot.coverThumbs.map { it.bitmap },
            spec = slot.clip,
            durationMs = slot.videoDurationMs,
            enabled = handles,
            onSpecChange = { viewModel.setSpec(index, it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT),
        )
    }
}

/**
 * 「封面」tab：封面游标 + 候选条带。**游标拖不出「从无到有」**——`coverMs == null`
 * 时 [ClipFilmstrip] 把封面手柄整支摘掉（见 `handlesToRender`），所以 null→非空
 * 这一步只能由候选条带点选完成；条带不可省，它不是游标的冗余副本。
 */
@Composable
private fun CoverTab(state: UiState, viewModel: LiveTriptychViewModel) {
    val slot = state.slots.getOrNull(state.selectedIndex) ?: return
    val clip = slot.clip
    val outOfRange = coverOutOfRangeOf(clip)
    AppSection(title = "第 ${state.selectedIndex + 1} 格 · 封面") {
        FilmstripArea(
            slot = slot,
            index = state.selectedIndex,
            viewModel = viewModel,
            handles = setOf(FilmstripHandle.Cover),
        )
        if (outOfRange) {
            // 判定与 CoverMark 的颜色共用 coverOutOfRangeOf；`null` 走不进 outOfRange
            // 为 true 这支，`?.let` 只是让编译器闭嘴，不是第二处判定
            clip.coverMs?.let { cover ->
                val effective = ClipMath.effectiveCoverMs(clip.startMs, clip.endMs, cover)
                ResultMessage(
                    text = "封面在所选片段之外，导出时会落在 %.1f s 处".format(effective / 1000f),
                    ok = false,
                )
            }
        }
        CoverThumbsRow(slot, state.selectedIndex, viewModel)
        // 判据是 `null`（未重选）而不是 0：0 是候选条带第一格的合法封面时刻，
        // 拿它当「未重选」会让点中第一张的用户看到「已恢复原图」的结果
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (clip.coverMs == null) "封面：原图静态画面" else "封面：已重选帧（候选条带高亮项）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (clip.coverMs != null) {
                AppLink(
                    text = "恢复原图封面",
                    onClick = { viewModel.resetCover(state.selectedIndex) },
                )
            }
        }
    }
}

/**
 * 候选帧条带（9 帧均匀抽取，点选 = 把封面设到该时刻）。高亮判据
 * `coverMs == thumb.timeMs`：`coverMs` 为 null（用原静态图）时九张都不高亮——
 * 包括时刻恰为 0 的第一张，那也是一个可被选中的合法时刻。
 */
@Composable
private fun CoverThumbsRow(slot: TriptychSlot, index: Int, viewModel: LiveTriptychViewModel) {
    if (slot.coverThumbs.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            items(slot.coverThumbs) { thumb ->
                val selected = slot.clip.coverMs == thumb.timeMs
                Image(
                    bitmap = thumb.bitmap.asImageBitmap(),
                    contentDescription = "封面候选帧",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        // 触控目标 ≥ 48dp（规范 §8.1）
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
}

/** 「声音」tab：逐格开关。音频也走 `setSpec`——它是 `ClipSpec` 的一部分，不是独立状态 */
@Composable
private fun AudioTab(state: UiState, viewModel: LiveTriptychViewModel) {
    AppSection(title = "各格声音") {
        state.slots.forEachIndexed { index, slot ->
            AppSwitchRow(
                title = "第 ${index + 1} 格",
                subtitle = slot.displayName,
                checked = slot.clip.audioOn,
                onCheckedChange = { viewModel.setSpec(index, slot.clip.copy(audioOn = it)) },
            )
        }
    }
}

/**
 * 对齐只在**确实会裁切**时才显示，判据是 [alignmentMatters]——它同时看两路：
 * 拼图格子里那张**封面位图**（未重选封面时就是静态 JPEG 的
 * [TriptychSlot.imageWidth]/[TriptychSlot.imageHeight]）与导出时那三段**视频**
 * （`cropFractions` 用的 `videoWidth/videoHeight`）。哪一路会被纵向裁切，顶/中/底就还是活参数。
 *
 * **上一版只按 `videoWidth / videoHeight` 对目标比例判，那是拿一个数去判断另一件事**：
 * `cropToAspect` 裁的是解码出来的那张图，而小米一类实况图的静态帧与视频比例常见地不一致。
 * 视频 16:9 + 静态图 4:3 配 16:9 目标时，视频那一路等比、chip 被收起，**而那一格画面
 * 正在被纵向裁切**：用户先前选的「顶」还在生效，却再也没有入口能改。
 * `AlignmentRow` 的旧注释把这叫做「看不见」——它不是观感问题，是对一个生效中的参数失去控制。
 *
 * 等比容差现在与 `cropToAspect` 共用 VM 那份 `CROP_ASPECT_EPS`（此前这里私藏了一份
 * 同值的 `ALIGNMENT_HIDE_EPS`，两份常量就是下一次漂移的候选）。
 * 剩下的一格是**故意**偏保守的（chip 在、但对齐那一刻不改变任何一帧）：
 * 一枚用不上的按钮比一个摸不到的生效参数好——理由见 [alignmentMatters]。
 */
@Composable
private fun AlignmentRow(
    slot: TriptychSlot,
    aspect: Aspect,
    index: Int,
    viewModel: LiveTriptychViewModel,
) {
    if (!alignmentMatters(
            imageWidth = slot.imageWidth,
            imageHeight = slot.imageHeight,
            videoWidth = slot.videoWidth,
            videoHeight = slot.videoHeight,
            targetRatio = aspect.ratio,
        )
    ) return
    AppSection(title = "裁切对齐") {
        AppChipRow(
            items = LiveTriptychViewModel.Alignment.entries.toList(),
            selected = slot.alignment,
            label = { it.label },
            onSelect = { viewModel.setAlignment(index, it) },
        )
    }
}

/** 结果页。`state.message` 由骨架渲染（`EditorFrameState.result`），面板标题因此是常量——
 *  上一版把 message 又写进 headline，成功时同屏两行一样的话 */
@Composable
private fun ResultStage(state: UiState, viewModel: LiveTriptychViewModel) {
    TaskResultPanel(
        ok = state.success,
        headline = if (state.success) "已生成 LIVE 图" else "生成失败",
        location = state.exportName,
        note = if (state.success) null else "三张素材与裁切都还留着，可以直接重试",
        primaryLabel = "再拼一张",
        onPrimary = { viewModel.startOver() },
    )
    state.previewBitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "三拼结果",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(canvasAspect(state))
                .clip(RoundedCornerShape(Radius.Card)),
        )
    }
}

/**
 * 成品的长宽比。走 [triptychCanvasSize] 而不是 `aspect.refW / (aspect.refH * 3)`：
 * 画布尺寸由 `cellSize(quality)` 决定，UI 再抄一份 `refW/refH` 就是在两处各写一个真源。
 */
private fun canvasAspect(state: UiState): Float {
    val canvas = triptychCanvasSize(state.aspect, state.quality)
    return canvas.width.toFloat() / canvas.height
}

/**
 * 选段条与候选帧的挂载高度。**56 不是随手写的**：UI-SPEC §4.4
 * （`docs/UI-SPEC.md:121`）「列表行最小高度 56（含 48dp 触控目标）」，这条带子就是一条行。
 *
 * 但对**手柄**它只保证得住一个方向，别说成两个方向都够：
 * 命中判定在 density=1 的空间里按 `ClipMath.TOUCH_RADIUS_PX = 24f` 收半径，组件把指针坐标
 * 除以 density（`ClipFilmstrip` 里的 `hitScale`），所以那一圈换算回来就是 24dp 半径 /
 * 48dp 直径。竖直方向轨道高 56dp，一圈在竖直方向装得下 ✓；水平方向**只有手柄离轨道两端
 * 都不止 24dp 时**，那一圈才整个落在手指能按到的地方。
 *
 * **贴在轨道端点上的手柄因此只剩一半**：[initialClipSpec] 恒给 `startMs = 0L`
 * （`ClipMath.clampStart(0, …)` 的结果就是 0），而素材时长 ≤ `ClipMath.MAX_CLIP_MS`（5000ms）时
 * 两个钳制都塌到素材边界上——`endMs == 素材时长`，折算成像素就是
 * `msToTrackPx(duration, duration, trackPx) == trackPx`——于是
 * **每一格的默认态两个手柄都正好压在轨道左右两端**，各自的可命中带是 24dp × 56dp，
 * 水平那一维短于 §8.1（`docs/UI-SPEC.md:229`）要求的 48dp。
 * 素材长过 5s 时止手停在 5000ms 那个位置，只有它离轨道右端不止 24dp 时才恢复 48dp 直径
 * （10s 素材上它在正中、6s 素材上它已在右侧 1/6 处），起手任何时候都仍是半宽；
 * 封面手柄拖到 0ms 或素材末尾时同样退化成 24dp。
 * 溢出轨道外的那半边圆也够不回来：整条带子在 `EditorFrame` 的
 * `padding(horizontal = Spacing.L)`（16dp，见 `ui/theme/Spacing.kt:18`）之内，
 * 而 `pointerInput` 的 Box 就到轨道边缘为止。
 *
 * 要真把端点手柄做回 48dp，得让**输入区比可视条带更宽**（负 padding 外扩 `pointerInput`）
 * 或者给贴边手柄一份加宽的边缘命中带（动 `ClipMath.resolveZone` 的半径语义，而它与短片段
 * 重叠的那套判据由 `ClipMathTest` 三条命中测试与 `FilmstripLayoutTest` 三条定区测试钉着）
 * ——那两条都是在为一句注释掰布局，本轮不做，先把话说明白。
 */
private val STRIP_HEIGHT = 56.dp
