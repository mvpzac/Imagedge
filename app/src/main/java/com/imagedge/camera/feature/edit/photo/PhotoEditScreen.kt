package com.imagedge.camera.feature.edit.photo

import android.net.Uri
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.imagedge.camera.ui.components.AppChip
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppDivider
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.layout.EditorFrame
import com.imagedge.camera.ui.layout.EditorFrameState
import com.imagedge.camera.ui.layout.EditorBusy
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.components.AppSlider
import com.imagedge.camera.ui.components.EmptyState
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
 * 三个分区（同一时间只显示一组控件，避免一屏堆满滑条）：
 * - **调色**：基础参数（曝光/对比度/饱和度/色温）+ LUT 滤镜（三类输入曲线分开）+ 强度
 * - **裁剪**：比例预设（自由/1:1/4:3/3:2/16:9/9:16）+ 拖动裁剪框（框内拖动整体移动）
 * - **旋转**：左右 90°、水平/垂直翻转、拉直（-45..45，自动裁角）
 *
 * 预览：调色/旋转分区显示最终结果（长按对比原图）；裁剪分区显示未裁剪的底图 + 裁剪框，
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

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.loadPicked(it) } }

    // 带图进入（下载页「编辑」/相册编辑）：只在 URI 变化时加载一次
    LaunchedEffect(initialUri) {
        if (initialUri != null && state.sourceUri != initialUri) viewModel.loadPicked(initialUri)
    }

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

                    when (state.tab) {
                        EditTab.COLOR -> ColorPanel(
                            state = state,
                            filters = filters,
                            viewModel = viewModel,
                            onShowHelp = { showHelp = true }
                        )

                        EditTab.CROP -> CropPanel(state = state, viewModel = viewModel)

                        EditTab.ROTATE -> RotatePanel(state = state, viewModel = viewModel)
                    }

                }
        }

        if (showHelp) {
            LutHelpOverlay(onDismiss = { showHelp = false })
        }
    }
}

/**
 * 预览区：调色/旋转分区显示成品（长按对比原图）；裁剪分区显示未裁剪底图 + 裁剪框。
 */
@Composable
private fun PreviewArea(
    state: PhotoEditState,
    viewModel: PhotoEditViewModel,
    onPick: () -> Unit
) {
    val image = when {
        state.tab == EditTab.CROP -> state.cropBase ?: state.original
        state.comparing -> state.original
        else -> state.filtered ?: state.original
    }
    val aspect = image?.let { it.width.toFloat() / it.height } ?: (4f / 3f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
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
        if (image != null) {
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
                    onRectChange = viewModel::setCropRect
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
        } else {
            EmptyState(
                title = stringResource(R.string.edit_pick_hint),
                actionLabel = stringResource(R.string.edit_pick_image),
                onAction = onPick
            )
        }
    }
}

/** 调色分区：滤镜（缩略图）+ 强度 + 基础参数 */
@Composable
private fun ColorPanel(
    state: PhotoEditState,
    filters: List<LutFilterOption>,
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
                if (state.selectedKey != FILTER_NONE) {
                    AppSlider(
                        label = stringResource(R.string.edit_strength),
                        value = state.strength,
                        onValueChange = viewModel::setStrength,
                        range = 0..100,
                        valueSuffix = "%"
                    )
                }
                AppSlider(
                    label = stringResource(R.string.edit_exposure),
                    value = state.adjust.exposure,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(exposure = it)) }
                )
                AppSlider(
                    label = stringResource(R.string.edit_contrast),
                    value = state.adjust.contrast,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(contrast = it)) }
                )
                AppSlider(
                    label = stringResource(R.string.edit_saturation),
                    value = state.adjust.saturation,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(saturation = it)) }
                )
                AppSlider(
                    label = stringResource(R.string.edit_temperature),
                    value = state.adjust.temperature,
                    onValueChange = { viewModel.setAdjust(state.adjust.copy(temperature = it)) }
                )
            }
        }
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
 * 裁剪框覆盖层：四块遮罩 + 三分线 + 四角手柄 + 框内拖动。
 *
 * 手势按「拖动起点快照 + 累计位移」计算：若直接以每次的 delta 增量更新并让
 * pointerInput 以 rect 为 key，重组的瞬间手势协程会被重启，拖动会卡住半路。
 */
@Composable
private fun CropOverlay(
    rect: NormRect,
    normTargetAspect: Float?,
    onRectChange: (NormRect) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val currentRect = rememberUpdatedState(rect)
        val currentOnChange = rememberUpdatedState(onRectChange)
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
                        onDragEnd = { base = null },
                        onDragCancel = { base = null }
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
                            onDragEnd = { base = null },
                            onDragCancel = { base = null }
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
