package com.imagedge.camera.feature.transfer

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.model.isActive
import com.imagedge.camera.data.transfer.DownloadHistoryEntity
import com.imagedge.camera.data.transfer.ResumeMode
import com.imagedge.camera.data.transfer.TransferBatchSummary
import com.imagedge.camera.data.transfer.TransferPolicy
import com.imagedge.camera.data.transfer.TransferSizeMode
import com.imagedge.camera.feature.edit.photo.PhotoEditScreen
import com.imagedge.camera.feature.share.ExportSettingsSheet
import com.imagedge.camera.feature.share.ShareViewModel
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.ConfirmDialog
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.StatusBanner
import com.imagedge.camera.ui.glass.glassDialog
import com.imagedge.camera.ui.glass.glassDialogContainerColor
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-27
 *     desc   : 传输页——批次摘要 + 队列 + 记录；记录行可打开、可重试（设计 §4.5）
 *     version: 3.0
 * </pre>
 */

/**
 * 「查看」走不通时的兜底现场。
 *
 * 为什么要在界面里带上 [path]：设计对这条的原文是「格式不可打开时提供文件位置或分享，
 * 不许静默失败」。打不开就是打不开，用户此刻最需要的不是道歉，是**那份文件在哪**。
 */
private data class OpenFallback(
    val filename: String,
    val location: String,
    /** true = 没有应用认领这个格式（还能分享给别的应用）；false = 读不到这份文件 */
    val noApp: Boolean,
    val shareUri: String?
)

/**
 * 传输页
 * @param onBack 返回回调
 * @param onGoAlbum 空队列时跳回照片页选片的回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferScreen(
    onBack: () -> Unit = {},
    onGoAlbum: () -> Unit = {},
    viewModel: TransferViewModel = hiltViewModel()
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val summary by viewModel.batchSummary.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0=进行中 1=记录
    var detail by remember { mutableStateOf<DownloadHistoryEntity?>(null) }
    var fallback by remember { mutableStateOf<OpenFallback?>(null) }
    // 分享（打不开时的第二条出路）：直接把已存的那份交给系统分享面板
    val shareViewModel: ShareViewModel = hiltViewModel()
    var showShareSheet by remember { mutableStateOf(false) }
    // 编辑：已完成的任务直接进编辑调节
    var editTarget by remember { mutableStateOf<Uri?>(null) }
    // 三个会丢东西的动作都先过确认，且各自说清实际影响（UI 规范 §5 / 新手手册 §6）：
    // 「清空」这个词本身分不出会不会碰到照片，用户不该靠猜
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }

    val hasFinished = tasks.any { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }
    // 有进行中（排队/下载中）任务时提供「全部取消」：断链时不必逐个取消或杀进程
    val hasActive = tasks.any { it.state.isActive }
    val policy by viewModel.transferPolicy.collectAsStateWithLifecycle()

    // 站到失败面前就算看过了：全局任务条的职责到此为止（设计 §4.5「尚未查看的失败」）
    LaunchedEffect(Unit) { viewModel.markBatchFailuresViewed() }

    AppScreenFrame(
        topBar = {
            AppPageHeader(
                title = stringResource(R.string.download_title),
                onBack = onBack,
                actions = {
                    if (tab == 0) {
                        if (hasActive) {
                            AppLink(
                                text = stringResource(R.string.download_cancel_all),
                                onClick = { pendingConfirm = PendingConfirm.CancelAll }
                            )
                        }
                        if (hasFinished) {
                            AppLink(
                                text = stringResource(R.string.download_clear),
                                onClick = { pendingConfirm = PendingConfirm.ClearFinished }
                            )
                        }
                    } else {
                        if (history.isNotEmpty()) {
                            AppLink(
                                text = stringResource(R.string.download_history_clear),
                                // 只清这本账，一张照片都不动（用户删的是记录，不是原片）
                                // ——但这句话得让用户先看到，而不是事后自己推断
                                onClick = { pendingConfirm = PendingConfirm.ClearHistory }
                            )
                        }
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.L)
        ) {
            // 进行中 / 记录：设计 §4.5 指定用 AppChipRow。
            // 原来是裸 M3 分段按钮——它不在 uiSpecCheck 的禁用名单里，所以规范检查一路放行，
            // 但观感上与全站其它互斥选项（主题档位、类型筛选）不是同一套东西
            val tabLabels = listOf(
                stringResource(R.string.download_queue_tab),
                stringResource(R.string.download_history_tab)
            )
            AppChipRow(
                items = listOf(0, 1),
                selected = tab,
                label = { tabLabels[it] },
                onSelect = { tab = it }
            )
            // 提示跟着「哪一面都能看见」走，所以放在滚动区之外：
            // 一个只出现在列表顶部的错误，用户往下滚一眼就看不到了
            notice?.let { msg ->
                Spacer(Modifier.height(Spacing.M))
                StatusBanner(
                    message = msg.message,
                    actionLabel = msg.shareUri?.let { stringResource(R.string.share_action) },
                    onAction = msg.shareUri?.let { uri ->
                        {
                            shareViewModel.prepare(listOf(Uri.parse(uri)))
                            showShareSheet = true
                            viewModel.dismissNotice()
                        }
                    },
                    isError = true
                )
            }
            fallback?.let { fb ->
                Spacer(Modifier.height(Spacing.M))
                OpenFailedBanner(
                    fallback = fb,
                    onShare = {
                        shareViewModel.prepare(listOf(Uri.parse(it)))
                        showShareSheet = true
                        fallback = null
                    },
                    onDismiss = { fallback = null }
                )
            }
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                TransferQueueList(
                    tasks = tasks,
                    summary = summary,
                    policy = policy,
                    onGoAlbum = onGoAlbum,
                    onCancel = { viewModel.cancel(it) },
                    onRetry = { viewModel.retryTask(it) },
                    onRetryUnfinished = { viewModel.retryUnfinished() },
                    onView = { task ->
                        val uri = task.savedUri?.toString()
                        val outcome = viewModel.openSaved(uri, task.filename)
                        fallback = if (outcome == OpenOutcome.Opened) null else OpenFallback(
                            filename = task.filename,
                            location = viewModel.readableLocation(uri),
                            noApp = outcome == OpenOutcome.NoApp,
                            shareUri = uri
                        )
                    },
                    onShare = { target ->
                        target.savedUri?.let { uri ->
                            shareViewModel.prepare(listOf(uri))
                            showShareSheet = true
                        }
                    },
                    onEdit = { target -> target.savedUri?.let { editTarget = it } }
                )
            } else {
                TransferHistoryList(
                    history = history,
                    // 已经重新排队的记录不再摆「重试」：同一个对象此刻正在队列里传，
                    // 再给一个按下去不会多排一次队的按钮只会让人以为它没生效
                    activeKeys = tasks.filter { it.state.isActive }.mapTo(mutableSetOf()) { it.id },
                    onOpen = { detail = it },
                    onView = { record ->
                        val outcome = viewModel.openSaved(record.savedUri, record.filename)
                        fallback = if (outcome == OpenOutcome.Opened) null else OpenFallback(
                            filename = record.filename,
                            // 记录行本来就存了给人看的路径，优先用它
                            location = record.savedPath.ifBlank {
                                viewModel.readableLocation(record.savedUri)
                            },
                            noApp = outcome == OpenOutcome.NoApp,
                            shareUri = record.savedUri
                        )
                    },
                    onRetry = { viewModel.retryRecord(it) }
                )
            }
        }
    }

    // 点击/长按传输记录条目 → 详情弹窗
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

    // 编辑：编辑调节（裁剪/旋转/调色/滤镜，直接以已下载的照片为源；返回即回到传输页）
    editTarget?.let { uri ->
        PhotoEditScreen(
            initialUri = uri,
            onBack = { editTarget = null }
        )
    }

    pendingConfirm?.let { which ->
        ConfirmDialog(
            title = stringResource(which.titleRes),
            body = stringResource(which.bodyRes),
            confirmLabel = stringResource(R.string.transfer_confirm_go),
            onDismiss = { pendingConfirm = null },
            onConfirm = {
                pendingConfirm = null
                when (which) {
                    PendingConfirm.CancelAll -> viewModel.cancelAllActive()
                    PendingConfirm.ClearFinished -> viewModel.clearFinished()
                    PendingConfirm.ClearHistory -> viewModel.clearHistory()
                }
            }
        )
    }
}

/** 传输页上三个「按下就会少掉一些东西」的动作 */
private enum class PendingConfirm(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int
) {
    CancelAll(
        R.string.transfer_confirm_cancel_all_title,
        R.string.transfer_confirm_cancel_all_body
    ),
    ClearFinished(
        R.string.transfer_confirm_clear_title,
        R.string.transfer_confirm_clear_body
    ),
    ClearHistory(
        R.string.transfer_confirm_history_title,
        R.string.transfer_confirm_history_body
    )
}

/**
 * 「进行中」这一面：批次摘要 → 传输策略 → 任务行。
 *
 * 整面是一个 LazyColumn，摘要作为第一个 item 跟着一起滚：
 * 200% 字体下「摘要 + 策略」两段能吃到半屏，把它们钉死在顶部，
 * 列表就再也塞不下第二行，最后一行永远滑不进可视区（批次 B/C 连踩两次的那类错）。
 */
@Composable
private fun TransferQueueList(
    tasks: List<DownloadTask>,
    summary: TransferBatchSummary?,
    policy: TransferPolicy,
    onGoAlbum: () -> Unit,
    onCancel: (DownloadTask) -> Unit,
    onRetry: (DownloadTask) -> Unit,
    onRetryUnfinished: () -> Unit,
    onView: (DownloadTask) -> Unit,
    onShare: (DownloadTask) -> Unit,
    onEdit: (DownloadTask) -> Unit
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.download_empty),
            icon = Lucide.Info,
            desc = stringResource(R.string.download_empty_desc),
            actionLabel = stringResource(R.string.download_empty_action),
            onAction = onGoAlbum,
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
        contentPadding = PaddingValues(bottom = Spacing.XL)
    ) {
        summary?.let {
            item(key = "batch-summary") {
                BatchSummaryCard(
                    summary = it,
                    onRetryUnfinished = onRetryUnfinished,
                    onKeepChoosing = onGoAlbum
                )
            }
        }
        item(key = "policy") {
            TransferPolicySummary(policy)
        }
        items(tasks, key = { it.id }) { task ->
            DownloadTaskRow(
                task = task,
                onCancel = onCancel,
                onRetry = onRetry,
                onView = onView,
                onShare = onShare,
                onEdit = onEdit
            )
        }
    }
}

@Composable
private fun TransferHistoryList(
    history: List<DownloadHistoryEntity>,
    activeKeys: Set<String>,
    onOpen: (DownloadHistoryEntity) -> Unit,
    onView: (DownloadHistoryEntity) -> Unit,
    onRetry: (DownloadHistoryEntity) -> Unit
) {
    if (history.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.download_history_empty),
            icon = Lucide.Info,
            desc = stringResource(R.string.download_history_empty_desc),
            modifier = Modifier.fillMaxSize()
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
        contentPadding = PaddingValues(bottom = Spacing.XL)
    ) {
        items(history, key = { it.id }) { record ->
            HistoryRow(
                record = record,
                alreadyQueued = record.thumbKey != null && record.thumbKey in activeKeys,
                onOpen = { onOpen(record) },
                onView = { onView(record) },
                onRetry = { onRetry(record) }
            )
        }
    }
}

/**
 * 批次摘要（设计 §4.5）：这批传完了多少、还剩多少，以及一键重试没传完的那几项。
 *
 * 用普通表面而不是玻璃卡：这一屏的玻璃预算已经给了队列里正在传的那张卡，
 * 摘要只是文字事实，抢注意力没有意义（UI 规范 §3.2）。
 */
@Composable
private fun BatchSummaryCard(
    summary: TransferBatchSummary,
    onRetryUnfinished: () -> Unit,
    onKeepChoosing: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        GroupTitle(text = stringResource(R.string.transfer_batch_title))
        Text(
            text = stringResource(
                R.string.transfer_batch_summary,
                summary.savedCount,
                summary.unfinishedCount
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        if (summary.retryable.isNotEmpty()) {
            AppLink(
                text = pluralStringResource(
                    R.plurals.transfer_batch_retry, summary.retryable.size, summary.retryable.size
                ),
                onClick = onRetryUnfinished
            )
        }
        // 这批传完了也要有一条往前走的路（新手手册 §3：结果页要说下一步）：
        // 回到照片页继续选，而不是让用户自己想去哪个 Tab
        AppLink(
            text = stringResource(R.string.transfer_keep_choosing),
            onClick = onKeepChoosing
        )
    }
}

/**
 * 单个下载任务行
 *
 * 动作单独占一行，不跟状态标签挤同一横排：查看 / 编辑 / 分享 三个文字动作
 * 压在 200% 字体的行尾会被裁成半截，而「看不到有哪个动作」等于没有动作。
 *
 * @param onCancel 取消该任务（仅进行中的任务会显示取消按钮）
 * @param onRetry 重试该任务（仅失败任务）
 * @param onView 打开已存到相册的文件（仅完成且拿到真 Uri 的任务）
 */
@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onCancel: (DownloadTask) -> Unit = {},
    onRetry: (DownloadTask) -> Unit = {},
    onView: (DownloadTask) -> Unit = {},
    onShare: (DownloadTask) -> Unit = {},
    onEdit: (DownloadTask) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Container))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    .padding(horizontal = Spacing.L)
            ) {
                Text(
                    text = task.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeUnknown = task.sizeBytes <= 0
                Text(
                    // 大小未知要说出「未知」：留白会被读成「没有内容」，
                    // 而下面那条永远 0% 的进度条更糟——它看起来像卡死
                    text = if (sizeUnknown) stringResource(R.string.transfer_size_unknown)
                    else formatSize(task.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 失败原因跟着这一条任务说：设计 §7 要求失败给「原因 + 下一步」，
                // 只写「失败」两个字是把排查工作退回给用户
                task.errorMessage?.takeIf { task.state == DownloadState.FAILED }?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (task.state == DownloadState.DOWNLOADING) {
                    if (sizeUnknown) {
                        // 未知总长 → 不定长进度条。百分比在这种任务上根本算不出来
                        // （DownloadManager 只在 total>0 时算 progress），显示数字就是撒谎
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.S)
                                .height(4.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.S)
                                .height(4.dp)
                        )
                    }
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
                AppIconButton(
                    icon = Lucide.X,
                    contentDescription = stringResource(R.string.download_cancel),
                    onClick = { onCancel(task) },
                    iconSize = 16.dp
                )
            }
        }

        // 主动作一排：完成 →「查看」（设计 §4.5 的主动作），失败 →「重试」
        val actionable = task.state == DownloadState.DONE || task.state == DownloadState.FAILED
        if (actionable) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (task.state == DownloadState.DONE) {
                    // 「查看」只在**真拿到 Uri** 时提供。以前这一行是 编辑 / 分享，
                    // 用户想「看一眼刚传的照片」反倒要绕到相册页
                    if (task.savedUri != null) {
                        AppLink(
                            text = stringResource(R.string.transfer_view),
                            onClick = { onView(task) }
                        )
                        AppLink(
                            text = stringResource(R.string.edit_action),
                            onClick = { onEdit(task) }
                        )
                        AppLink(
                            text = stringResource(R.string.share_action),
                            onClick = { onShare(task) }
                        )
                    }
                }
                if (task.state == DownloadState.FAILED) {
                    AppLink(
                        text = stringResource(R.string.album_retry),
                        onClick = { onRetry(task) }
                    )
                }
            }
        }
    }
}

/**
 * 单条传输记录行。
 *
 * 整行点击进详情（设计 §4.5「历史详情用点击整行进入」），
 * 行尾「查看 / 重试」是这条记录自己的两个动作，按各自的来路判断能不能给：
 * - 查看：需要 [DownloadHistoryEntity.savedUri]，升级前的老记录没记过 → 不摆按钮；
 * - 重试：需要能重建对象身份，同理。
 * 宁可不摆，也不摆一个点了只会报错的按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    record: DownloadHistoryEntity,
    alreadyQueued: Boolean,
    onOpen: () -> Unit,
    onView: () -> Unit,
    onRetry: () -> Unit
) {
    val canView = !record.savedUri.isNullOrBlank()
    val canRetry = !alreadyQueued && record.toMediaItemOrNull() != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Container))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = onOpen)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标：成功/失败用图标 + 文字双保险，不只靠颜色
            LucideIcon(
                if (record.success) Lucide.CircleCheck else Lucide.CircleX,
                contentDescription = null,
                size = 24.dp,
                tint = if (record.success) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(Spacing.M))
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
            Text(
                text = stringResource(
                    if (record.success) R.string.transfer_record_ok else R.string.transfer_record_failed
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (record.success) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }
        record.errorMessage?.takeIf { !record.success }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (canView || canRetry) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canView) {
                    AppLink(text = stringResource(R.string.transfer_view), onClick = onView)
                }
                if (canRetry && !record.success) {
                    AppLink(text = stringResource(R.string.album_retry), onClick = onRetry)
                }
            }
        }
    }
}

/**
 * 「查看」走不通时的兜底：原因 + 文件位置 + 还能做什么。
 *
 * 「没有能打开这种格式的应用」才提供分享——读不到文件时分享出去同样是空的，
 * 给一个注定失败的按钮比不给更糟（UI 规范 §7：失败要带**可执行**的下一步）。
 */
@Composable
private fun OpenFailedBanner(
    fallback: OpenFallback,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val reason = stringResource(
        if (fallback.noApp) R.string.transfer_open_no_app else R.string.transfer_open_not_readable
    )
    val location = fallback.location.ifBlank { stringResource(R.string.download_history_unknown) }
    val canShare = fallback.noApp && fallback.shareUri != null
    StatusBanner(
        message = stringResource(R.string.transfer_open_failed, fallback.filename, reason) +
                "\n" + stringResource(R.string.transfer_open_location, location),
        actionLabel = stringResource(if (canShare) R.string.share_action else R.string.transfer_got_it),
        onAction = { if (canShare) fallback.shareUri?.let(onShare) else onDismiss() },
        isError = true
    )
}

/** 传输记录详情弹窗：路径 / 起止时间 / 相机型号 / 失败原因 */
@Composable
private fun HistoryDetailDialog(record: DownloadHistoryEntity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 玻璃弹窗：容器透明 + 玻璃底层（引用页面背景层，无递归风险）
        modifier = Modifier.glassDialog(),
        containerColor = glassDialogContainerColor(),
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
                record.errorMessage?.let { reason ->
                    DetailLine(
                        label = stringResource(R.string.download_history_reason),
                        value = reason
                    )
                }
            }
        },
        confirmButton = {
            AppLink(
                text = stringResource(R.string.download_history_close),
                onClick = onDismiss
            )
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
@Composable
private fun stateLabel(task: DownloadTask): String = when (task.state) {
    DownloadState.QUEUED -> stringResource(R.string.download_state_queued)
    DownloadState.DOWNLOADING -> if (task.sizeBytes > 0) {
        stringResource(R.string.download_state_downloading, task.progress)
    } else {
        // 与上方进度条同一口径：算不出来的数字就不写
        stringResource(R.string.download_state_downloading_unknown)
    }
    DownloadState.DONE -> stringResource(R.string.download_state_done)
    DownloadState.FAILED -> stringResource(R.string.download_state_failed)
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
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else if (mb >= 1) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
}

/**
 * 传输策略摘要（T3）。
 *
 * 这里只做一件事：把「这批文件按什么规则传」摆到界面上。三条限制各自跟着自己的选项写出来，
 * 尤其是**缩图不省流量**和**分块续传未实测**——这两条最容易被用户按直觉理解反。
 */
@Composable
private fun TransferPolicySummary(policy: TransferPolicy) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Text(
            text = stringResource(R.string.transfer_policy_title),
            style = MaterialTheme.typography.titleSmall
        )
        PolicyLine(
            label = stringResource(R.string.transfer_size_label),
            value = stringResource(
                if (policy.sizeMode == TransferSizeMode.LOCAL_THUMBNAIL) {
                    R.string.transfer_size_local_thumbnail
                } else {
                    R.string.transfer_size_original
                }
            )
        )
        PolicyLine(
            label = stringResource(R.string.transfer_resume_label),
            value = stringResource(
                if (policy.resumeMode == ResumeMode.PARTIAL_OBJECT) {
                    R.string.transfer_resume_partial
                } else {
                    R.string.transfer_resume_whole_object
                }
            )
        )
        Text(
            text = stringResource(R.string.transfer_size_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 只在真的启用了分块续传时才提示它的风险；当前默认关闭，这条说明就跟着选项走
        if (policy.resumeMode == ResumeMode.PARTIAL_OBJECT && !policy.resumeMode.verifiedAvailable) {
            Text(
                text = stringResource(R.string.transfer_resume_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PolicyLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
