package com.imagedge.camera.feature.viewer

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.imagedge.camera.R
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.feature.share.ExportSettingsSheet
import com.imagedge.camera.feature.share.ShareViewModel
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.theme.OnViewer
import com.imagedge.camera.ui.theme.PillShape
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.ViewerBackdrop
import java.io.File

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-28
 *     desc   : 大图查看器（设计 §4.4）：黑底、原比例、双指缩放、单击显隐工具、
 *              保存/编辑/分享三个有文字动作；编辑与分享永远走原图
 *     version: 2.0
 * </pre>
 */

/** 查看器一帧的全部输入。Route 负责收集，Screen 只画（设计 §8.7） */
data class ViewerState(
    val items: List<MediaItem>,
    val startIndex: Int?,
    val previews: Map<String, ImageBitmap>,
    val videoStates: Map<String, VideoDownloadState>,
    val savedUris: Map<String, Uri>,
    val prepare: ViewerViewModel.PrepareState?
)

sealed interface ViewerAction {
    data object Back : ViewerAction
    data object ToggleChrome : ViewerAction
    data class Save(val item: MediaItem) : ViewerAction
    data class Edit(val item: MediaItem) : ViewerAction
    data class Share(val item: MediaItem) : ViewerAction
    data class PlayVideo(val item: MediaItem) : ViewerAction
    data object CancelPrepare : ViewerAction
}

/**
 * 查看器的接线层：拿 ViewModel、收状态、接导航与系统回调（设计 §8.7）。
 *
 * [onEdit] / [onShare] 只在**原图已经落盘**之后被调用——预览位图从不外流。
 */
@Composable
fun ViewerRoute(
    onBack: () -> Unit,
    onEdit: (Uri) -> Unit,
    snackbarController: SnackbarController,
    viewModel: ViewerViewModel = hiltViewModel(),
    shareViewModel: ShareViewModel = hiltViewModel()
) {
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val videoStates by viewModel.videoStates.collectAsStateWithLifecycle()
    val prepare by viewModel.prepare.collectAsStateWithLifecycle()
    val tasks by viewModel.savedUrisFlow().collectAsStateWithLifecycle(initialValue = emptyMap())
    var shareUri by remember { mutableStateOf<Uri?>(null) }

    // 原图就绪 → 继续原意图。一次性消费，旋转/重组不会重复打开编辑器
    LaunchedEffect(prepare?.readyUri) {
        viewModel.consumeReadyUri()?.let { (uri, intent) ->
            when (intent) {
                ViewerViewModel.ViewerIntent.Edit -> onEdit(uri)
                // 分享复用导出面板：格式、EXIF 与目标目录都在那里说清楚，
                // 查看器不再自己拼一套 ACTION_SEND（§7「不重复创建」）
                ViewerViewModel.ViewerIntent.Share -> shareUri = uri
            }
        }
    }

    shareUri?.let { uri ->
        ExportSettingsSheet(
            viewModel = shareViewModel,
            onDismiss = { shareUri = null }
        )
    }

    ViewerScreen(
        state = ViewerState(
            items = viewModel.items,
            startIndex = viewModel.startIndex,
            previews = previews,
            videoStates = videoStates,
            savedUris = tasks,
            prepare = prepare
        ),
        onAction = { action ->
            when (action) {
                ViewerAction.Back -> onBack()
                ViewerAction.ToggleChrome -> Unit // 纯界面状态，Screen 自己管
                is ViewerAction.Save -> {
                    viewModel.enqueueDownload(action.item)
                }

                is ViewerAction.Edit ->
                    viewModel.requestLocalCopy(action.item, ViewerViewModel.ViewerIntent.Edit)

                is ViewerAction.Share ->
                    viewModel.requestLocalCopy(action.item, ViewerViewModel.ViewerIntent.Share)

                is ViewerAction.PlayVideo -> viewModel.loadVideo(action.item)
                ViewerAction.CancelPrepare -> viewModel.cancelPrepare()
            }
        }
    )
}

/**
 * 纯展示层。黑底、照片按原比例 fit、双指缩放；上下工具区单击显隐。
 *
 * 工具隐藏时**返回与读屏仍然可达**：系统返回不依赖工具条，
 * 而画面本身带一个「显示工具」的语义动作，读屏用户不必靠猜。
 */
@Composable
fun ViewerScreen(
    state: ViewerState,
    onAction: (ViewerAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var chromeVisible by remember { mutableStateOf(true) }
    val pagerState = rememberPagerState(
        initialPage = state.startIndex ?: 0,
        pageCount = { state.items.size }
    )

    Box(modifier = modifier.fillMaxSize().background(ViewerBackdrop)) {
        if (state.items.isEmpty() || state.startIndex == null) {
            // 会话刷新或进程重建后指纹对不上了：说清楚，不猜一张给用户看
            Column(
                modifier = Modifier.align(Alignment.Center).padding(Spacing.XL),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                Text(stringResource(R.string.viewer_stale), color = OnViewer)
                AppButton(
                    text = stringResource(R.string.viewer_back),
                    onClick = { onAction(ViewerAction.Back) },
                    fullWidth = false,
                    type = AppButtonType.SECONDARY
                )
            }
            return
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = state.items[page]
            ViewerPage(
                item = item,
                preview = state.previews[item.thumbKey],
                videoState = state.videoStates[item.thumbKey],
                onToggleChrome = { chromeVisible = !chromeVisible },
                onPlayVideo = { onAction(ViewerAction.PlayVideo(item)) }
            )
        }

        if (chromeVisible) {
            ViewerTopBar(
                item = state.items[pagerState.settledPage],
                page = pagerState.settledPage + 1,
                total = state.items.size,
                saved = state.savedUris.containsKey(state.items[pagerState.settledPage].thumbKey),
                onBack = { onAction(ViewerAction.Back) }
            )
            val current = state.items[pagerState.settledPage]
            if (current.photoType != PhotoType.VIDEO) {
                ViewerBottomBar(
                    item = current,
                    savedUri = state.savedUris[current.thumbKey],
                    onAction = onAction,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // 准备流程：编辑/分享前的「需先保存到手机」。取消只结束准备，pager 不动
        state.prepare?.let { prepare ->
            PreparePanel(
                prepare = prepare,
                onCancel = { onAction(ViewerAction.CancelPrepare) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 单页：图片（可缩放）或视频。单击切换工具条，双指缩放不触发单击 */
@Composable
private fun ViewerPage(
    item: MediaItem,
    preview: ImageBitmap?,
    videoState: VideoDownloadState?,
    onToggleChrome: () -> Unit,
    onPlayVideo: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = item.filename
                onClick {
                    onToggleChrome()
                    true
                }
            }
            .pointerInput(item.thumbKey) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    // 缩回 1 倍时把平移也归零，否则画面会停在一个看不见的偏移上
                    if (scale <= 1.01f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            }
            .pointerInput(item.thumbKey) {
                detectTapGestures(onTap = { onToggleChrome() })
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            item.photoType == PhotoType.VIDEO -> VideoPreview(
                item = item,
                state = videoState,
                onPlay = onPlayVideo
            )

            preview != null -> Image(
                bitmap = preview,
                contentDescription = item.filename,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )

            else -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                CircularProgressIndicator(color = OnViewer)
                Text(
                    text = stringResource(R.string.viewer_loading),
                    color = OnViewer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ViewerTopBar(
    item: MediaItem,
    page: Int,
    total: Int,
    saved: Boolean,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ViewerBackdrop.copy(alpha = 0.6f))
            .padding(horizontal = Spacing.S, vertical = Spacing.XS)
            .padding(top = 24.dp) // 状态栏让位：沉浸页自己避安全区（设计 §8.3）
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(
                icon = Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.viewer_back),
                onClick = onBack,
                modifier = Modifier
                    .padding(Spacing.XS)
                    .background(ViewerBackdrop.copy(alpha = 0.35f), PillShape)
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = Spacing.S)) {
                Text(
                    text = item.filename,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnViewer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatSize(item.sizeBytes)} · " +
                        stringResource(R.string.viewer_page, page, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnViewer.copy(alpha = 0.7f)
                )
            }
        }
        // 「相机预览 / 已保存到手机」必须明说：用户要能分清屏幕上这张是相机上的原图
        // 还是已经落进相册的副本（设计 §4.4）
        Text(
            text = stringResource(
                if (saved) R.string.viewer_marker_saved else R.string.viewer_marker_preview
            ),
            style = MaterialTheme.typography.labelSmall,
            color = OnViewer.copy(alpha = 0.8f),
            modifier = Modifier
                .padding(start = Spacing.XL, bottom = Spacing.XS)
                .clip(RoundedCornerShape(Radius.Tag))
                .background(ViewerBackdrop.copy(alpha = 0.5f))
                .padding(horizontal = Spacing.S, vertical = 2.dp)
        )
    }
}

/** 底部最多三个有文字动作（设计 §4.4）：保存 / 编辑 / 分享 */
@Composable
private fun ViewerBottomBar(
    item: MediaItem,
    savedUri: Uri?,
    onAction: (ViewerAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                ViewerBackdrop.copy(alpha = 0.6f),
                RoundedCornerShape(topStart = Radius.Card, topEnd = Radius.Card)
            )
            .padding(Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (savedUri == null) {
            AppButton(
                text = stringResource(R.string.viewer_download),
                onClick = { onAction(ViewerAction.Save(item)) },
                modifier = Modifier.weight(1f)
            )
        }
        // 编辑与分享永远要原图：没落盘时点它们进入准备流程，而不是拿预览位图凑
        AppLink(
            text = stringResource(R.string.edit_action),
            onClick = { onAction(ViewerAction.Edit(item)) },
            modifier = Modifier.weight(1f)
        )
        AppLink(
            text = stringResource(R.string.share_action),
            onClick = { onAction(ViewerAction.Share(item)) },
            modifier = Modifier.weight(1f)
        )
    }
}

/** 准备面板：进度 + 取消。取消只结束准备（设计 §4.4） */
@Composable
private fun PreparePanel(
    prepare: ViewerViewModel.PrepareState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.L)
            .clip(RoundedCornerShape(Radius.Container))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        Text(
            text = stringResource(R.string.viewer_need_save),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        prepare.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (prepare.error == null && prepare.readyUri == null) {
            LinearProgressIndicator(
                progress = { (prepare.progress ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(R.string.viewer_preparing, prepare.progress ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            AppLink(text = stringResource(R.string.viewer_prepare_cancel), onClick = onCancel)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes B"
}

/** 视频预览：未下载显示播放按钮，下载中进度条，完成 ExoPlayer 播放 */
@Composable
private fun VideoPreview(
    item: MediaItem,
    state: VideoDownloadState?,
    onPlay: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            is VideoDownloadState.Ready -> VideoPlayer(state.file, Modifier.fillMaxSize())
            is VideoDownloadState.Downloading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                CircularProgressIndicator(color = OnViewer)
                Text(
                    stringResource(R.string.viewer_video_downloading, state.progress),
                    color = OnViewer
                )
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(0.6f),
                    color = OnViewer
                )
            }

            is VideoDownloadState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                Text(
                    stringResource(R.string.viewer_video_failed, state.message),
                    color = OnViewer
                )
                AppButton(
                    text = stringResource(R.string.album_retry),
                    onClick = onPlay,
                    fullWidth = false,
                    type = AppButtonType.SECONDARY
                )
            }

            else -> AppButton(
                text = stringResource(R.string.viewer_video_play),
                onClick = onPlay,
                fullWidth = false,
                type = AppButtonType.SECONDARY
            )
        }
    }
}

/** ExoPlayer 视频播放器（播放本地缓存文件） */
@Composable
private fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(file) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
        modifier = modifier
    )
}
