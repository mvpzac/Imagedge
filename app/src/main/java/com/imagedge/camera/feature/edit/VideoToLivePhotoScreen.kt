package com.imagedge.camera.feature.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TextButton
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.PageHeader
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.theme.OnViewer
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.ViewerBackdrop

/**
 * 视频转 LIVE 图导出页（批次 A）：
 * 多选视频 → 逐个「时间线选段（≤5s）+ 段内选封面帧」→ 批量裁剪转码合成保存。
 * 文案暂硬编码中文（与控制页 StatusChip 同策略），后续可挪 strings.xml。
 */
@Composable
fun VideoToLivePhotoScreen(
    onBack: () -> Unit = {},
    viewModel: VideoToLivePhotoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onVideosPicked(uris)
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = "视频转 LIVE 图",
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                // ── 编辑态：选段 + 选封面 ──
                state.session != null -> SessionEditor(
                    viewModel = viewModel,
                )

                state.processing -> {
                    ProcessingView(message = state.progressText ?: "正在处理…")
                }

                state.doneCount > 0 || state.failCount > 0 -> {
                    EmptyState(
                        title = state.message.orEmpty(),
                        icon = if (state.failCount == 0) Lucide.CircleCheck else Lucide.TriangleAlert,
                        desc = "已保存到系统相册，可在各平台以「实况/动态照片」方式分享",
                        actionLabel = "继续导出",
                        onAction = {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                    )
                }

                else -> {
                    EmptyState(
                        title = "视频转 LIVE 图",
                        icon = Lucide.Video,
                        desc = "选取视频中最精彩的 1.5~5 秒，自选封面帧，一键转成可在国产主流手机与社交平台显示的实况照片",
                        actionLabel = "选择视频",
                        onAction = {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                    )
                    Text(
                        text = "导出的 LIVE 图在小红书、微信、微博等平台分享时，请开启对应的「实况 / 原图」开关",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 编辑会话（用户定义的交互模型）：
 * 1. 拖动滑条选定**关键点**（= 封面帧，选定后不动）；
 * 2. 关键点前后各 5s 以外视为删除（时间线上以暗色蒙层呈现）；
 * 3. 保留区间上出现**滑块窗口**（默认 5s，可选 4s/3s），必须包含关键点，
 *    整体左右拖动选定最终片段；
 * 4. 点「生成 LIVE 图」导出。
 */
@Composable
private fun SessionEditor(viewModel: VideoToLivePhotoViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = state.session ?: return
    val density = LocalDensity.current
    var previewing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = state.indexLabel ?: "编辑片段",
            style = MaterialTheme.typography.titleMedium
        )

        // ── 关键点帧预览（= 封面）──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(Radius.Card))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val preview = session.keyPreview
            when {
                preview != null -> Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "封面关键点帧",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                session.keyPreviewLoading -> CircularProgressIndicator(
                    Modifier.size(28.dp),
                    strokeWidth = 2.dp
                )
                else -> Text(
                    "拖动下方滑条选择封面关键点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 关键点滑条（封面锚点）──
        Text(
            text = "关键点 %.1fs —— 此帧将作为封面，选定后保持不动".format(session.keyMs / 1000f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (session.durationMs > 0) {
            Slider(
                value = session.keyMs.toFloat(),
                // 拖动中只更新锚点状态（窗口随动）；松手才抽帧刷新封面预览，
                // 避免 OPTION_CLOSEST 逐帧解码被高频触发
                onValueChange = { viewModel.setKeyPoint(it.toLong()) },
                onValueChangeFinished = { viewModel.refreshKeyPreview() },
                valueRange = 0f..session.durationMs.toFloat()
            )
        }

        // ── 时间线：缩略图 + 删除蒙层 + 滑块窗口 + 关键点竖线 ──
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(Radius.Tag))
        ) {
            val totalWidth = maxWidth
            val duration = session.durationMs.coerceAtLeast(1L)
            // 时间 → 宽度的线性换算（缩略图等分铺满，与时间轴 1:1 对齐）
            fun msToDp(ms: Long) = totalWidth * (ms.toFloat() / duration)

            Row(modifier = Modifier.matchParentSize()) {
                session.timelineThumbs.forEach { t ->
                    Image(
                        bitmap = t.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            if (!session.timelineLoading && session.keyMs >= 0) {
                // 左右删除蒙层（关键点 ±5s 以外）
                Box(
                    modifier = Modifier
                        .width(msToDp(session.keepStartMs))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(totalWidth - msToDp(session.keepEndMs))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                )

                // 滑块窗口：整体左右拖动（包含关键点的约束在 VM 内钳制）
                Box(
                    modifier = Modifier
                        .offset { IntOffset(msToDp(session.winStartMs).roundToPx(), 0) }
                        .width(msToDp(session.effectiveWindowMs))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.Tag))
                        .pointerInput(session.durationMs, session.effectiveWindowMs) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                val totalPx = with(density) { totalWidth.toPx() }
                                viewModel.dragWindowBy((dragAmount / totalPx * duration).toLong())
                            }
                        }
                )

                // 关键点竖线（封面锚点，永远在窗口内）
                Box(
                    modifier = Modifier
                        .offset { IntOffset((msToDp(session.keyMs) - 1.dp).roundToPx(), 0) }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.tertiary)
                )
            } else if (session.timelineLoading) {
                CircularProgressIndicator(
                    Modifier.size(20.dp).align(Alignment.Center),
                    strokeWidth = 2.dp
                )
            }
        }

        // ── 窗口时长档位 + 片段区间 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WINDOW_LEN_OPTIONS_MS.forEach { len ->
                FilterChip(
                    selected = session.windowLenMs == len,
                    onClick = { viewModel.setWindowLen(len) },
                    label = { Text("${len / 1000} 秒") }
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "%.1fs – %.1fs".format(session.winStartMs / 1000f, session.winEndMs / 1000f),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // ── 操作：预览（真实模拟 LIVE 图行为）→ 导出 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppButton(
                text = "预览",
                onClick = { previewing = true },
                modifier = Modifier.weight(1f),
                type = AppButtonType.SECONDARY,
                fullWidth = false
            )
            AppButton(
                text = if (state.pendingUris.isEmpty()) "生成 LIVE 图" else "完成本段",
                onClick = { viewModel.confirmSession() },
                modifier = Modifier.weight(1f),
                fullWidth = false
            )
        }
        AppButton(
            text = "放弃",
            onClick = { viewModel.cancelSession() },
            type = AppButtonType.SECONDARY
        )

        if (previewing) {
            ClipPreviewDialog(
                session = session,
                onDismiss = { previewing = false }
            )
        }
    }
}

/**
 * 导出前预览：真实模拟 LIVE 图的行为——
 * 静止时显示封面帧（ExoPlayer 裁剪窗口加载后 seek 到关键点并暂停），
 * **按住**画面从封面帧开始循环播放片段，**松手**回到封面帧。
 * 预览内容与导出产物完全同源（同一裁剪区间、同一封面时间戳）。
 */
@Composable
private fun ClipPreviewDialog(
    session: VideoToLivePhotoViewModel.EditSession,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 封面在裁剪后时间轴上的位置（= 关键点 - 窗口起点，必然 ∈ [0, 窗口长]）
    val coverOffsetMs = (session.keyMs - session.winStartMs).coerceIn(0L, session.effectiveWindowMs)

    val player = remember(session.uri, session.winStartMs, session.winEndMs) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(session.uri)
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(session.winStartMs)
                            .setEndPositionMs(session.winEndMs)
                            .build()
                    )
                    .build()
            )
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
            seekTo(coverOffsetMs)
            playWhenReady = false
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackdrop),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(coverOffsetMs) {
                        detectTapGestures(
                            onPress = {
                                // 按住：从封面帧开始播放（贴近实况图的真实行为）
                                player.seekTo(coverOffsetMs)
                                player.play()
                                try {
                                    awaitRelease()
                                } finally {
                                    // 松手：回到封面静止态
                                    player.pause()
                                    player.seekTo(coverOffsetMs)
                                }
                            }
                        )
                    }
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
            ) {
                // 预估：H.264 1080p 转码经验码率 10Mbps + 封面 JPEG ~0.5MB
                val estimatedMb = (session.effectiveWindowMs / 1000.0 * 10_000_000 / 8 +
                    0.5 * 1024 * 1024) / 1024 / 1024
                Text(
                    "预估导出大小 ≈ %.1f MB".format(estimatedMb),
                    color = OnViewer,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "按住画面播放片段 · 松手回到封面",
                    color = OnViewer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Text("关闭", color = OnViewer)
            }
        }
    }
}
