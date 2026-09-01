package com.imagedge.camera.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.model.MediaSessionCache
import com.imagedge.camera.data.model.isActive
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.ChannelConnectionState
import com.imagedge.camera.data.remote.ChannelType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 全局下载管理器（串行队列，跨页面存活；前台服务保活 + 通知进度 + Room 持久化）
 *     version: 1.2
 * </pre>
 */

@Singleton
class DownloadManager @Inject constructor(
    private val repository: CameraRepository,
    @ApplicationContext private val context: Context,
    private val taskDao: DownloadTaskDao,
    private val historyDao: DownloadHistoryDao,
    private val sessionCache: MediaSessionCache
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 断点续传单块大小（512KB：平衡事务开销与失败重传代价） */
    private val DOWNLOAD_CHUNK_SIZE = 512 * 1024L

    /** 单块失败后的指数退避基数与上限（1s → 2s → 4s → 8s → 15s 封顶） */
    private val DOWNLOAD_BACKOFF_BASE_MS = 1_000L
    private val DOWNLOAD_BACKOFF_MAX_MS = 15_000L

    /** 单块最大连续重试次数（超过判定整体失败） */
    private val DOWNLOAD_MAX_RETRIES = 5

    /** 下载队列（串行消费） */
    private val queue = Channel<MediaItem>(Channel.UNLIMITED)

    /**
     * 用户已取消、但尚未被消费循环回收的任务 ID。
     *
     * Channel 无法移除已入队的单项，只能在消费时过滤；排队中的任务靠此集合跳过。
     * 下载中的任务也先进这里，供 [download] 的 finally 判断「是取消还是失败」。
     */
    private val cancelledIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 当前正在下载的任务协程（用户手动取消时用） */
    @Volatile
    private var currentDownloadJob: Job? = null

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 是否有活跃下载（排队中或下载中）——切换浏览通道（选片集↔整卡）前据此拦截 */
    val hasActiveDownload: StateFlow<Boolean> = _tasks
        .map { list -> list.any { it.state.isActive } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // 下载活跃状态上报：有未完成任务 → 豁免 PTP 保活的后台暂停（用户主动传输例外）；
        // 队列空闲 → 恢复暂停。用于功耗合规（后台持续网络活动豁免仅限用户主动下载）。
        scope.launch {
            tasks.collect { list ->
                repository.setDownloadActive(list.any { it.state.isActive })
            }
        }
        scope.launch {
            restorePendingTasks()
            for (item in queue) {
                // 排队期间被用户取消：跳过本次下载（Channel 内已入队的项无法移除）
                if (cancelledIds.contains(item.thumbKey)) {
                    cancelledIds.remove(item.thumbKey)
                    AppLog.i("download", "任务已取消，跳过：${item.filename}")
                    continue
                }
                // 用可取消的子协程承载单次下载，用户取消时只中断这一个任务，
                // 不影响队列消费循环（download 内部会吞掉 CancellationException）
                val job = scope.launch { download(item) }
                currentDownloadJob = job
                job.join()
                if (currentDownloadJob === job) currentDownloadJob = null
            }
        }
    }

    /** 进程重启后恢复未完成的下载任务（Room 里的排队/下载中任务） */
    private suspend fun restorePendingTasks() {
        val pending = runCatching { taskDao.getAll() }.getOrNull() ?: return
        if (pending.isEmpty()) return
        AppLog.i("download", "恢复 ${pending.size} 个待下载任务")
        for (entity in pending) {
            val item = entity.toMediaItem()
            _tasks.update { list ->
                if (list.any { it.id == item.thumbKey }) list
                else list + DownloadTask(
                    id = item.thumbKey,
                    filename = item.filename,
                    sizeBytes = item.sizeBytes,
                    state = DownloadState.QUEUED
                )
            }
            queue.trySend(item)
        }
        startDownloadService()
    }

    /**
     * 加入下载队列。
     *
     * 去重**只针对进行中的任务**（[DownloadState.isActive]）：已完成/失败的记录允许重新入队，
     * 复用同一条记录并重置为排队态。
     *
     * 背景（真机 bug）：原实现以「任务列表里存在该 ID」为去重条件，而完成/失败的任务
     * 只有用户手动 [clearFinished] 才会移除——也就是说一张照片只要下载过一次，
     * 用户即使删掉了本地文件，也再也无法重新入队，只能杀进程重开。
     *
     * id 用 thumbKey——相机会复用 handle，同一 handle 指向不同照片时须能再次下载。
     */
    fun enqueue(item: MediaItem) {
        val accepted = enqueueInternal(listOf(item))
        if (accepted.isNotEmpty()) {
            scope.launch { runCatching { taskDao.insert(DownloadTaskEntity.from(item)) } }
            startDownloadService()
        }
    }

    /**
     * 把若干项并入任务列表并入队，**只做一次** [_tasks] 更新。
     *
     * 修复（P1-12）：原先 [enqueueAll] 对每项调用一次 [enqueue]，而 enqueue 内部
     * `_tasks.update { ... }` 每次都要拷贝整个列表 —— N 项就是 N 次全量拷贝
     * （500 项 ≈ 12.5 万次对象构造），滚动/点击明显卡顿。此处一次性算差量再更新。
     *
     * @return 实际入队的项（已在进行中的重复项会被过滤掉）
     */
    private fun enqueueInternal(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) return emptyList()
        val accepted = ArrayList<MediaItem>(items.size)
        // 先按当前快照筛出真正要入队的，避免在 update 里做重复判断
        val snapshot = _tasks.value
        for (item in items) {
            val id = item.thumbKey
            if (snapshot.any { it.id == id && it.state.isActive }) continue
            accepted.add(item)
        }
        if (accepted.isEmpty()) return emptyList()

        val indexById = snapshot.associateBy { it.id }.toMutableMap()
        _tasks.update { list ->
            val next = list.toMutableList()
            for (item in accepted) {
                val id = item.thumbKey
                val previous = indexById[id]
                val reset = DownloadTask(
                    id = id,
                    filename = item.filename,
                    sizeBytes = item.sizeBytes,
                    state = DownloadState.QUEUED,
                    // 重新入队时相册缩略图缓存可能已被 LRU 淘汰，回退复用任务上已存的缩略图
                    thumbnail = sessionCache.gridThumbnails[id] ?: previous?.thumbnail
                )
                val at = next.indexOfFirst { it.id == id }
                if (at >= 0) next[at] = reset else next.add(reset)
                indexById[id] = reset
            }
            next
        }
        for (item in accepted) queue.trySend(item)
        return accepted
    }

    /** 重新下载指定任务（失败/完成后再次下载同一文件；等价于 [enqueue] 的重入队路径） */
    fun retry(task: DownloadTask, item: MediaItem) {
        if (task.state.isActive) return
        enqueue(item)
    }

    /**
     * 批量加入下载队列（P1-12：单次列表更新 + 单事务批量落库）。
     *
     * 原先是 `items.forEach { enqueue(it) }`：N 次全量列表拷贝 + N 个 Room 协程，
     * 全选 500 张时会明显卡顿，低端机可能 ANR。
     */
    fun enqueueAll(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val accepted = enqueueInternal(items)
        if (accepted.isEmpty()) return
        scope.launch {
            runCatching { taskDao.insertAll(accepted.map { DownloadTaskEntity.from(it) }) }
                .onFailure { AppLog.w("download", "批量任务落库失败：${it.message}") }
        }
        startDownloadService()
    }

    /** 清空已完成/失败的任务 */
    fun clearFinished() {
        _tasks.update { list ->
            list.filterNot { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }
        }
    }

    /**
     * 取消任务（用户手动关闭）。
     *
     * 原先只有 [clearFinished]（清已完成/失败），排队中与下载中的任务**无法关闭**——
     * 遇到「相机已断开、任务一直转圈」时用户只能杀进程（真机反馈）。
     *
     * - 排队中：标记后由队列消费循环跳过（Channel 内已入队的项无法移除，只能消费时过滤）
     * - 下载中：取消该任务的协程；[download] 的 finally 负责清理临时文件与 Room 记录。
     *   注意 PTP 的 socket 读不响应取消，实际中断会等到当前事务超时（≤30s）才生效，
     *   UI 侧会立即移除任务，不等它。
     *
     * 取消的任务不写入传输历史（它不是一次失败的传输）。
     */
    fun cancel(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (!task.state.isActive) return
        when (task.state) {
            DownloadState.QUEUED -> {
                cancelledIds.add(taskId)
                _tasks.update { list -> list.filterNot { it.id == taskId } }
                scope.launch { runCatching { taskDao.delete(taskId) } }
                AppLog.i("download", "已取消排队任务：${task.filename}")
            }
            DownloadState.DOWNLOADING -> {
                // 先标记：download() 的 finally 据此判断是取消还是失败
                cancelledIds.add(taskId)
                _tasks.update { list -> list.filterNot { it.id == taskId } }
                currentDownloadJob?.cancel()
                scope.launch { runCatching { taskDao.delete(taskId) } }
                AppLog.i("download", "已取消下载中任务：${task.filename}")
            }
            else -> Unit
        }
    }

    /** 取消全部进行中的任务（排队中 + 下载中） */
    fun cancelAllActive() {
        val ids = _tasks.value.filter { it.state.isActive }.map { it.id }
        for (id in ids) cancel(id)
        if (ids.isNotEmpty()) AppLog.i("download", "已取消全部进行中任务：${ids.size} 个")
    }

    /**
     * 启动下载前台服务（保活 + 通知进度；队列空闲时服务自动停止）。
     *
     * Android 12+ 从后台启动前台服务会抛 `ForegroundServiceStartNotAllowedException`，
     * 典型场景是 [restorePendingTasks] 在进程重启/退后台时拉起队列。此时下载本身
     * 不受影响（任务已在内存队列里，由 [scope] 驱动），只是暂时没有通知，故捕获后
     * 仅告警；用户回到前台再次入队时会自然补上通知。
     */
    private fun startDownloadService() {
        val intent = Intent(context, DownloadService::class.java)
        runCatching { context.startForegroundService(intent) }
            .onFailure { AppLog.w("download", "前台服务启动受限（后台启动被拒）：${it.message}") }
    }

    /** 串行下载单个任务（断点续传：分块落盘临时文件，失败退避重试，完成后提交相册） */
    private suspend fun download(item: MediaItem) {
        val taskId = item.thumbKey
        val startTime = System.currentTimeMillis()
        val cameraModel = repository.deviceModel
        updateTask(taskId) { it.copy(state = DownloadState.DOWNLOADING, progress = 0) }
        // 连接已断开（PTP 会话重建或超时自愈后句柄已失效）：直接失败。
        // 否则任务会走完 6 次分块重试（退避合计近 1 分钟）才报错，
        // 而每一次请求都注定拿到 0x2009（无效对象句柄）。
        if (isCameraDisconnected()) {
            AppLog.w("download", "相机连接已断开，跳过下载：${item.filename}")
            updateTask(taskId) {
                it.copy(
                    state = DownloadState.FAILED,
                    errorMessage = "相机连接已断开，请重新连接后再下载"
                )
            }
            return
        }
        var savedUri: Uri? = null
        var success = false
        // 临时文件：分块随机写落盘，完成后一次性提交到相册
        // （MediaStore/SAF 的 OutputStream 不支持 seek，无法在下载中途续写）
        val safeName = item.filename.replace(Regex("[^\\w.\\-]"), "_")
        val tempName = "imgd_${if (taskId.hashCode() < 0) -taskId.hashCode() else taskId.hashCode()}_$safeName"
        val tempFile = File(context.cacheDir, tempName)
        try {
            // 大小未知或 UPnP 通道（不支持分块）走整文件直下
            if (item.sizeBytes <= 0 || repository.currentChannelType == ChannelType.UPNP) {
                savedUri = repository.downloadToGallery(item) { loaded, total ->
                    val p = if (total > 0) (loaded * 100 / total).toInt() else 0
                    updateTask(taskId) { it.copy(progress = p) }
                }
                success = savedUri != null
            } else {
                success = downloadResumable(item, tempFile) { loaded, total ->
                    val p = if (total > 0) (loaded * 100 / total).toInt() else 0
                    updateTask(taskId) { it.copy(progress = p) }
                }
                if (success) {
                    savedUri = runCatching { repository.commitToGallery(item, tempFile) }.getOrNull()
                    // 提交相册失败时不能算成功：否则任务标 DONE 且 finally 删掉临时文件，
                    // 已下载的照片彻底丢失而 UI 报成功（数据丢失）
                    if (savedUri == null) {
                        success = false
                        updateTask(taskId) {
                            it.copy(errorMessage = "已下载但写入相册失败，请重试")
                        }
                    }
                }
            }
            updateTask(taskId) {
                it.copy(
                    state = if (success) DownloadState.DONE else DownloadState.FAILED,
                    progress = if (success) 100 else it.progress,
                    errorMessage = if (success) null else (it.errorMessage ?: "下载失败")
                )
            }
        } catch (e: Exception) {
            // 用户取消：任务已从列表移除，不再标失败。
            // 这里必须吞掉 CancellationException——否则它会经 job.join() 抛给队列
            // 消费循环，导致后续所有任务都不再出队（整个下载队列停摆）。
            if (e is kotlinx.coroutines.CancellationException) {
                AppLog.i("download", "下载已取消：${item.filename}")
            } else {
                updateTask(taskId) { it.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "下载失败") }
            }
        } finally {
            val cancelled = cancelledIds.remove(taskId)
            // 完成/失败/取消后都从 Room 移除（持久化只存排队中的任务）
            runCatching { taskDao.delete(taskId) }
            runCatching { tempFile.delete() }
            // 追加传输记录（成功或失败都记，供「传输记录」页长按查看详情）；
            // 用户主动取消不算一次传输，不记
            if (!cancelled) {
                runCatching {
                    historyDao.insert(
                        DownloadHistoryEntity(
                            filename = item.filename,
                            savedPath = uriToReadablePath(savedUri),
                            startTime = startTime,
                            endTime = System.currentTimeMillis(),
                            cameraModel = cameraModel,
                            sizeBytes = item.sizeBytes,
                            success = success
                        )
                    )
                }
            }
        }
    }

    /**
     * 相机连接是否已断开（**仅 PTP 通道可信**）。
     *
     * PTP 事务超时自愈（forceClose）或保活失败都会把连接状态置为 DISCONNECTED，
     * 而此时会话中的对象句柄已全部失效——继续下载只会拿到 0x2009。
     *
     * 注意 UPnP 通道没有状态跟踪，`connectionState` 恒为 DISCONNECTED，
     * 不排除它的话会把 UPnP 通道下的所有下载误判为断连。
     */
    private fun isCameraDisconnected(): Boolean {
        if (repository.currentChannelType != ChannelType.PTP_IP) return false
        return repository.connectionState.value == ChannelConnectionState.DISCONNECTED
    }

    /**
     * 断点续传核心：把 [item] 分块下载到 [tempFile]，网络中断时从已写字节处续传。
     * 每块 GET_PARTIAL_OBJECT 请求自带 offset，写盘用 RandomAccessFile 定位到 offset，
     * 因此即使某块中途失败，已落盘字节不丢失；失败后指数退避重试（最多 [DOWNLOAD_MAX_RETRIES] 次）。
     * @return 全部字节到齐返回 true；重试耗尽返回 false
     */
    private suspend fun downloadResumable(
        item: MediaItem,
        tempFile: File,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val total = item.sizeBytes
        val raf = RandomAccessFile(tempFile, "rw")
        val out = RafOutputStream(raf)
        return try {
            var offset = raf.length().coerceAtMost(total)
            var attempt = 0
            while (offset < total) {
                // 连接已断开：对象句柄随会话一起失效（相机回 0x2009），
                // 再重试只是空转——退避最长 15s × 6 次 ≈ 一分钟后才报错，
                // 用户只能干等。这里立即失败并给出可行动的原因。
                if (isCameraDisconnected()) {
                    AppLog.w("download", "相机连接已断开，放弃重试：${item.filename}")
                    updateTask(item.thumbKey) {
                        it.copy(errorMessage = "相机连接已断开，请重新连接后再下载")
                    }
                    return false
                }
                if (attempt > 0) {
                    // 指数退避：1s → 2s → 4s → 8s → 15s（封顶），给相机/网络恢复时间
                    val backoff = (DOWNLOAD_BACKOFF_BASE_MS shl (attempt - 1)).coerceAtMost(DOWNLOAD_BACKOFF_MAX_MS)
                    AppLog.w("download", "分块下载退避 ${backoff}ms 后重试（$attempt/$DOWNLOAD_MAX_RETRIES）offset=$offset")
                    delay(backoff)
                }
                try {
                    raf.seek(offset)
                    val before = raf.filePointer
                    repository.downloadRange(item, out, offset, DOWNLOAD_CHUNK_SIZE, onProgress)
                    val after = raf.filePointer
                    // 零推进守卫：相机可能回 OK 但不带任何 DataPacket（offset 越界、
                    // 实际字节数少于 GetObjectInfo 报告值、内容集被重建等）。
                    // downloadRange 返回 Unit，调用方无从得知实际写入量，只能靠文件指针判断。
                    // 不做这个判断就会以满速无限重发请求——且 attempt 恒为 0，退避永不生效，
                    // 表现为 CPU 100% + 手机发烫 + 进度卡死。
                    if (after <= before) {
                        attempt++
                        AppLog.w(
                            "download",
                            "分块零推进 offset=$offset（$attempt/$DOWNLOAD_MAX_RETRIES）：相机未返回数据"
                        )
                    } else {
                        offset = after
                        attempt = 0
                        onProgress(offset, total)
                    }
                } catch (e: Exception) {
                    attempt++
                    AppLog.w("download", "分块下载失败（offset=$offset，$attempt/$DOWNLOAD_MAX_RETRIES）：${e.message}")
                }
                if (attempt > DOWNLOAD_MAX_RETRIES) {
                    AppLog.e("download", "下载重试耗尽，放弃：${item.filename}（offset=$offset/$total）")
                    return false
                }
            }
            offset >= total
        } finally {
            runCatching { out.close() }
            runCatching { raf.close() }
        }
    }

    /** 把 RandomAccessFile 包装成 OutputStream，写入落到其当前文件指针处（用于分块定位写） */
    private class RafOutputStream(private val raf: RandomAccessFile) : OutputStream() {
        override fun write(b: Int) = raf.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
    }

    /** 把下载返回的 Uri 转成人类可读路径：MediaStore 用 RELATIVE_PATH+DISPLAY_NAME，SAF 回退文档 URI */
    private fun uriToReadablePath(uri: Uri?): String {
        if (uri == null) return ""
        return try {
            if (uri.scheme == "content") {
                val projection = arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val rel = c.getString(0)
                        val name = c.getString(1)
                        when {
                            rel != null && name != null -> "$rel$name"
                            name != null -> name
                            else -> uri.lastPathSegment ?: uri.toString()
                        }
                    } else {
                        uri.lastPathSegment ?: uri.toString()
                    }
                } ?: (uri.lastPathSegment ?: uri.toString())
            } else {
                uri.path ?: uri.toString()
            }
        } catch (_: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
