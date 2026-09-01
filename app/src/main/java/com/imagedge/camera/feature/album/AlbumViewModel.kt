package com.imagedge.camera.feature.album

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.model.MediaSessionCache
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.transfer.DownloadManager
import com.imagedge.camera.ui.feedback.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** 相册浏览模式：选片集（PTP 内容集） / 整卡（UPnP 整张 SD 卡） */
enum class BrowseMode { SELECTION, FULL_CARD }

/**
 * 网格缩略图缓存上限（条目数）——**已上移到 MediaSessionCache 统一管理**。
 *
 * 索尼 PTP 缩略图常见 320×240，ARGB_8888 解码后约 300KB/张 → 150 张约 45MB。
 * 原先是无界 Map 且**从不清理**（切浏览模式、刷新列表都不清），整卡模式下
 * 上千张缩略图会持续占用内存直至 OOM。
 *
 * P1-11：本 ViewModel 不再自建缓存，改为直接引用 [MediaSessionCache.gridThumbnails]
 * 的快照，避免出现「两份各 150 张、内存翻倍」的情况。上限常量见
 * `MediaSessionCache.MAX_SESSION_THUMBNAILS`。
 */

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相册 ViewModel——浏览、缩略图缓存、多选下载
 *     version: 1.0
 * </pre>
 */

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val downloadManager: DownloadManager,
    private val sessionCache: MediaSessionCache,
    private val haptics: Haptics
) : ViewModel() {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 浏览模式（选片集 / 整卡）。默认选片集（与 PTP 默认 functionMode=0 一致） */
    private val _browseMode = MutableStateFlow(BrowseMode.SELECTION)
    val browseMode: StateFlow<BrowseMode> = _browseMode.asStateFlow()

    /** 是否有活跃下载（下载未完成前禁止切换浏览通道） */
    val hasActiveDownload: StateFlow<Boolean> = downloadManager.hasActiveDownload

    /**
     * 校正相机功能模式，使其与当前浏览模式一致（选片集=0 / 整卡=1）。
     *
     * **必须是 suspend 且由调用方在同一协程内等待完成。**
     * 原实现在这里 `viewModelScope.launch { switchFunctionMode() }` 后不等待，
     * `loadMedia()` 紧接着又 launch 一个协程去 `listMedia()`——两个协程之间没有任何
     * 顺序保证，扫描完全可能在模式切换完成前执行，读到旧通道的快照
     * （真机表现为「进整卡页却显示选片集内容，下拉刷新才正常」）。
     *
     * @return 模式是否就绪（本就一致或切换成功 = true）
     */
    private suspend fun syncFunctionMode(): Boolean {
        val target = if (_browseMode.value == BrowseMode.FULL_CARD) 1 else 0
        if (repository.currentFunctionMode == target) return true
        return runCatching { repository.switchFunctionMode(target) }.getOrDefault(false)
    }

    /**
     * 进入浏览页（拆开的固定模式入口：选片集 / 整卡）。
     * 切换相机功能模式（选片集=RemoteControl / 整卡=ContentsTransfer）并加载；
     * 下载未完成前禁止切换通道，给出文字说明；失败则提示。
     */
    fun enter(mode: BrowseMode) {
        if (_browseMode.value == mode && _items.value.isNotEmpty()) return
        _browseMode.value = mode
        // 重新进入（或切换通道）时重置整卡补全校验计数，让本次会话重新获得补全机会
        fullCardVerifyCount = 0
        _thumbnails.value = emptyMap()
        // 跨页面共享的缩略图缓存一并清空：否则整卡↔选片集来回切换时，
        // 两个模式的缩略图会一直堆积在单例缓存里（这正是 OOM 的来源）
        sessionCache.clearThumbnails()
        _selected.value = emptySet()
        _error.value = null
        viewModelScope.launch {
            val targetMode = if (mode == BrowseMode.FULL_CARD) 1 else 0
            // 下载未完成前不切换通道（选片集↔整卡），避免中断正在进行的传输
            if (repository.currentFunctionMode != targetMode && hasActiveDownload.value) {
                _error.value = "照片/视频传输中，暂不能切换浏览通道，请等待下载完成"
                return@launch
            }
            loadMedia()
        }
    }

    /** 缩略图内存缓存（key = thumbKey，有界，见 [MAX_GRID_THUMBNAILS]） */
    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    /**
     * 缩略图缓存代数（每次缓存清空递增，如内存 trim / 切浏览模式）。
     * 网格格子把它并入加载触发键：否则 trim 后 [loadThumbnail] 永不重跑，格子永久灰块。
     */
    val thumbnailGeneration: StateFlow<Int> = sessionCache.generation

    /**
     * 单个缩略图的可观察流。
     *
     * 每个网格格子独立订阅自己那一项，配合 [distinctUntilChanged] 后**只有**该格子的
     * 位图引用变化时才重组。
     * 若让每个格子直接 `collectAsStateWithLifecycle(thumbnails)`，任意一张缩略图加载完成
     * 都会让全部可见格子重组——数百项时滚动必然掉帧（真机已复现）。
     */
    fun thumbnailFlow(key: String): Flow<Bitmap?> =
        _thumbnails.map { it[key] }.distinctUntilChanged()

    /** 同步读取当前已缓存的缩略图（用于 collect 前的首帧，避免闪一下占位块） */
    fun cachedThumbnail(key: String): Bitmap? = _thumbnails.value[key]

    /** 正在拉取中的缩略图 key（同一项被多个格子并发触发时去重，避免重复 PTP 请求） */
    private val inflightThumbnails =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 多选集合（key = channelKey） */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /**
     * 扫描互斥锁（P2-8）。
     *
     * [loadMedia]（进页/下拉刷新）与 [silentRefresh]（4s 轮询 + 事件驱动）都会触发
     * `repository.listMedia()`。原先两者无互斥，轮询可能在手动加载中途并发发起
     * 第二次扫描——两次扫描在 PTP 通道上互相争锁，放大通道压力，极端情况下
     * 与保活三方挤在一起触发超时自愈。统一用一把 suspend 锁串行化。
     */
    private val scanMutex = kotlinx.coroutines.sync.Mutex()

    /** 加载相机媒体列表（PTP，按当前功能模式：选片集 / 整卡） */
    fun loadMedia() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                scanMutex.withLock {
                    // 先校正相机功能模式并**等待切换完成**再扫描（处理上一次离开的延迟退出
                    // 与实际模式错位）。此前 syncFunctionMode 内部异步 launch 不等待，
                    // 与下面的 listMedia 形成竞态，会读到旧通道的快照。
                    if (!syncFunctionMode()) {
                        _error.value = if (_browseMode.value == BrowseMode.FULL_CARD) {
                            "切换到整卡失败，请重试"
                        } else {
                            "切换到选片集失败，请重试"
                        }
                        return@withLock
                    }
                    _items.value = repository.listMedia().also { sessionCache.update(it) }
                }
            } catch (e: Exception) {
                AppLog.e("album", "加载媒体失败：${e::class.simpleName}: ${e.message}")
                _error.value = e.message ?: "加载媒体失败"
            } finally {
                _loading.value = false
            }
        }
    }

    /** 手动重连进行中（防止重复点击） */
    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    @Volatile
    private var silentRefreshing = false

    /** 连接状态（断开后 UI 显示提示横幅） */
    val connectionState = repository.connectionState

    init {
        // 事件驱动即时刷新：相机端选片推送（ObjectAdded/StoreAdded/RequestObjectTransfer）
        // 到达后立即扫描，不再等 4s 轮询节拍
        viewModelScope.launch {
            repository.contentEvents.collect {
                if (!silentRefreshing && repository.isConnected) silentRefresh()
            }
        }
        // 缓存被清空（内存 trim / 切浏览模式）时立即同步 _thumbnails：
        // 若继续持旧快照，下一张缩略图加载完成会用清空后的缓存重建快照整体替换，
        // 其余格子的位图从状态中消失、变灰且不再触发加载
        viewModelScope.launch {
            sessionCache.generation.collect { _thumbnails.value = sessionCache.gridThumbnails }
        }
    }

    /**
     * 整卡模式下已执行的「补全校验」次数。
     *
     * 整卡（ContentsTransfer）首次枚举时相机 SD 卡索引往往尚未就绪，GetObjectHandles
     * 会先返回一小部分（甚至为空），要过一段时间才逐步给全。原先整卡是「一次性快照、
     * 不轮询」，拿到残缺列表后永远不自愈，用户只能反复手动点重试（真机反馈）。
     * 改为进入整卡后做有限次静默补全校验，次数用尽即恢复快照语义，
     * 避免持续全量扫描拖垮 PTP 通道。
     */
    private var fullCardVerifyCount = 0

    /** 整卡补全校验次数上限与间隔（间隔更长：整卡枚举代价远高于选片集） */
    private val FULL_CARD_MAX_VERIFY = 4
    private val FULL_CARD_VERIFY_INTERVAL_MS = 8_000L

    /**
     * 相册页轮询（相机端选片驱动：用户在相机上选择照片后自动显示，与 Creators' App 交互一致）。
     * 静默刷新：不触发 loading 状态，内容无变化时不更新列表（避免网格闪烁）。
     * 由 UI 层 repeatOnLifecycle(STARTED) 驱动：退后台自动取消，回前台自动恢复
     * （金标功耗标准 4.2：后台禁止非必要的持续网络活动）。
     *
     * 整卡模式节奏不同：间隔更长，且只做有限次补全校验（见 [fullCardVerifyCount]）。
     */
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val fullCard = _browseMode.value == BrowseMode.FULL_CARD
            // 整卡：校验次数用尽即停止轮询（回到一次性快照语义）
            if (fullCard && fullCardVerifyCount >= FULL_CARD_MAX_VERIFY) return
            delay(if (fullCard) FULL_CARD_VERIFY_INTERVAL_MS else 4_000L)
            if (silentRefreshing) continue
            silentRefresh()
        }
    }

    /** 单次静默刷新（轮询节拍与内容事件共用；异常静默，不打断用户） */
    private suspend fun silentRefresh() {
        val fullCard = _browseMode.value == BrowseMode.FULL_CARD
        if (fullCard) {
            // 整卡：下载进行中不校验（全量枚举与下载争抢 PTP 通道，两者都变慢）
            if (hasActiveDownload.value) return
            if (fullCardVerifyCount >= FULL_CARD_MAX_VERIFY) return
            fullCardVerifyCount++
        }
        silentRefreshing = true
        try {
            if (repository.isConnected) {
                // P2-8：与 loadMedia 共用一把扫描锁，避免并发扫描互相争抢 PTP 通道
                scanMutex.withLock {
                    val fresh = repository.listMedia()
                    val oldSignature = _items.value.map { it.channelKey to it.sizeBytes }
                    val newSignature = fresh.map { it.channelKey to it.sizeBytes }
                    if (oldSignature != newSignature) {
                        AppLog.i("album", "检测到相机端内容变化：${oldSignature.size} → ${newSignature.size}")
                        _items.value = fresh
                        sessionCache.update(fresh)
                    }
                }
            }
        } catch (e: Exception) {
            // 相机断开/暂不可用：静默，不打断用户
            AppLog.d("album", "轮询刷新暂不可用：${e.message}")
        } finally {
            silentRefreshing = false
        }
    }

    /** 用户手动重连（断线横幅按钮）：沿用仓库连接流程（网关发现 + PTP 优先） */
    fun reconnect() {
        if (_reconnecting.value) return
        viewModelScope.launch {
            _reconnecting.value = true
            try {
                repository.connect()
                loadMedia()
            } catch (e: Exception) {
                AppLog.w("album", "手动重连失败：${e.message}")
                _error.value = "重连失败：${e.message}"
            } finally {
                _reconnecting.value = false
            }
        }
    }

    /**
     * 加载缩略图（缓存去重 + 在途去重 + 有界缓存）。
     *
     * 三点修正：
     * 1. **解码切到 [Dispatchers.Default]** —— 原先在 `viewModelScope` 默认的主线程上
     *    同步 `BitmapFactory.decodeByteArray`，滚动时逐格解码直接造成掉帧。
     * 2. **在途去重** —— 同一个 key 被多个格子并发触发时会重复发起 PTP 请求。
     * 3. **有界缓存** —— 超过 [MAX_GRID_THUMBNAILS] 按插入顺序淘汰，避免整卡模式 OOM。
     *
     * key 含 sizeBytes/filename：相机会复用 handle，内容变化需刷新。
     */
    fun loadThumbnail(item: MediaItem) {
        val key = item.thumbKey
        if (sessionCache.gridThumbnails.containsKey(key)) return
        if (!inflightThumbnails.add(key)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val bytes = repository.getThumbnail(item) ?: return@launch
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
                // 单一数据源（P1-11）：只写 sessionCache，再把它的快照引用赋给 _thumbnails。
                // 原先这里 `_thumbnails` 与 `sessionCache.gridThumbnails` 各存一份同样的
                // Bitmap —— 300 张 × 300KB ≈ 90MB，内存直接翻倍；且每加载一张都要
                // 复制整个 150 项 Map（N 张 = N² 次插入），滚动时持续抖动。
                sessionCache.updateThumbnail(key, bitmap)
                _thumbnails.value = sessionCache.gridThumbnails
            } finally {
                inflightThumbnails.remove(key)
            }
        }
    }

    /** 切换选中 */
    fun toggleSelect(item: MediaItem) {
        _selected.update { current ->
            if (item.channelKey in current) current - item.channelKey else current + item.channelKey
        }
        haptics.tick()
    }

    /** 筛选切换反馈（筛选为 UI 局部状态，由 UI 调用） */
    fun onFilterChanged() = haptics.tick()

    /** 清空选中 */
    fun clearSelection() {
        _selected.value = emptySet()
    }

    /** 下载选中项（交给全局下载队列串行处理） */
    fun downloadSelected() {
        val toDownload = _items.value.filter { it.channelKey in _selected.value }
        downloadManager.enqueueAll(toDownload)
        clearSelection()
    }

    /**
     * 退出整卡页：延迟 5 秒切回选片集通道，给相机留出完成当前操作的时间。
     * 由页面 onDispose（可靠）与 ViewModel onCleared（兜底）双重触发，幂等。
     */
    fun exitFullCard() {
        if (_browseMode.value == BrowseMode.FULL_CARD) {
            repository.exitFullCardDelayed(5_000)
        }
    }

    /**
     * 页面销毁（返回上一级）：整卡模式下延迟 5 秒退出整卡读取，
     * 给相机留出完成当前操作的时间；若 5 秒内重新进入相册，
     * loadMedia→syncFunctionMode 会取消延迟并按 UI 模式同步。
     */
    override fun onCleared() {
        exitFullCard()
    }
}
