package com.imagedge.camera.feature.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.net.Uri
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import java.io.File

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 大图查看器——全屏左右翻页预览，顶部信息栏 + 底部下载按钮。
 *              RAW 走内嵌 JPEG 预览秒开；视频显示占位提示。
 *     version: 1.0
 * </pre>
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    onBack: () -> Unit = {},
    viewModel: PhotoViewerViewModel = hiltViewModel()
) {
    val items = viewModel.items
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val videoStates by viewModel.videoStates.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(
        initialPage = viewModel.startIndex,
        pageCount = { items.size }
    )

    // 翻页到达时加载该页预览（含预加载相邻一页）
    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        listOf(page - 1, page, page + 1)
            .filter { it in items.indices }
            .forEach { viewModel.loadPreview(items[it]) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            val current = items.getOrNull(pagerState.settledPage)
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = current?.filename ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = formatSize(current?.sizeBytes ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    // 半透明深色圆底衬：照片明暗不定，保证 primary 箭头在任意画面上可读
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    ) {
                        LucideIcon(
                            Lucide.ArrowLeft,
                            contentDescription = stringResource(R.string.viewer_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f)
                )
            )
        },
        bottomBar = {
            val current = items.getOrNull(pagerState.settledPage)
            if (current != null && current.photoType != PhotoType.VIDEO) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(onClick = { viewModel.enqueueDownload(current) }) {
                        Text(stringResource(R.string.viewer_download))
                    }
                }
            }
        }
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.viewer_no_items),
                    color = Color.White
                )
            }
            return@Scaffold
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            val item = items[page]
            val preview = previews[item.thumbKey] ?: viewModel.cachedPreview(item)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when {
                    item.photoType == PhotoType.VIDEO -> VideoPreview(
                        item = item,
                        state = videoStates[item.thumbKey],
                        onPlay = { viewModel.loadVideo(item) }
                    )
                    preview != null -> Image(
                        bitmap = preview,
                        contentDescription = item.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    // 网格缩略图垫底：点开瞬间可见，原图加载完成后自动替换
                    viewModel.gridPreview(item) != null -> Image(
                        bitmap = viewModel.gridPreview(item)!!,
                        contentDescription = item.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.viewer_loading),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(color = Color.White)
                Text("下载视频 ${state.progress}%", color = Color.White)
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(0.6f),
                    color = Color.White
                )
            }
            is VideoDownloadState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("下载失败：${state.message}", color = Color.White)
                Button(onClick = onPlay) { Text("重试") }
            }
            else -> Button(onClick = onPlay) { Text("播放视频") }
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
    DisposableEffect(file) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer } },
        modifier = modifier
    )
}
