package com.imagedge.camera.feature.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.remote.ChannelConnectionState
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.ui.components.AlbumGridSkeleton
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.PageHeader
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相册（时间轴网格 + 缩略图 + 多选下载，交互规格 4.2）
 *     version: 1.0
 * </pre>
 */

/**
 * 相册屏幕（固定浏览模式：选片集 / 整卡，二者已拆为相册 TAB 上的两个独立入口）
 * @param browseMode 本次进入的浏览模式
 * @param onOpenDownloads 打开下载队列页的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    browseMode: BrowseMode,
    onOpenViewer: (Int) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 类型筛选（全部 / 照片 / 视频 / RAW）
    var filter by remember { mutableStateOf(MediaFilter.ALL) }

    // 进入页面：按本次固定模式切换相机通道并加载（下载未完成时 enter 会拦截并给出说明）
    LaunchedEffect(browseMode) {
        viewModel.enter(browseMode)
    }
    // 离开整卡页：立即触发「延迟 5 秒切回选片集」（onDispose 比 ViewModel.onCleared 更可靠）
    DisposableEffect(browseMode) {
        onDispose {
            if (browseMode == BrowseMode.FULL_CARD) viewModel.exitFullCard()
        }
    }
    // 轮询挂靠生命周期：退后台（STOP）自动暂停、回前台（START）恢复，
    // 避免后台持续发起 PTP 扫描（金标功耗标准 4.2：后台禁非必要网络活动）
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.pollLoop()
        }
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(
                    if (browseMode == BrowseMode.FULL_CARD) R.string.album_full_card_title
                    else R.string.album_selection_title
                ),
                onBack = onBack
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.downloadSelected() },
                    content = {
                        Text(stringResource(R.string.album_btn_download, selected.size))
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // 类型筛选（全部 / 照片 / 视频 / RAW）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediaFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = {
                            Text(
                                text = stringResource(f.labelRes),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 断线提示横幅（连接状态由保活/事务自愈维护）
            if (connectionState == ChannelConnectionState.DISCONNECTED) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.album_disconnected_banner),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.reconnect() }, enabled = !reconnecting) {
                            Text(
                                if (reconnecting) stringResource(R.string.album_disconnected_reconnecting)
                                else stringResource(R.string.album_disconnected_retry)
                            )
                        }
                    }
                }
            }

            // 筛选 + 分组结果（按日期分组、组内按文件名排序）
            val filtered = remember(items, filter) { items.filter { it.matches(filter) } }
            val grouped = remember(filtered) { groupByDate(filtered) }

            when {
                // 加载：骨架屏（而非转圈），列数与真实网格一致，避免完成时跳动
                loading -> AlbumGridSkeleton()
                // 失败：说明原因 + 重试入口（规范：失败必须带「怎么办」）
                error != null -> EmptyState(
                    icon = Lucide.TriangleAlert,
                    title = stringResource(R.string.album_error_title),
                    description = error.orEmpty(),
                    actionLabel = stringResource(R.string.album_retry),
                    onAction = { viewModel.loadMedia() }
                )
                // 空态：说明 + 下一步行动（规范：空状态不能只丢一句"暂无数据"）
                items.isEmpty() -> EmptyState(
                    icon = Lucide.Info,
                    title = stringResource(R.string.album_empty_title),
                    description = stringResource(R.string.album_empty_hint),
                    actionLabel = stringResource(R.string.album_retry),
                    onAction = { viewModel.loadMedia() }
                )
                // 筛选后为空：提示切换筛选
                filtered.isEmpty() -> EmptyState(
                    icon = Lucide.Info,
                    title = stringResource(R.string.album_filter_empty_title),
                    description = stringResource(R.string.album_filter_empty_hint),
                    actionLabel = stringResource(R.string.album_filter_reset),
                    onAction = { filter = MediaFilter.ALL }
                )
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        grouped.forEach { entry ->
                            when (entry) {
                                is GridEntry.Header -> item(
                                    key = "date-${entry.label}",
                                    span = { GridItemSpan(maxLineSpan) }
                                ) {
                                    DateHeader(label = entry.label)
                                }
                                is GridEntry.Media -> item(key = entry.item.thumbKey) {
                                    MediaCell(
                                        item = entry.item,
                                        selected = entry.item.channelKey in selected,
                                        onClick = { onOpenViewer(items.indexOf(entry.item)) },
                                        onLongClick = { viewModel.toggleSelect(entry.item) },
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
}

/**
 * 单个媒体单元格
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    item: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    viewModel: AlbumViewModel
) {
    // 每个格子**只订阅自己那一项**的缩略图流。
    // 原先这里 collect 整张 Map：任意一张缩略图加载完成都会让全部可见格子重组，
    // 数百项时滚动必然掉帧。改为按 thumbKey 的独立流 + distinctUntilChanged 后，
    // 只有该格子自己的位图引用变化时才重组。
    val bitmap by remember(item.thumbKey) { viewModel.thumbnailFlow(item.thumbKey) }
        .collectAsStateWithLifecycle(initialValue = viewModel.cachedThumbnail(item.thumbKey))

    // 缓存代数并入触发键：内存 trim 清空缩略图缓存后代数递增，
    // 若只用 thumbKey 作键，格子变灰后永不重新加载（永久灰块）
    val thumbnailGeneration by viewModel.thumbnailGeneration.collectAsStateWithLifecycle()
    LaunchedEffect(item.thumbKey, thumbnailGeneration) {
        viewModel.loadThumbnail(item)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        val cellModifier = Modifier.fillMaxSize()
        // 取局部变量：`bitmap` 是 `by` 委托属性，无法智能转换为非空
        val thumb = bitmap

        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = item.filename,
                modifier = cellModifier
            )
        } else {
            Box(modifier = cellModifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }

        // 选中态边框
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            )
        }

        // 类型角标（左上角显示文件格式，如 JPG / ARW / MP4）
        item.formatBadge()?.let { badge ->
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/** 左上角格式角标：优先取文件扩展名大写，无扩展名时按类型兜底 */
private fun MediaItem.formatBadge(): String? {
    val ext = filename.substringAfterLast('.', "")
        .uppercase(Locale.US)
        .takeIf { it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    if (ext != null) return ext
    return when (photoType) {
        PhotoType.RAW -> "RAW"
        PhotoType.VIDEO -> "VIDEO"
        PhotoType.JPEG -> "JPG"
        else -> null
    }
}

/** 类型筛选 */
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

/** 日期显示格式（按拍摄日分组） */
private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

private fun dateKey(date: Date?): String {
    if (date == null) return "未知日期"
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter)
}

/** 按日期分组（日期降序，同日期按文件名升序） */
private fun groupByDate(items: List<MediaItem>): List<GridEntry> {
    val sorted = items.sortedWith(
        compareByDescending<MediaItem> { it.captureDate?.time ?: 0L }
            .thenBy { it.filename }
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

/** 日期分组头 */
@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
    )
}
