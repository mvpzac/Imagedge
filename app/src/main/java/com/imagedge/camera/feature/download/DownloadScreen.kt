package com.imagedge.camera.feature.download

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.model.isActive
import com.imagedge.camera.data.transfer.DownloadHistoryEntity
import com.imagedge.camera.feature.edit.BasicEditScreen
import com.imagedge.camera.feature.share.ExportSettingsSheet
import com.imagedge.camera.feature.share.ShareViewModel
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.PageHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 下载页——分段切换「下载队列 / 传输记录」；历史记录长按查看详情
 *     version: 2.0
 * </pre>
 */

/**
 * 下载队列 + 传输记录屏幕
 * @param onBack 返回回调
 * @param onGoAlbum 空队列时跳回相册选片的回调
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit = {},
    onGoAlbum: () -> Unit = {},
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0=下载队列 1=传输记录
    var detail by remember { mutableStateOf<DownloadHistoryEntity?>(null) }
    // 分享（一站式闭环最后一环）：已完成的任务可导出并分享
    val shareViewModel: ShareViewModel = hiltViewModel()
    var showShareSheet by remember { mutableStateOf(false) }
    // 编辑（闭环第三环）：已完成的任务可进入基础调整
    var editTarget by remember { mutableStateOf<Uri?>(null) }

    val hasFinished = tasks.any { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }
    // 有进行中（排队/下载中）任务时提供「全部取消」：断链时不必逐个取消或杀进程
    val hasActive = tasks.any { it.state.isActive }

    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(R.string.download_title),
                onBack = onBack,
                actions = {
                    if (tab == 0) {
                        if (hasActive) {
                            TextButton(onClick = { viewModel.cancelAllActive() }) {
                                Text(stringResource(R.string.download_cancel_all))
                            }
                        }
                        if (hasFinished) {
                            TextButton(onClick = { viewModel.clearFinished() }) {
                                Text(stringResource(R.string.download_clear))
                            }
                        }
                    } else {
                        if (history.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearHistory() }) {
                                Text(stringResource(R.string.download_history_clear))
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 分段切换：下载队列 / 传输记录
            val segmentedColors = SegmentedButtonDefaults.colors(
                inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    colors = segmentedColors
                ) { Text(stringResource(R.string.download_queue_tab)) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    colors = segmentedColors
                ) { Text(stringResource(R.string.download_history_tab)) }
            }
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                // 下载队列
                if (tasks.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.download_empty),
                        icon = Lucide.Info,
                        desc = stringResource(R.string.download_empty_desc),
                        actionLabel = stringResource(R.string.download_empty_action),
                        onAction = onGoAlbum,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            DownloadTaskRow(
                                task = task,
                                onCancel = { viewModel.cancel(it) },
                                onShare = { target ->
                                    target.savedUri?.let { uri ->
                                        shareViewModel.prepare(listOf(uri))
                                        showShareSheet = true
                                    }
                                },
                                onEdit = { target ->
                                    target.savedUri?.let { editTarget = it }
                                }
                            )
                        }
                    }
                }
            } else {
                // 传输记录
                if (history.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.download_history_empty),
                        icon = Lucide.Info,
                        desc = stringResource(R.string.download_history_empty_desc),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history, key = { it.id }) { record ->
                            HistoryRow(
                                record = record,
                                onOpen = { detail = record }
                            )
                        }
                    }
                }
            }
        }
    }

    // 长按/点击传输记录条目 → 详情弹窗
    detail?.let { record ->
        HistoryDetailDialog(record = record, onDismiss = { detail = null })
    }

    // 分享：导出设置 → 系统分享面板
    if (showShareSheet) {
        ExportSettingsSheet(
            viewModel = shareViewModel,
            onDismiss = { showShareSheet = false }
        )
    }

    // 编辑：基础调整（覆盖当前页，返回即回到下载队列）
    editTarget?.let { uri ->
        BasicEditScreen(
            sourceUri = uri,
            onBack = { editTarget = null }
        )
    }
}

/**
 * 单个下载任务行
 * @param onCancel 取消该任务（仅进行中的任务会显示取消按钮）
 * @param onShare 分享该任务（仅已完成且拿到相册 Uri 的任务会显示）
 * @param onEdit 编辑该任务（同上，进入基础调整）
 */
@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onCancel: (DownloadTask) -> Unit = {},
    onShare: (DownloadTask) -> Unit = {},
    onEdit: (DownloadTask) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Container))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略图（有则显示，无则占位）
        val thumb = task.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = task.filename,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.Control)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(Radius.Control))
                    .background(MaterialTheme.colorScheme.surface)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = task.filename,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSize(task.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (task.state == DownloadState.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(4.dp)
                )
            }
        }

        // 状态标签
        Text(
            text = stateLabel(task),
            style = MaterialTheme.typography.bodySmall,
            color = when (task.state) {
                DownloadState.DONE -> MaterialTheme.colorScheme.onSurface
                DownloadState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        // 取消按钮：仅进行中（排队/下载中）可关闭。
        // 原先只能等它跑完或杀进程——相机断链时任务会一直卡在「下载中」，
        // 用户没有任何办法把它从队列里拿掉（真机反馈）。
        if (task.state.isActive) {
            androidx.compose.material3.IconButton(
                onClick = { onCancel(task) },
                modifier = Modifier.size(32.dp)
            ) {
                LucideIcon(
                    lucide = Lucide.X,
                    contentDescription = stringResource(R.string.download_cancel),
                    size = 16.dp
                )
            }
        }

        // 已完成且拿到相册 Uri 的任务：提供「编辑」与「分享」。
        // 这是一站式闭环的后两环——传完的照片可以直接调整并分享出去，
        // 不必先退出 App 再打开相册或第三方工具。
        if (task.state == DownloadState.DONE && task.savedUri != null) {
            androidx.compose.material3.TextButton(onClick = { onEdit(task) }) {
                Text(
                    text = stringResource(R.string.edit_action),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            androidx.compose.material3.TextButton(onClick = { onShare(task) }) {
                Text(
                    text = stringResource(R.string.share_action),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** 单条传输记录行（长按查看详情） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(record: DownloadHistoryEntity, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Container))
            .combinedClickable(onClick = onOpen, onLongClick = onOpen)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 状态图标：成功绿/失败红（图标 + 文字双保险）
        LucideIcon(
            if (record.success) Lucide.CircleCheck else Lucide.CircleX,
            contentDescription = null,
            size = 24.dp,
            tint = if (record.success) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.filename,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatDateTime(record.endTime)} · ${formatSize(record.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 传输记录详情弹窗：路径 / 开始时间 / 结束时间 / 相机型号 */
@Composable
private fun HistoryDetailDialog(record: DownloadHistoryEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = record.filename,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailLine(
                    label = stringResource(R.string.download_history_path),
                    value = record.savedPath.ifBlank { stringResource(R.string.download_history_unknown) }
                )
                DetailLine(
                    label = stringResource(R.string.download_history_start),
                    value = formatDateTime(record.startTime)
                )
                DetailLine(
                    label = stringResource(R.string.download_history_end),
                    value = formatDateTime(record.endTime)
                )
                DetailLine(
                    label = stringResource(R.string.download_history_camera),
                    value = record.cameraModel.ifBlank { stringResource(R.string.download_history_unknown) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.download_history_close)) }
        }
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 状态文案 */
private fun stateLabel(task: DownloadTask): String = when (task.state) {
    DownloadState.QUEUED -> "等待中"
    DownloadState.DOWNLOADING -> "${task.progress}%"
    DownloadState.DONE -> "完成"
    DownloadState.FAILED -> "失败"
    DownloadState.NOT_DOWNLOADED -> ""
}

/** 时间戳 → 可读时间 */
private fun formatDateTime(epochMs: Long): String = try {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
} catch (_: Exception) {
    ""
}

/** 字节数格式化 */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024.0)
    } else if (mb >= 1) {
        String.format("%.1f MB", mb)
    } else {
        String.format("%.1f KB", bytes / 1024.0)
    }
}
