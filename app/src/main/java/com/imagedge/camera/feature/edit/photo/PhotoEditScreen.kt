package com.imagedge.camera.feature.edit.photo

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.lut.LutType
import com.imagedge.camera.image.Geometry
import com.imagedge.camera.image.NormRect
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppChip
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppDivider
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppTextField
import com.imagedge.camera.ui.components.ConfirmDialog
import com.imagedge.camera.ui.layout.EditorFrame
import com.imagedge.camera.ui.layout.EditorFrameState
import com.imagedge.camera.ui.layout.EditorBusy
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.components.Histogram
import com.imagedge.camera.ui.components.AppSlider
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.data.hdr.HdrExport
import com.imagedge.camera.ui.components.AppSwitchRow
import com.imagedge.camera.ui.components.ExportConfigControls
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.ViewerBackdrop
import com.imagedge.camera.ui.theme.OnViewer
import kotlin.math.roundToInt
import com.imagedge.camera.ui.components.AppIconButton

/**
 * 编辑调节 —— 相册的主编辑入口。
 *
 * 四个分区（同一时间只显示一组控件，避免一屏堆满滑条）：
 * - **调色**：基础参数按处理顺序排（曝光/色温/色调 → 高光/阴影 → 对比度 → 饱和度）
 *   + LUT 滤镜（三类输入曲线分开）+ 强度
 * - **裁剪**：比例预设（自由/1:1/4:3/3:2/16:9/9:16）+ 拖动裁剪框（框内拖动整体移动）
 * - **旋转**：左右 90°、水平/垂直翻转、拉直（-45..45，自动裁角）
 * - **导出**：格式 / 元数据与隐私 / 画质（与分享面板同一组控件）
 *
 * 预览：调色/旋转/导出分区显示最终结果（长按对比调色前）；裁剪分区显示未裁剪的底图 + 裁剪框，
 * 保证「框选的画面 = 导出的画面」。
 *
 * @param initialUri 由调用方指定的源图（例如下载页传已下载照片）；null 时用户自行选择
 */
@Composable
fun PhotoEditScreen(
    onBack: () -> Unit = {},
    initialUri: Uri? = null,
    viewModel: PhotoEditViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.loadPicked(it) } }

    // 带图进入（下载页「编辑」/相册编辑）：只在 URI 变化时加载一次
    LaunchedEffect(initialUri) {
        if (initialUri != null && state.sourceUri != initialUri) viewModel.loadPicked(initialUri)
    }

    // 预设列表：进页面时刷一次。列目录是磁盘 IO，所以只许走 viewModel.refreshPresets()
    // （它自己开到 Dispatchers.IO），不许在组合里直接读 viewModel.presets 背后的那个目录
    LaunchedEffect(Unit) { viewModel.refreshPresets() }

    // 说明弹窗开关：打开时标题栏与内容一起模糊，点背景退出
    var showHelp by remember { mutableStateOf(false) }
    val blurModifier = if (showHelp) Modifier.blur(12.dp) else Modifier

    Box(modifier = Modifier.fillMaxSize()) {
        // 统一编辑器骨架（批次 E，设计 §4.7）：返回/标题/重置 → 预览 → 工具区 → 保存副本。
        // 导出期间返回不假装取消、重置过确认、结果常驻——这三条由骨架统一保证
        EditorFrame(
            title = stringResource(R.string.edit_photo_title),
            state = EditorFrameState(
                hasSubject = state.hasImage,
                busy = when {
                    state.exporting -> EditorBusy.Exporting
                    state.processing -> EditorBusy.Preparing
                    else -> EditorBusy.None
                },
                canReset = state.hasEdits,
                hasEdits = state.hasEdits,
                saveVisible = state.hasImage,
                result = state.message,
                resultOk = state.saved
            ),
            onSave = { viewModel.save() },
            onReset = { viewModel.resetEdits() },
            onBack = onBack,
            modifier = blurModifier
        ) {
                PreviewArea(state = state, viewModel = viewModel, onPick = {
                    imagePicker.launch(arrayOf("image/*"))
                })

                if (state.hasImage) {
                    // 对比原图给一个**显式**按钮，长按只是快捷方式（设计 §4.7）：
                    // 长按是不可发现的，而且读屏用户拿不到它
                    if (state.tab != EditTab.CROP) {
                        AppLink(
                            text = stringResource(
                                if (state.comparing) R.string.editor_show_result
                                else R.string.editor_compare_original
                            ),
                            onClick = { viewModel.setComparing(!state.comparing) }
                        )
                    }

                    // ── 分区切换（互斥选项 ≤ 4 → AppChipRow）──
                    AppChipRow(
                        items = EditTab.entries.toList(),
                        selected = state.tab,
                        label = { it.label },
                        onSelect = viewModel::setTab
                    )

                    // 撤销/重做：一格 = 一次连续操作（提交时机见 PhotoEditViewModel.commitEdit）。
                    // 「刚载入」那一格由 loadPicked 调 seededHistoryOf 播下种子，所以第一次改动
                    // 就能退回它；那一格的内容是 carriedColour 带上来的调色与强度，不是空白
                    // （用例见 PhotoEditRecipeStateTest 的「seeded history points at…」）。
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        AppLink(
                            text = stringResource(R.string.editor_undo),
                            enabled = state.history.canUndo,
                            onClick = { viewModel.undoEdit() }
                        )
                        AppLink(
                            text = stringResource(R.string.editor_redo),
                            enabled = state.history.canRedo,
                            onClick = { viewModel.redoEdit() }
                        )
                    }

                    when (state.tab) {
                        EditTab.COLOR -> ColorPanel(
                            state = state,
                            filters = filters,
                            presets = presets,
                            viewModel = viewModel,
                            onShowHelp = { showHelp = true }
                        )

                        EditTab.CROP -> CropPanel(state = state, viewModel = viewModel)

                        EditTab.ROTATE -> RotatePanel(state = state, viewModel = viewModel)

                        EditTab.EXPORT -> ExportPanel(state = state, viewModel = viewModel)
                    }

                }
        }

        if (showHelp) {
            LutHelpOverlay(onDismiss = { showHelp = false })
        }
    }
}

/**
 * 预览区：调色/旋转/导出分区显示成品（长按对比调色前）；裁剪分区显示未裁剪底图 + 裁剪框。
 */
@Composable
private fun PreviewArea(
    state: PhotoEditState,
    viewModel: PhotoEditViewModel,
    onPick: () -> Unit
) {
    val image = when {
        state.tab == EditTab.CROP -> state.cropBase ?: state.original
        // 对比用 compareBase（构图已定、未调色）而不是裸原图：
        // 用户旋转过之后，拿裸原图来「对比」是两幅不同构图的画在比，等于没比
        state.comparing -> state.compareBase ?: state.original
        else -> state.filtered ?: state.original
    }
    val aspect = image?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
    // 竖图按宽度铺满会高过整屏，调色/裁剪/旋转三个 Tab 被推到屏幕外——
    // 实测 1080×2400 上预览占掉 290..1992，工具落在 2251，首页完全看不见它们。
    // 看不到有哪个工具，等于没有工具。
    // 高度上限必须落在**预览块自己**身上，并且保持照片比例：裁剪框是按这个 Box 的像素
    // 换算的，让照片在固定高度里 letterbox，裁剪就会偏。
    val previewHeight = with(LocalConfiguration.current) {
        (screenHeightDp * PREVIEW_HEIGHT_FRACTION).dp
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Box(
                modifier = Modifier
                    .heightIn(max = previewHeight)
                    .aspectRatio(aspect, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(Radius.Card))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    // 长按对比原图（裁剪分区不启用：那一屏看的是未裁剪底图）
                    .pointerInput(state.hasImage, state.tab) {
                        detectTapGestures(
                            onPress = {
                                if (!state.hasImage || state.tab == EditTab.CROP) {
                                    return@detectTapGestures
                                }
                                viewModel.setComparing(true)
                                tryAwaitRelease()
                                viewModel.setComparing(false)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.edit_title),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                if (state.tab == EditTab.CROP) {
                    CropOverlay(
                        rect = state.crop,
                        normTargetAspect = state.cropAspect.ratio?.let { it / state.cropBaseAspect },
                        onRectChange = viewModel::setCropRect,
                        // 一次拖动算一格：拖动过程中每次写框都不进历史，手势结束才提交
                        onDragFinished = viewModel::commitEdit
                    )
                }
                if (state.processing) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                }
                // 状态角标
                val badge = when {
                    state.tab == EditTab.CROP -> stringResource(R.string.edit_crop_hint)
                    state.comparing -> stringResource(R.string.edit_compare_original)
                    else -> stringResource(R.string.edit_compare_hint)
                }
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            RoundedCornerShape(Radius.Tag)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        } else {
            EmptyState(
                title = stringResource(R.string.edit_pick_hint),
                actionLabel = stringResource(R.string.edit_pick_image),
                onAction = onPick
            )
        }
    }
}

/** 调色分区：滤镜（缩略图）+ 强度 + 基础参数 + 预设 */
@Composable
private fun ColorPanel(
    state: PhotoEditState,
    filters: List<LutFilterOption>,
    presets: List<String>,
    viewModel: PhotoEditViewModel,
    onShowHelp: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
        AppSection(
            title = stringResource(R.string.edit_lut_filter_title),
            trailing = {
                AppIconButton(
                    icon = Lucide.CircleQuestionMark,
                    contentDescription = stringResource(R.string.edit_lut_help),
                    onClick = onShowHelp
                )
            }
        ) {
            // 三类输入曲线互不相通：分开排列，避免普通照片套 Log 还原 LUT 发灰、Log 灰片套创意 LUT 过冲
            LutFilterGroup(
                title = stringResource(R.string.edit_lut_group_creative),
                options = filters.filter { it.type == LutType.CREATIVE },
                selectedKey = state.selectedKey,
                thumbnails = state.thumbnails,
                onSelect = viewModel::selectFilter
            )
            LutFilterGroup(
                title = stringResource(R.string.edit_lut_group_slog2),
                options = filters.filter { it.type == LutType.SLOG2 },
                selectedKey = state.selectedKey,
                thumbnails = state.thumbnails,
                onSelect = viewModel::selectFilter
            )
            LutFilterGroup(
                title = stringResource(R.string.edit_lut_group_slog3),
                options = filters.filter { it.type == LutType.SLOG3 },
                selectedKey = state.selectedKey,
                thumbnails = state.thumbnails,
                onSelect = viewModel::selectFilter
            )
        }

        AppSection(
            title = stringResource(R.string.edit_adjust_title),
            trailing = {
                AppLink(
                    text = stringResource(R.string.edit_reset),
                    onClick = { viewModel.resetEdits() },
                    enabled = state.hasEdits
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                // 直方图在滑条之上：调高光/对比度时眼睛要同时在画面和形状之间来回看，
                // 放到面板底部等于每次都要滚下去确认
                Histogram(state.histogram)
                if (state.selectedKey != FILTER_NONE) {
                    AppSlider(
                        label = stringResource(R.string.edit_strength),
                        value = state.strength,
                        onValueChange = viewModel::setStrength,
                        // 松手才进历史：拖动过程中每个中间值都记一格的话，一次拖动就占满 64 格
                        onValueChangeFinished = viewModel::commitEdit,
                        range = 0..100,
                        valueSuffix = "%"
                    )
                }
                // 滑条顺序 = 实际处理顺序（见 ColorAdjust 的类注释）：
                // 曝光与色温/色调是同一次增益，先算；然后才是分区恢复与对比度。
                // 这三段之间是不可交换的（对比度绕 18% 灰做仿射），界面顺序与实际顺序不一致时，
                // 用户会看到「同样拉满、先动哪个」给出两张不同的照片，却没有任何地方解释。
                // 每条都要挂 onValueChangeFinished：一次拖动算一格历史（松手时提交），
                // 而不是让 200ms 防抖把每个中间值都记成一步。
                AppSlider(
                    label = stringResource(R.string.edit_exposure),
                    value = state.adjust.exposure,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(exposure = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
                AppSlider(
                    label = stringResource(R.string.edit_temperature),
                    value = state.adjust.temperature,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(temperature = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
                AppSlider(
                    label = stringResource(R.string.edit_tint),
                    value = state.adjust.tint,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(tint = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
                AppSlider(
                    label = stringResource(R.string.edit_highlights),
                    value = state.adjust.highlights,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(highlights = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
                AppSlider(
                    label = stringResource(R.string.edit_shadows),
                    value = state.adjust.shadows,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(shadows = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
                AppSlider(
                    label = stringResource(R.string.edit_contrast),
                    value = state.adjust.contrast,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(contrast = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
                AppSlider(
                    label = stringResource(R.string.edit_saturation),
                    value = state.adjust.saturation,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(saturation = it)) },
                    onValueChangeFinished = viewModel::commitEdit
                )
            }
        }

        // 预设区排在调色分区的**最后**：它存的正是这一屏的两样东西（参数 + 滤镜），
        // 而导出分区只有格式与元数据，摆在那儿等于把动作放到它管不到的东西后面
        PresetSection(
            presets = presets,
            nameTaken = viewModel::presetNameTaken,
            onSave = viewModel::savePreset,
            onApply = viewModel::applyPreset,
            onDelete = viewModel::deletePreset
        )
    }
}

/** 裁剪分区：比例预设 + 重置 */
@Composable
private fun CropPanel(state: PhotoEditState, viewModel: PhotoEditViewModel) {
    AppSection(
        title = stringResource(R.string.edit_crop_aspect),
        trailing = {
            AppLink(
                text = stringResource(R.string.edit_crop_reset),
                onClick = { viewModel.resetCrop() },
                enabled = !state.crop.isFull || state.cropAspect != CropAspect.FREE
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            // 比例预设 6 个 → 横向滚动，避免换行挤压预览
            AppChipRow(
                items = CropAspect.entries.toList(),
                selected = state.cropAspect,
                label = { it.label },
                onSelect = { viewModel.setCropAspect(it) },
                scrollable = true
            )
            Text(
                text = stringResource(R.string.edit_crop_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 旋转分区：90° 旋转、翻转、拉直 */
@Composable
private fun RotatePanel(state: PhotoEditState, viewModel: PhotoEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
        AppSection(title = stringResource(R.string.edit_rotate_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                AppChip(
                    label = stringResource(R.string.edit_rotate_left),
                    selected = false,
                    onClick = { viewModel.rotate(clockwise = false) },
                    role = Role.Button,
                    modifier = Modifier.weight(1f)
                )
                AppChip(
                    label = stringResource(R.string.edit_rotate_right),
                    selected = false,
                    onClick = { viewModel.rotate(clockwise = true) },
                    role = Role.Button,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        AppSection(title = stringResource(R.string.edit_flip_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                AppChip(
                    label = stringResource(R.string.edit_flip_h),
                    selected = state.flipHorizontal,
                    onClick = { viewModel.toggleFlipHorizontal() },
                    modifier = Modifier.weight(1f)
                )
                AppChip(
                    label = stringResource(R.string.edit_flip_v),
                    selected = state.flipVertical,
                    onClick = { viewModel.toggleFlipVertical() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        AppSection(
            title = stringResource(R.string.edit_straighten),
            trailing = {
                AppLink(
                    text = stringResource(R.string.edit_geometry_reset),
                    onClick = { viewModel.resetGeometry() },
                    enabled = state.hasGeometryEdits
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                AppSlider(
                    label = stringResource(R.string.edit_straighten),
                    value = state.straighten.roundToInt(),
                    onValueChange = { viewModel.setStraighten(it.toFloat()) },
                    // 与调色滑条同一条规矩：松手提交一格历史（见 ColorPanel 里的说明）
                    onValueChangeFinished = viewModel::commitEdit,
                    range = -45..45
                )
                Text(
                    text = stringResource(R.string.edit_straighten_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 预设区：把当前这份「调色 + 滤镜」存成一个可复用的名字，或把存过的名字套回这张照片。
 *
 * 横向列表用仓库里既有的形状 `LazyRow + items + AppChip`（与 [LutFilterGroup] 的滤镜行同一套）。
 * **不用 [AppChipRow]**：它没有逐项 modifier 的槽位，长按删除这个动作挂不上去；
 * 长按要走 `AppChip` 自己的触点（见它的 onLongClick 参数）——外面再套一层可点容器时，
 * AppChip 内部那个 clickable 会先吃掉 down 事件，长按永远收不到。
 *
 * 删除动的是预设库、不是这张照片的配方，所以它**不进撤销历史**（见 deletePreset），
 * 但它是会丢东西的决定，因此走 [ConfirmDialog] 确认，结果由全局横幅报出来（不是静默）。
 */
@Composable
private fun PresetSection(
    presets: List<String>,
    nameTaken: (String) -> Boolean,
    onSave: (String) -> Unit,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // rememberSaveable：切到裁剪再切回来时 ColorPanel 整个被 when 分支换掉，普通 remember
    // 会把用户刚打的预设名一起丢掉
    var name by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // 覆盖同名预设要单独问一次：删除都要确认，毁掉一份用户自己存的东西反而不问，
    // 这个不一致本身就是 bug
    var pendingOverwrite by remember { mutableStateOf<String?>(null) }

    AppSection(title = stringResource(R.string.edit_preset_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            if (presets.isEmpty()) {
                Text(
                    text = stringResource(R.string.edit_preset_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    // key 用名字本身：预设名就是它的文件身份，列表刷新后同一个名字不该换触点
                    items(presets, key = { it }) { preset ->
                        AppChip(
                            label = preset,
                            selected = false,
                            onClick = { onApply(preset) },
                            role = Role.Button,
                            onLongClick = { pendingDelete = preset },
                            onLongClickLabel = stringResource(R.string.edit_preset_delete_confirm)
                        )
                    }
                }
                // 长按是唯一的删除入口，就得把它写出来：不写等于没有这个入口
                Text(
                    text = stringResource(R.string.edit_preset_long_press_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.edit_preset_name_hint)
            )
            // 名字空着不给存：归一化后为空会被存储层拒掉，与其弹一条「名称无效」
            // 不如让按钮自己看起来就是按不动的
            AppButton(
                text = stringResource(R.string.edit_preset_save),
                onClick = {
                    val trimmed = name.trim()
                    // 撞名要按**落盘身份**问存储层（viewModel::presetNameTaken）：
                    // 界面上那份 presets 既晚一步又是归一化后的名字，拿输入框的原文去比，
                    // 「我的.v2」与已经存在的「我的v2」不相等 → 不弹确认直接覆盖
                    if (nameTaken(trimmed)) pendingOverwrite = trimmed
                    else onSave(trimmed)
                },
                type = AppButtonType.SECONDARY,
                enabled = name.isNotBlank()
            )
        }
    }

    pendingOverwrite?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.edit_preset_overwrite_title),
            body = stringResource(R.string.edit_preset_overwrite_body, target),
            confirmLabel = stringResource(R.string.edit_preset_overwrite_confirm),
            onDismiss = { pendingOverwrite = null },
            onConfirm = {
                pendingOverwrite = null
                onSave(target)
            }
        )
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.edit_preset_delete_title),
            body = stringResource(R.string.edit_preset_delete_body, target),
            confirmLabel = stringResource(R.string.edit_preset_delete_confirm),
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(target)
            }
        )
    }
}

/**
 * 导出分区：格式 / 元数据与隐私 / 画质。
 *
 * 用的是分享面板那套 [ExportConfigControls]，不是第二份实现：两处导出必须给出
 * 同一个结果，否则「在编辑器里选了清除位置、分享出去还带 GPS」这类差异
 * 只能靠用户记住每个入口的脾气。
 *
 * 没有尺寸档位：编辑器的导出走「全分辨率重算」，尺寸由那条路径按内存预算定，
 * 摆一个改了也没用的档位等于骗人。
 */
@Composable
private fun ExportPanel(state: PhotoEditState, viewModel: PhotoEditViewModel) {
    val config = state.exportConfig
    AppSection(title = stringResource(R.string.share_settings_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            ExportConfigControls(
                config = config,
                onFormatChange = { viewModel.setExportConfig(config.copy(format = it)) },
                onQualityChange = { viewModel.setExportConfig(config.copy(quality = it)) },
                onExifChange = { viewModel.setExportConfig(config.copy(exif = it)) }
            )
            // HDR 只在「真能做」时亮着，并把不能做的理由摆出来。
            // 摆一个亮着但按了没反应的开关，比不摆这个功能更坏——
            // 那是本文件顶部那条「改了也没用的档位等于骗人」的具体形态
            val hdrUnavailable = HdrExport.availability(
                Build.VERSION.SDK_INT, config.format, state.sourceHasGainMap
            )
            AppSwitchRow(
                title = stringResource(R.string.edit_hdr),
                subtitle = stringResource(R.string.edit_hdr_hint),
                checked = state.hdr,
                enabled = hdrUnavailable == null,
                onCheckedChange = viewModel::setHdr
            )
            if (hdrUnavailable != null) {
                Text(
                    text = hdrUnavailable.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.edit_export_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 裁剪框覆盖层：四块遮罩 + 三分线 + 四角手柄 + 框内拖动。
 *
 * 手势按「拖动起点快照 + 累计位移」计算：若直接以每次的 delta 增量更新并让
 * pointerInput 以 rect 为 key，重组的瞬间手势协程会被重启，拖动会卡住半路。
 *
 * [onDragFinished] 挂在 `onDragEnd` 与 `onDragCancel` 两处：拖动途中每一帧写的都是同一份
 * 配方槽位（见 `croppedRecipe` 的同类替换），一次手势结束提交一次，才是一格历史。
 * 取消也要提交——半路被打断时那几帧已经进了配方，不提交的话它会被并进下一次提交里，
 * 于是「一次拖动」忽而一格忽而零格。
 */
@Composable
private fun CropOverlay(
    rect: NormRect,
    normTargetAspect: Float?,
    onRectChange: (NormRect) -> Unit,
    onDragFinished: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val currentRect = rememberUpdatedState(rect)
        val currentOnChange = rememberUpdatedState(onRectChange)
        // 手势协程跑在 pointerInput 里，重组不会重启它，所以提交入口也要读最新那一份
        val currentCommit = rememberUpdatedState(onDragFinished)
        val density = LocalDensity.current
        // 手柄的**触摸目标**取 40dp（视觉圆点仍为 14dp）：28dp 的手指落点太苛刻，
        // 拖角时经常点不中
        val handleDp = 40.dp
        val handlePx = with(density) { handleDp.toPx() }

        val left = rect.left * wPx
        val top = rect.top * hPx
        val right = rect.right * wPx
        val bottom = rect.bottom * hPx

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scrim = ViewerBackdrop.copy(alpha = 0.55f)  // 沉浸层遮罩用纯黑 token（规范 §4.1 例外项）
            drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, top))
            drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
            drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
            drawRect(scrim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
            drawRect(
                color = OnViewer.copy(alpha = 0.95f),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 2f)
            )
            // 三分线
            val thirdW = (right - left) / 3f
            val thirdH = (bottom - top) / 3f
            for (i in 1..2) {
                drawLine(
                    OnViewer.copy(alpha = 0.35f),
                    Offset(left + thirdW * i, top), Offset(left + thirdW * i, bottom), 1f
                )
                drawLine(
                    OnViewer.copy(alpha = 0.35f),
                    Offset(left, top + thirdH * i), Offset(right, top + thirdH * i), 1f
                )
            }
        }

        // 框内拖动：整体移动裁剪框
        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(
                    with(density) { (right - left).toDp() },
                    with(density) { (bottom - top).toDp() }
                )
                .pointerInput(wPx, hPx) {
                    var base: NormRect? = null
                    var accX = 0f
                    var accY = 0f
                    detectDragGestures(
                        onDragStart = {
                            base = currentRect.value
                            accX = 0f
                            accY = 0f
                        },
                        // 拖动途中每次写都替换同一份 Crop 槽位；手势收尾时提交一格（见函数注释）
                        onDragEnd = { base = null; currentCommit.value() },
                        onDragCancel = { base = null; currentCommit.value() }
                    ) { change, drag ->
                        change.consume()
                        val start = base ?: return@detectDragGestures
                        accX += drag.x
                        accY += drag.y
                        currentOnChange.value(
                            Geometry.moveCrop(start, accX / wPx, accY / hPx)
                        )
                    }
                }
        )

        // 四角手柄：锁定比例时以对角为锚点按比例缩放
        Geometry.Corner.entries.forEach { corner ->
            val cornerX = if (corner == Geometry.Corner.TOP_RIGHT || corner == Geometry.Corner.BOTTOM_RIGHT) right else left
            val cornerY = if (corner == Geometry.Corner.BOTTOM_LEFT || corner == Geometry.Corner.BOTTOM_RIGHT) bottom else top
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (cornerX - handlePx / 2f).roundToInt(),
                            (cornerY - handlePx / 2f).roundToInt()
                        )
                    }
                    .size(handleDp)
                    .pointerInput(corner, normTargetAspect, wPx, hPx) {
                        var base: NormRect? = null
                        var accX = 0f
                        var accY = 0f
                        detectDragGestures(
                            onDragStart = {
                                base = currentRect.value
                                accX = 0f
                                accY = 0f
                            },
                            // 拖动途中每次写都替换同一份 Crop 槽位；手势收尾时提交一格（见函数注释）
                            onDragEnd = { base = null; currentCommit.value() },
                            onDragCancel = { base = null; currentCommit.value() }
                        ) { change, drag ->
                            change.consume()
                            val start = base ?: return@detectDragGestures
                            accX += drag.x
                            accY += drag.y
                            currentOnChange.value(
                                Geometry.resizeCrop(
                                    start, corner,
                                    accX / wPx, accY / hPx,
                                    normTargetAspect
                                )
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(OnViewer, CircleShape)
                        .border(2.dp, ViewerBackdrop, CircleShape)
                )
            }
        }
    }
}

/**
 * 同类滤镜一排：标题 + 横向**缩略图**列表（用用户自己的照片渲染）。
 * 三排分别对应三类输入曲线（普通照片 / S-Log2 / S-Log3）。
 */
@Composable
private fun LutFilterGroup(
    title: String,
    options: List<LutFilterOption>,
    selectedKey: String,
    thumbnails: Map<String, android.graphics.Bitmap>,
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                items(options, key = { it.key }) { option ->
                    val selected = selectedKey == option.key
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val thumb = thumbnails[option.key]
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(Radius.Tag))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(Radius.Tag)
                                    ) else Modifier
                                )
                                .clickable { onSelect(option.key) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = option.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(64.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
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
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.XL)
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

/** 预览最多占掉屏幕高度的这个比例，剩下的留给工具区与主按钮 */
private const val PREVIEW_HEIGHT_FRACTION = 0.42f
