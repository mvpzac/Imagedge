package com.imagedge.camera.feature.capture.monitoring

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.AspectMarker
import com.imagedge.camera.data.model.GridMode
import com.imagedge.camera.data.model.MonitoringSettings
import com.imagedge.camera.feature.capture.CameraControlViewModel
import com.imagedge.camera.feature.capture.IdentitySummary
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.theme.OnViewer
import com.imagedge.camera.ui.theme.PillShape
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.ViewerBackdrop

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 本地监看工作台（T1）：横屏全屏 · 镜像/旋转 · 网格/比例标记 · 双指放大 · 暂停 · 取景截图
 *     version: 1.0
 * </pre>
 */

/**
 * 监看工作台。
 *
 * 用 [Dialog] 承载（沿用 `VideoToLivePhotoScreen` 的 `ClipPreviewDialog` 先例）：
 * 它是**独立 window**，所以既能盖住整屏、又不需要把遥控页那棵 Scaffold 树重排成 Box 兄弟节点，
 * 系统返回也天然由 [Dialog.onDismissRequest] 承担。
 * 相应地，沉浸要作用在 **Dialog 自己的 window** 上（经 `DialogWindowProvider` 取），
 * 调 Activity 的 window 管不到它。
 *
 * 取景帧来自 ViewModel 的单一采集 Job：覆盖层与遥控页的嵌入预览共用同一条 60152 流，
 * 不会出现两条流打同一个 `@Singleton LiveViewClient`。
 *
 * 本屏幕上的一切工具**只影响监看显示**：不向相机发命令、不改相机文件、不进下载队列。
 * 放大是显示级数码放大，不是镜头变焦。
 */
@Composable
fun MonitoringWorkstation(
    viewModel: CameraControlViewModel,
    onExit: () -> Unit
) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 不让系统栏吃掉窗口布局：横屏沉浸下画面要真正铺满
            decorFitsSystemWindows = false
        )
    ) {
        WorkstationContent(viewModel = viewModel, onExit = onExit)
    }
}

@Composable
private fun WorkstationContent(
    viewModel: CameraControlViewModel,
    onExit: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.monitoringSettings.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()

    // LocalView 必须在组合期读，再交给 remember：在 remember 的计算 lambda 里
    // 直接写 LocalView.current 不是 composable 上下文
    val dialogView = LocalView.current
    val dialogWindow = remember(dialogView) {
        (dialogView.parent as? DialogWindowProvider)?.window
    }
    ImmersiveLandscape(dialogWindow)

    // 缩放/平移是会话内手势状态：跨启动保留反而反直觉，每次进工作台都从 1× 居中开始。
    // 用 state holder 而不是普通局部变量：手势回调必须读到「上一个事件写入的值」，
    // 若读组合期快照，一次捏合内的连续事件会互相覆盖（画面抖、缩放丢更新）。
    val zoom = remember { mutableFloatStateOf(ViewportTransform.MIN_ZOOM) }
    val pan = remember { mutableStateOf(Offset.Zero) }
    val currentSettings by rememberUpdatedState(settings)

    val transform = ViewportTransform(
        rotation = settings.rotation,
        mirrored = settings.mirrored,
        zoom = zoom.floatValue,
        pan = pan.value
    )
    val frameSize = frame?.let { Size(it.width.toFloat(), it.height.toFloat()) }
    val image = remember(frame) { frame?.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ViewerBackdrop)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, gesturePan, gestureZoom, _ ->
                        val source = frame ?: return@detectTransformGestures
                        val dimensions = Size(source.width.toFloat(), source.height.toFloat())
                        // PointerInputScope.size 是 IntSize，必须转 Float 才能进几何模型
                        val viewport = Size(size.width.toFloat(), size.height.toFloat())
                        val base = ViewportTransform(
                            rotation = currentSettings.rotation,
                            mirrored = currentSettings.mirrored,
                            zoom = zoom.floatValue,
                            pan = pan.value
                        )
                        // 先拖后缩；两者都走同一个 centerInViewport 夹取，不会各自漂移
                        val moved = base
                            .draggedBy(gesturePan, dimensions, viewport)
                            .zoomedBy(gestureZoom, centroid, dimensions, viewport)
                        zoom.floatValue = moved.effectiveZoom
                        pan.value = moved.pan
                    }
                }
        ) {
            val dimensions = frameSize
            val source = image
            if (dimensions == null || source == null) {
                // 没有画面时什么都不画（包括网格）——在空画面上画构图线是误导
                return@Canvas
            }
            val viewport = Size(this.size.width, this.size.height)
            val content = transform.contentRect(dimensions, viewport)
            if (content.width <= 0f || content.height <= 0f) return@Canvas

            drawFrame(source, transform, dimensions, viewport)
            drawMarkers(content, settings.gridMode, settings.aspectMarker)
        }

        // 状态提示只有这一处，居中显示：视线本来就在画面中间
        val hint = when {
            frame == null -> stringResource(R.string.monitoring_no_frame)
            state.viewfinderPaused -> stringResource(R.string.monitoring_paused)
            else -> state.message
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = OnViewer.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(ViewerBackdrop.copy(alpha = 0.45f), PillShape)
                    .padding(horizontal = Spacing.M, vertical = Spacing.S)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = Spacing.L, vertical = Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            // 全屏 Dialog 没有天然的可见出口（只能靠系统返回），必须给出明确的退出按钮
            AppIconButton(
                icon = Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.monitoring_exit),
                onClick = onExit,
                tint = OnViewer,
                modifier = Modifier.background(ViewerBackdrop.copy(alpha = 0.45f), PillShape)
            )
            // 相机身份：监看的是哪台机器、什么模式，出片不符时首先要能确认这一点。
            // 深色底上加一层薄暗衬：IdentitySummary 用的是主题的中性文字色，
            // 直接压在画面上对比度不够
            Box(
                modifier = Modifier
                    .background(ViewerBackdrop.copy(alpha = 0.45f), PillShape)
                    .padding(horizontal = Spacing.M, vertical = Spacing.XS)
            ) {
                IdentitySummary(state.identity)
            }
        }

        Toolbar(
            viewModel = viewModel,
            settings = settings,
            zoom = transform.effectiveZoom,
            paused = state.viewfinderPaused,
            snapshotEnabled = frame != null && !state.snapshotting,
            onResetView = {
                zoom.floatValue = ViewportTransform.MIN_ZOOM
                pan.value = Offset.Zero
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
        )
    }
}

/** 工作台工具栏：镜像 / 旋转 / 暂停 / 取景截图 + 网格、比例标记、缩放重置 */
@Composable
private fun Toolbar(
    viewModel: CameraControlViewModel,
    settings: MonitoringSettings,
    zoom: Float,
    paused: Boolean,
    snapshotEnabled: Boolean,
    onResetView: () -> Unit,
    modifier: Modifier = Modifier
) {
    var aidsOn by rememberSaveable { mutableStateOf(false) }
    val stats by viewModel.exposureStats.collectAsStateWithLifecycle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ViewerBackdrop.copy(alpha = 0.6f))
            .padding(horizontal = Spacing.L, vertical = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        val scrim = ViewerBackdrop.copy(alpha = 0.35f)
        AppIconButton(
            icon = Lucide.FlipHorizontal,
            contentDescription = stringResource(R.string.monitoring_mirror),
            onClick = { viewModel.setMirrored(!settings.mirrored) },
            tint = OnViewer,
            modifier = Modifier.background(scrim, PillShape)
        )
        AppIconButton(
            icon = Lucide.RefreshCw,
            contentDescription = stringResource(R.string.monitoring_rotate),
            onClick = viewModel::rotateView,
            tint = OnViewer,
            modifier = Modifier.background(scrim, PillShape)
        )
        AppIconButton(
            icon = if (paused) Lucide.Play else Lucide.Pause,
            contentDescription = stringResource(
                if (paused) R.string.monitoring_resume else R.string.monitoring_pause
            ),
            onClick = { viewModel.setViewfinderPaused(!paused) },
            tint = OnViewer,
            modifier = Modifier.background(scrim, PillShape)
        )
        AppIconButton(
            icon = Lucide.Camera,
            contentDescription = stringResource(R.string.monitoring_snapshot),
            onClick = viewModel::captureSnapshot,
            enabled = snapshotEnabled,
            tint = OnViewer,
            modifier = Modifier.background(scrim, PillShape)
        )
        // 曝光辅助（T2）：直方图与斑马纹。开着才有分析——停用时一次都不算
        AppIconButton(
            icon = Lucide.Aperture,
            contentDescription = stringResource(R.string.monitoring_exposure_aids),
            onClick = {
                val next = !aidsOn
                aidsOn = next
                viewModel.setExposureAids(next)
            },
            tint = if (aidsOn) MaterialTheme.colorScheme.primary else OnViewer,
            modifier = Modifier.background(scrim, PillShape)
        )
        if (aidsOn) {
            // 读数只是显示辅助：预览的色域/伽马未知，所以不写「EV」也不写「准确」
            Text(
                text = stats?.let {
                    stringResource(
                        R.string.monitoring_exposure_readout,
                        it.highlightRatio * 100f,
                        it.meanLuma
                    )
                } ?: stringResource(R.string.monitoring_exposure_no_data),
                style = MaterialTheme.typography.labelSmall,
                color = OnViewer.copy(alpha = 0.85f),
                modifier = Modifier
                    .weight(1f)
                    .background(scrim, PillShape)
                    .padding(horizontal = Spacing.S, vertical = Spacing.XS)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.XS)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                AppLink(
                    text = stringResource(gridLabelOf(settings.gridMode)),
                    onClick = viewModel::cycleGrid,
                    color = OnViewer
                )
                AppLink(
                    text = markerLabel(settings.aspectMarker),
                    onClick = viewModel::cycleAspectMarker,
                    color = OnViewer
                )
                AppLink(
                    text = stringResource(R.string.monitoring_zoom_reset, zoom),
                    onClick = onResetView,
                    color = OnViewer
                )
            }
            Text(
                text = stringResource(R.string.monitoring_tools_hint),
                style = MaterialTheme.typography.labelSmall,
                color = OnViewer.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun markerLabel(marker: AspectMarker): String {
    if (marker == AspectMarker.NONE) return stringResource(R.string.monitoring_marker_off)
    val ratio = stringResource(
        when (marker) {
            AspectMarker.R16_9 -> R.string.ratio_16_9
            AspectMarker.R17_9 -> R.string.ratio_17_9
            AspectMarker.R4_3 -> R.string.ratio_4_3
            AspectMarker.R3_2 -> R.string.ratio_3_2
            AspectMarker.R1_1 -> R.string.ratio_1_1
            AspectMarker.R235 -> R.string.ratio_235
            AspectMarker.NONE -> R.string.ratio_16_9
        }
    )
    return stringResource(R.string.monitoring_marker_fmt, ratio)
}

private fun gridLabelOf(mode: GridMode): Int = when (mode) {
    GridMode.NONE -> R.string.monitoring_grid_off
    GridMode.THIRD -> R.string.monitoring_grid_third
    GridMode.QUARTER -> R.string.monitoring_grid_quarter
}

/**
 * 把取景帧画到视口。
 *
 * **internal 而非工作台私有**：遥控页的嵌入预览必须走同一套变换，否则用户在
 * 工作台里转了 90°，退出后嵌入预览还是横的——两处各写一遍就一定会漂。
 *
 * **调用顺序必须与 [ViewportTransform] 的分解一致**。Compose 的 `withTransform` 里
 * 先调用的处于最外层，而点的变换顺序是自内向外，所以「镜像」这一行必须排在「旋转」**之前**，
 * 才能让镜像作用在旋转后的显示朝向上。顺序写反不会报错，只会「画面对、点偏」——
 * 那比崩溃难查得多，也是本包坚持绘制与命中共用一套变换的原因。
 */
internal fun DrawScope.drawFrame(
    image: ImageBitmap,
    transform: ViewportTransform,
    frameSize: Size,
    viewport: Size
) {
    val rect = transform.contentRect(frameSize, viewport)
    val scale = transform.fitScale(frameSize, viewport) * transform.effectiveZoom
    if (scale <= 0f || rect.width <= 0f) return

    withTransform({
        translate(rect.center.x, rect.center.y)
        if (transform.mirrored) scale(-1f, 1f)
        rotate(transform.rotation.degrees.toFloat())
        translate(-frameSize.width * scale / 2f, -frameSize.height * scale / 2f)
        scale(scale, scale)
    }) {
        drawImage(
            image = image,
            dstSize = IntSize(frameSize.width.toInt(), frameSize.height.toInt())
        )
    }
}

/**
 * 叠加网格与比例标记。
 *
 * 全部画在内容矩形**之内**：标记描述的是画面构图，铺到 letterbox 黑边上就成了假信息。
 * 只画线，不改像素，也不参与取景截图的输出。边框一并画出——否则留边与画面在深色背景上
 * 不易分辨，用户会把黑边也当成构图范围。
 */
internal fun DrawScope.drawMarkers(rect: Rect, gridMode: GridMode, aspect: AspectMarker) {
    val width = 1.dp.toPx()
    drawRect(
        color = OnViewer.copy(alpha = 0.22f),
        topLeft = Offset(rect.left, rect.top),
        size = rect.size,
        style = Stroke(width = width)
    )
    val line = OnViewer.copy(alpha = 0.5f)
    MonitoringMarkers.gridFractions(gridMode).forEach { fraction ->
        drawLine(
            color = line,
            start = Offset(rect.left + rect.width * fraction, rect.top),
            end = Offset(rect.left + rect.width * fraction, rect.bottom),
            strokeWidth = width
        )
        drawLine(
            color = line,
            start = Offset(rect.left, rect.top + rect.height * fraction),
            end = Offset(rect.right, rect.top + rect.height * fraction),
            strokeWidth = width
        )
    }
    MonitoringMarkers.aspectRect(rect, aspect)?.let { marker ->
        drawRect(
            color = OnViewer.copy(alpha = 0.8f),
            topLeft = Offset(marker.left, marker.top),
            size = marker.size,
            style = Stroke(width = width)
        )
    }
}

/**
 * 进入工作台切横屏 + 沉浸，离开时无条件还原。
 *
 * 横屏是 **Activity 级**属性（`requestedOrientation`），沉浸则是 **window 级**的——
 * 所以后者必须用工作台自己那个 Dialog window 的 controller；拿 Activity 的 window
 * 去隐藏系统栏，盖在上面的 Dialog 不受影响，结果是「以为隐藏了、其实还在」。
 *
 * 本仓库此前**没有任何 Compose→Activity/Window 的路径**（清单已声明 `orientation|screenSize`
 * 的 configChanges，旋转不重建 Activity，因此也不需要新的清单配置），这是第一处。
 * 还原写在 `onDispose`：无论退出工作台、离开遥控页还是组合被销毁，都不会把应用
 * 留在横屏或隐藏系统栏的状态里。
 *
 * 命名取大写开头：它是 @Composable 且无返回值，按 Compose 命名规范如此。
 * 本函数不产出任何界面，只为生命周期副作用存在。
 */
@Composable
internal fun ImmersiveLandscape(window: Window?) {
    val context = LocalContext.current
    DisposableEffect(window) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * 沿 ContextWrapper 找宿主 Activity。
 *
 * 不直接用 `LocalActivity`：它可能为 null。找不到就整段跳过横屏/沉浸切换——
 * 宁可少一个效果，也不在拿不到 window 的时候崩掉。
 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
