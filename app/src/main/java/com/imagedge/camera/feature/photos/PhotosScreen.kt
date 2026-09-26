package com.imagedge.camera.feature.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.core.permission.rememberNotificationPermissionRequester
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.remote.ChannelConnectionState
import com.imagedge.camera.data.transfer.TransferScope
import com.imagedge.camera.navigation.BottomSlotHost
import com.imagedge.camera.navigation.BottomSlotOwner
import com.imagedge.camera.domain.media.mediaId
import com.imagedge.camera.domain.media.MediaId
import com.imagedge.camera.navigation.LocalNavClearance
import com.imagedge.camera.ui.components.AlbumGridSkeleton
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.StatusBanner
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.guidance.GuideCard
import com.imagedge.camera.ui.guidance.GuideContent
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize
import com.imagedge.camera.ptp.PhotoType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 照片页（批次 C）：Tab 根，范围行 + 筛选 + 网格 + 选择态底栏
 *     version: 2.0
 * </pre>
 */

/** 等待相机端选片的引导标识（含版本号，文案改版就换） */
private const val AWAITING_GUIDE_ID = "photos-await-camera-selection-v1"

/**
 * 照片页。
 *
 * 批次 C 把原来的「相册中枢 → 选片集 / 整卡 两条子路由」压成一页：
 * 中枢那两张入口卡只是把一次范围切换伪装成两个页面，用户要先选「进哪个相册」
 * 才能看到照片，而范围本身随时可换（设计 §2）。
 *
 * 三处层级约定：
 * - **范围不是筛选芯片**：切换会让相机换 PTP 功能模式，走 [BrowseScopeSheet]；
 * - **选择态换掉底部**：[SelectionActionBar] 占住底栏位置，悬浮导航同时让位
 *   （[BottomSlotHost]），避免「导航 + 任务条 + 保存按钮」三层叠加；
 * - **有内容时出错不清空**：后台刷新失败只出横幅，列表保持可见。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosScreen(
    onOpenViewer: (MediaId) -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    onGoConnect: () -> Unit = {},
    snackbarController: SnackbarController,
    viewModel: PhotosViewModel = hiltViewModel(),
    bottomSlot: BottomSlotHost
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val scope by viewModel.browseMode.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()
    val transferActive by viewModel.hasActiveDownload.collectAsStateWithLifecycle()
    val savedKeys by viewModel.savedKeys.collectAsStateWithLifecycle()
    val cardEmptyConfirmed by viewModel.cardEmptyConfirmed.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val requestNotificationPermission =
        rememberNotificationPermissionRequester(snackbarController)

    var filter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var showScopeSheet by rememberSaveable { mutableStateOf(false) }

    // 进入本页：按当前范围校正相机通道并加载（首次进入 = 选片集）
    LaunchedEffect(Unit) { viewModel.enter(scope) }

    // 离开整卡范围：延迟 5 秒让相机退出整卡读取（onDispose 比 onCleared 可靠）
    DisposableEffect(scope) {
        onDispose { if (scope == BrowseMode.FULL_CARD) viewModel.exitFullCard() }
    }
    // 选择态占用底栏；离开页面或退出选择态都要交还，否则导航永久消失
    DisposableEffect(selectionMode) {
        if (selectionMode) bottomSlot.claim(BottomSlotOwner.SelectionBar)
        onDispose { bottomSlot.release(BottomSlotOwner.SelectionBar) }
    }
    // 轮询挂靠生命周期：退后台停，回前台续（金标功耗 4.2）
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollLoop() }
    }

    val connected = connectionState == ChannelConnectionState.CONNECTED
    // 头部与网格共用同一份筛选结果（「全选当前列表」说的就是这一份）
    val filtered = remember(items, filter) { items.filter { it.matches(filter) } }
    val scopeEnum = TransferScope.forBrowseMode(scope == BrowseMode.FULL_CARD)
    val scopeLabel = stringResource(
        if (scopeEnum == TransferScope.WHOLE_CARD) R.string.photos_scope_full_card
        else R.string.photos_scope_selection
    )

    AppScreenFrame(
        topBar = {
            AppPageHeader(
                title = if (selectionMode) {
                    pluralStringResource(R.plurals.photos_selected_count, selected.size, selected.size)
                } else {
                    stringResource(R.string.tab_photos)
                },
                large = true,
                actions = {
                    if (selectionMode) {
                        // 全选当前**筛选出来的**这一批，而不是整个相册
                        val allShownSelected = filtered.isNotEmpty() &&
                            filtered.all { it.channelKey in selected }
                        AppLink(
                            text = stringResource(
                                if (allShownSelected) R.string.photos_deselect_all
                                else R.string.photos_select_all
                            ),
                            onClick = { viewModel.setManySelected(filtered, !allShownSelected) }
                        )
                        AppLink(
                            text = stringResource(R.string.photos_cancel_selection),
                            onClick = {
                                selectionMode = false
                                viewModel.clearSelection()
                            }
                        )
                    } else {
                        // 一张都没有时不给进选择态：进去之后只能对着一句「请选择照片」发呆
                        if (items.isNotEmpty()) {
                            AppLink(
                                text = stringResource(R.string.photos_select_action),
                                onClick = { selectionMode = true }
                            )
                        }
                        AppLink(
                            text = stringResource(R.string.photos_transfer_action),
                            onClick = onOpenTransfer
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (selectionMode) {
                SelectionActionBar(
                    selectedCount = selected.size,
                    submitting = submitting,
                    saveLocation = viewModel.saveLocation,
                    onSave = {
                        requestNotificationPermission()
                        viewModel.downloadSelected()
                    },
                    onCancel = {
                        selectionMode = false
                        viewModel.clearSelection()
                    },
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 没连上又什么都没有时，「范围」和「筛选」都还是在说一个不存在的数据集：
            // 这时页面只该给一条修复路径（下面的 NeedsConnection 空态），不摆控件
            val hasSession = connected || items.isNotEmpty()

            // 范围行：这一屏列的是哪一批，点开可换
            if (hasSession) BrowseScopeRow(
                scopeLabel = scopeLabel,
                note = stringResource(
                    if (scopeEnum.decidedByCamera) R.string.transfer_scope_by_camera
                    else R.string.photos_scope_note_card
                ),
                // 传输中就地把原因写在行下（设计 §2）。真点下去时 ViewModel 还会按
                // 「相机当前功能模式」再判一次，所以这里最坏是多说一句，不会拦掉本该允许的切换
                blockedReason = if (transferActive)
                    stringResource(R.string.photos_scope_blocked) else null,
                onClick = { showScopeSheet = true },
                modifier = Modifier.padding(horizontal = Spacing.L)
            )

            val filterLabels = MediaFilter.entries.associateWith { stringResource(it.labelRes) }
            if (hasSession) AppChipRow(
                items = MediaFilter.entries.toList(),
                selected = filter,
                label = { filterLabels.getValue(it) },
                onSelect = {
                    filter = it
                    viewModel.onFilterChanged()
                },
                modifier = Modifier.padding(horizontal = Spacing.L)
            )

            // 断线横幅只负责「已经有内容，但连接掉了」：内容不清空，改一条横幅说明。
            // 首屏还没连上时不画它——那时该出现的是 NeedsConnection 空态，
            // 两块一起出现就是同一件事说两遍，还平白给一块失败红
            if (!connected && items.isNotEmpty()) {
                StatusBanner(
                    message = stringResource(R.string.album_disconnected_banner),
                    actionLabel = stringResource(
                        if (reconnecting) R.string.album_disconnected_reconnecting
                        else R.string.album_disconnected_retry
                    ),
                    onAction = { if (!reconnecting) viewModel.reconnect() },
                    modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.XS)
                )
            }

            // 一次说明：文字、语气和**能做的补救**都按类型给（设计 §8.5 状态到界面的显式映射）。
            // 一律给一个「重试→重新加载」会把「去连接」「再存一次」这两件不同的事抹平成第三件
            val noticeUi = notice?.let { noticeView(it, scopeLabel, viewModel, onGoConnect) }

            // 后台刷新失败但已有内容：横幅说明，列表照旧（设计 §4.3 末段）
            if (noticeUi != null && items.isNotEmpty()) {
                StatusBanner(
                    message = noticeUi.text,
                    actionLabel = noticeUi.actionLabel,
                    onAction = noticeUi.onAction,
                    isError = noticeUi.isError,
                    modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.XS)
                )
            }

            val grouped = remember(filtered) { groupByDate(filtered) }

            when {
                // 没连上又什么都没有：给一条去连接的路，而不是「暂无数据」
                !connected && items.isEmpty() -> EmptyState(
                    title = stringResource(R.string.photos_need_connect_title),
                    desc = stringResource(R.string.photos_need_connect_desc),
                    icon = Lucide.Camera,
                    actionLabel = stringResource(R.string.photos_need_connect_action),
                    onAction = onGoConnect,
                    modifier = Modifier.fillMaxSize()
                )
                loading && items.isEmpty() -> AlbumGridSkeleton()
                noticeUi != null && items.isEmpty() -> EmptyState(
                    title = stringResource(R.string.album_error_title),
                    desc = noticeUi.text,
                    actionLabel = noticeUi.actionLabel ?: stringResource(R.string.album_retry),
                    onAction = noticeUi.onAction ?: { viewModel.loadMedia() },
                    modifier = Modifier.fillMaxSize()
                )
                // 选片集且相机还没推任何东西：这是**正常等待**，不是空目录
                connected && items.isEmpty() && scopeEnum.decidedByCamera -> GuideCard(
                    guide = GuideContent(
                        id = AWAITING_GUIDE_ID,
                        locationLabel = stringResource(R.string.home_guide_on_camera),
                        title = stringResource(R.string.photos_awaiting_title),
                        body = stringResource(R.string.photos_awaiting_body),
                        actionLabel = stringResource(R.string.photos_refresh_action)
                    ),
                    onAction = { viewModel.loadMedia() },
                    modifier = Modifier.padding(Spacing.L)
                )
                // 整卡确认过是空的：说「卡里没有照片」，不要把两种范围的解释混在一句里
                connected && items.isEmpty() && cardEmptyConfirmed -> EmptyState(
                    title = stringResource(R.string.photos_card_empty_title),
                    desc = stringResource(R.string.photos_card_empty_desc),
                    icon = Lucide.HardDrive,
                    actionLabel = stringResource(R.string.photos_refresh_action),
                    onAction = { viewModel.loadMedia() },
                    modifier = Modifier.fillMaxSize()
                )
                connected && items.isEmpty() -> EmptyState(
                    title = stringResource(R.string.album_empty_title),
                    desc = stringResource(R.string.album_empty_hint),
                    icon = Lucide.Images,
                    actionLabel = stringResource(R.string.photos_refresh_action),
                    onAction = { viewModel.loadMedia() },
                    modifier = Modifier.fillMaxSize()
                )
                filtered.isEmpty() -> EmptyState(
                    title = stringResource(R.string.album_filter_empty_title),
                    desc = stringResource(R.string.album_filter_empty_hint),
                    actionLabel = stringResource(R.string.album_filter_reset),
                    onAction = { filter = MediaFilter.ALL },
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyVerticalGrid(
                    // 自适应列：窄屏自然掉到 2 列，不写死 3
                    columns = GridCells.Adaptive(UiSize.PhotoTileMin),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
                    verticalArrangement = Arrangement.spacedBy(Spacing.XS),
                    contentPadding = PaddingValues(
                        start = Spacing.L,
                        end = Spacing.L,
                        // 最后一行要能完整停在悬浮导航上方：让位量实测下发，不写死
                        bottom = LocalNavClearance.current
                    ),
                    modifier = Modifier.fillMaxSize()
                ) {
                    grouped.forEach { entry ->
                        when (entry) {
                            is GridEntry.Header -> item(
                                key = "date-${entry.label}",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                GroupTitle(text = entry.label)
                            }
                            is GridEntry.Media -> {
                                val item = entry.item
                                item(key = item.thumbKey) {
                                    PhotoGridCell(
                                        item = item,
                                        selectionMode = selectionMode,
                                        selected = item.channelKey in selected,
                                        savedLocally = item.thumbKey in savedKeys,
                                        onClick = {
                                            if (selectionMode) viewModel.toggleSelect(item)
                                            else onOpenViewer(item.mediaId)
                                        },
                                        onLongClick = {
                                            selectionMode = true
                                            viewModel.toggleSelect(item)
                                        },
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScopeSheet) {
        BrowseScopeSheet(
            current = scope,
            switchBlocked = transferActive,
            blockedReason = stringResource(R.string.photos_scope_blocked),
            onSelect = {
                showScopeSheet = false
                viewModel.switchScope(it)
            },
            onDismiss = { showScopeSheet = false }
        )
    }
}

/** 一格照片：只订阅自己那张缩略图，避免任意一张加载完成让整屏重组 */
@Composable
private fun PhotoGridCell(
    item: MediaItem,
    selectionMode: Boolean,
    selected: Boolean,
    savedLocally: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    viewModel: PhotosViewModel
) {
    val bitmap by remember(item.thumbKey) { viewModel.thumbnailFlow(item.thumbKey) }
        .collectAsStateWithLifecycle(initialValue = viewModel.cachedThumbnail(item.thumbKey))
    val thumbnailGeneration by viewModel.thumbnailGeneration.collectAsStateWithLifecycle()
    val failures by viewModel.thumbnailFailures.collectAsStateWithLifecycle()
    LaunchedEffect(item.thumbKey, thumbnailGeneration) { viewModel.loadThumbnail(item) }

    PhotoGridTile(
        item = item,
        bitmap = bitmap,
        selectionMode = selectionMode,
        selected = selected,
        savedLocally = savedLocally,
        thumbnailFailed = item.thumbKey in failures,
        onRetryThumbnail = { viewModel.retryThumbnail(item) },
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.aspectRatio(1f)
    )
}


/** 一条说明在界面上的样子：文字、语气（错误/提醒）、以及此刻唯一说得通的补救 */
private class NoticeView(
    val text: String,
    val isError: Boolean,
    val actionLabel: String?,
    val onAction: (() -> Unit)?
)

/**
 * `PhotosNotice` → 界面。ViewModel 只说「是什么事」，措辞全在 strings.xml，
 * 补救动作也按事给：**「传输中不能切」的补救是等，不是再按一次重试**。
 */
@Composable
private fun noticeView(
    notice: PhotosNotice,
    scopeLabel: String,
    viewModel: PhotosViewModel,
    onGoConnect: () -> Unit
): NoticeView = when (notice) {
    PhotosNotice.TransferBusy -> NoticeView(
        text = stringResource(R.string.photos_scope_blocked),
        isError = false,
        actionLabel = null,
        onAction = null
    )
    PhotosNotice.NotConnected -> NoticeView(
        text = stringResource(R.string.photos_notice_not_connected),
        isError = false,
        actionLabel = stringResource(R.string.photos_need_connect_action),
        onAction = onGoConnect
    )
    PhotosNotice.QueueRejected -> NoticeView(
        // 选择还在，所以这里的重试是「再存一次」，不是「重新加载列表」
        text = stringResource(R.string.photos_notice_queue_rejected),
        isError = true,
        actionLabel = stringResource(R.string.album_retry),
        onAction = { viewModel.downloadSelected() }
    )
    PhotosNotice.RefreshFailed -> NoticeView(
        text = stringResource(R.string.photos_notice_refresh_failed),
        isError = false,
        actionLabel = stringResource(R.string.album_retry),
        onAction = { viewModel.loadMedia() }
    )
    is PhotosNotice.ModeSwitchFailed -> NoticeView(
        text = stringResource(R.string.photos_notice_mode_switch_failed, scopeLabel),
        isError = true,
        actionLabel = stringResource(R.string.album_retry),
        onAction = { viewModel.switchScope(notice.target) }
    )
    PhotosNotice.ModeSyncFailed -> NoticeView(
        text = stringResource(R.string.photos_notice_mode_sync_failed),
        isError = true,
        actionLabel = stringResource(R.string.album_retry),
        onAction = { viewModel.loadMedia() }
    )
    is PhotosNotice.LoadFailed -> NoticeView(
        text = notice.detail?.let {
            stringResource(R.string.photos_notice_with_detail, stringResource(R.string.album_error_title), it)
        } ?: stringResource(R.string.album_error_title),
        isError = true,
        actionLabel = stringResource(R.string.album_retry),
        onAction = { viewModel.loadMedia() }
    )
    is PhotosNotice.ReconnectFailed -> NoticeView(
        text = notice.detail?.let {
            stringResource(R.string.photos_notice_with_detail, stringResource(R.string.photos_notice_reconnect_failed), it)
        } ?: stringResource(R.string.photos_notice_reconnect_failed),
        isError = true,
        actionLabel = stringResource(R.string.album_retry),
        onAction = { viewModel.reconnect() }
    )
}

/** 类型筛选（全部 / 照片 / 视频 / RAW） */
enum class MediaFilter(val labelRes: Int) {
    ALL(R.string.album_filter_all),
    PHOTO(R.string.album_filter_photo),
    VIDEO(R.string.album_filter_video),
    RAW(R.string.album_filter_raw)
}

/** 筛选匹配 */
private fun MediaItem.matches(filter: MediaFilter): Boolean = when (filter) {
    MediaFilter.ALL -> true
    MediaFilter.PHOTO -> photoType == PhotoType.JPEG
    MediaFilter.VIDEO -> photoType == PhotoType.VIDEO
    MediaFilter.RAW -> photoType == PhotoType.RAW
}

/** 网格条目：日期分组头 或 媒体项 */
private sealed interface GridEntry {
    data class Header(val label: String) : GridEntry
    data class Media(val item: MediaItem) : GridEntry
}

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

private fun dateKey(date: Date?): String =
    date?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()?.format(dateFormatter) ?: "未知日期"

/** 按拍摄日分组（日期降序，同日按文件名升序）。日期标题独占一行，不显示长文件名 */
private fun groupByDate(items: List<MediaItem>): List<GridEntry> {
    val sorted = items.sortedWith(
        compareByDescending<MediaItem> { it.captureDate?.time ?: 0L }.thenBy { it.filename }
    )
    val result = mutableListOf<GridEntry>()
    var lastKey: String? = null
    for (item in sorted) {
        val key = dateKey(item.captureDate)
        if (key != lastKey) {
            result.add(GridEntry.Header(key))
            lastKey = key
        }
        result.add(GridEntry.Media(item))
    }
    return result
}
