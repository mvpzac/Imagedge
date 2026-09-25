package com.imagedge.camera.data.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.model.MediaSessionCache
import com.imagedge.camera.data.model.isActive
import com.imagedge.camera.data.remote.CameraRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-27
 *     desc   : 全局下载管理器（串行队列，跨页面存活；前台服务保活 + 通知进度 + Room 持久化）
 *     version: 2.0 —— 任务状态随队列一起落库，进程重启后账目不丢
 * </pre>
 */

/**
 * 一次传输在内存里的排队凭据：要下哪个对象，属于哪一批。
 *
 * 批次号必须跟着队列项走而不是查内存：内存列表会被「清空」撤掉，
 * 而历史记录写入时要知道自己属于哪一批（重试与批次摘要都按批次对齐）。
 */
private data class QueuedDownload(val item: MediaItem, val batchId: Long)

@Singleton
class DownloadManager @Inject constructor(
    private val repository: CameraRepository,
    @ApplicationContext private val context: Context,
    private val taskDao: DownloadTaskDao,
    private val historyDao: DownloadHistoryDao,
    private val sessionCache: MediaSessionCache
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Room persistence and in-memory queue publication must have one total order. */
    private val queueMutationMutex = Mutex()

    /** A retry waits for any earlier cancellation delete for the same id to commit first. */
    private val pendingTaskDeletes = ConcurrentHashMap<String, Job>()

    /** 下载队列（串行消费） */
    private val queue = Channel<QueuedDownload>(Channel.UNLIMITED)

    /**
     * 用户已取消、但尚未被消费循环回收的任务 ID。
     *
     * Channel 无法移除已入队的单项，只能在消费时过滤；排队中的任务靠此集合跳过。
     * 下载中的任务也先进这里，供 [download] 的 finally 判断「是取消还是失败」。
     */
    private val cancelledIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 被系统 dataSync 时限掐掉的**下载中**任务。
     *
     * 这些任务的结局由 [stopAllForSystemTimeout] 自己写（状态 + 原因 + 记录），
     * [download] 的 finally 必须让位：它拿到的异常是协程取消，写成失败原因就是
     * 「Standalone coroutine was cancelled」，用户读不懂，而且真正的原因（系统时限）
     * 会被覆盖掉。
     */
    private val systemStoppedIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

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
            for (queued in queue) {
                val id = queued.item.thumbKey
                // 排队期间被用户取消：跳过本次下载（Channel 内已入队的项无法移除）
                if (cancelledIds.remove(id)) {
                    AppLog.i("download", "任务已取消，跳过：${queued.item.filename}")
                    continue
                }
                // 用可取消的子协程承载单次下载，用户取消时只中断这一个任务，
                // 不影响队列消费循环（download 内部会吞掉 CancellationException）
                val job = scope.launch { download(queued) }
                currentDownloadJob = job
                job.join()
                if (currentDownloadJob === job) currentDownloadJob = null
            }
        }
    }

    /**
     * 进程重启后恢复队列：状态、进度、失败原因、相册 Uri、批次、是否已看过，全部照库里的读。
     *
     * 只有当时未完成的行才重新排队（整文件重传）；已完成/已失败的行是**账目**，
     * 摆在列表里等用户处置，绝不自动重下——自动重试会在相机断链时把每一次开机
     * 都变成一轮新的传输风暴，而用户并没有要求它。
     */
    private suspend fun restorePendingTasks() {
        val resumed = queueMutationMutex.withLock {
            val rows = runCatching { taskDao.getAll() }.getOrNull() ?: return@withLock 0
            if (rows.isEmpty()) return@withLock 0
            val indexById = _tasks.value.associateBy { it.id }.toMutableMap()
            val toResume = ArrayList<QueuedDownload>(rows.size)
            _tasks.update { list ->
                val next = list.toMutableList()
                for (entity in rows) {
                    val item = entity.toMediaItem()
                    if (indexById.containsKey(item.thumbKey)) continue
                    val task = DownloadTask(
                        mediaItem = item,
                        batchId = entity.batchId,
                        state = restoredStateOf(entity.persistedState),
                        progress = entity.progress.coerceIn(0, 100),
                        errorMessage = entity.errorMessage,
                        thumbnail = sessionCache.gridThumbnails[item.thumbKey],
                        savedUri = entity.savedUri?.let { raw ->
                            runCatching { Uri.parse(raw) }.getOrNull()
                        },
                        failureViewed = entity.failureViewed
                    )
                    if (resumesOnRestore(entity.persistedState)) {
                        toResume += QueuedDownload(item, entity.batchId)
                    }
                    next += task
                    indexById[task.id] = task
                }
                next
            }
            AppLog.i("download", "恢复 ${rows.size} 条任务，其中 ${toResume.size} 条重新排队")
            for (queued in toResume) queue.trySend(queued)
            toResume.size
        }
        if (resumed > 0) startDownloadService()
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
        scope.launch {
            if (enqueuePersisted(listOf(item))) startDownloadService()
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
    private suspend fun enqueuePersisted(items: List<MediaItem>): Boolean {
        if (items.isEmpty()) return false
        // Cancellation removes the item from UI immediately, while Room deletion is asynchronous.
        // Joining that exact delete prevents it from racing a user who instantly taps Retry.
        items.asSequence()
            .mapNotNull { pendingTaskDeletes[it.thumbKey] }
            .distinct()
            .toList()
            .forEach { it.join() }
        return queueMutationMutex.withLock {
        val accepted = ArrayList<MediaItem>(items.size)
        // 先按当前快照筛出真正要入队的，避免在 update 里做重复判断
        val snapshot = _tasks.value
        val activeIds = snapshot.asSequence()
            .filter { it.state.isActive }
            .mapTo(mutableSetOf()) { it.id }
        for (item in items) {
            val id = item.thumbKey
            if (!activeIds.add(id)) continue
            accepted.add(item)
        }
        if (accepted.isEmpty()) return@withLock false

        // Room is the commit point. Publishing first lets a fast completion delete the row before
        // a late insert, resurrecting a ghost task on the next process start.
        // 批次号也在这里定：一次提交算一批，且必须与落库同生共死——
        // 先算好再落库的话，落库失败会白吃掉一个号，界面上出现「批次 7、6、4」这样的断号。
        val batchId = runCatching { taskDao.maxBatchId() + 1 }
            .onFailure { AppLog.w("download", "批次号读取失败，未加入队列：${it.message}") }
            .getOrElse { return@withLock false }
        val persisted = runCatching {
            taskDao.insertAll(accepted.map { DownloadTaskEntity.from(it, batchId) })
        }.onFailure {
            AppLog.w("download", "任务落库失败，未加入队列：${it.message}")
        }.isSuccess
        if (!persisted) return@withLock false

        val indexById = snapshot.associateBy { it.id }.toMutableMap()
        _tasks.update { list ->
            val next = list.toMutableList()
            for (item in accepted) {
                val id = item.thumbKey
                val previous = indexById[id]
                val reset = DownloadTask(
                    mediaItem = item,
                    batchId = batchId,
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
        for (item in accepted) queue.trySend(QueuedDownload(item, batchId))
        true
        }
    }

    /**
     * 批量加入下载队列（P1-12：单次列表更新 + 单事务批量落库）。
     *
     * 原先是 `items.forEach { enqueue(it) }`：N 次全量列表拷贝 + N 个 Room 协程，
     * 全选 500 张时会明显卡顿，低端机可能 ANR。
     */
    fun enqueueAll(items: List<MediaItem>) {
        if (items.isEmpty()) return
        scope.launch {
            if (enqueuePersisted(items)) startDownloadService()
        }
    }

    /**
     * 批量入队并**等待落库结果**。
     *
     * [enqueueAll] 是 fire-and-forget，调用方无从知道这批有没有真的进队列。照片页要
     * 「队列受理之后才清选择」，所以需要一个能等的入口：语义与 [enqueueAll] 完全一致
     * （同一把互斥锁、同一次批量落库、同样只在落库成功时启动服务），只是把结果回传。
     */
    suspend fun enqueueAllAwait(items: List<MediaItem>): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false
        val accepted = enqueuePersisted(items)
        if (accepted) startDownloadService()
        accepted
    }

    /**
     * 清空已完成/失败的任务：内存与库里一起删。
     *
     * 这是任务行**唯一**的常规删除时机。v2 之前完成即删，所以「重启后还能看见并重试未完成项」
     * 根本无从谈起；现在撤掉这一行的决定权在用户手上（点「清空」）。
     * 传输记录（download_history）不受影响——那是流水账，且删行也从不删照片文件。
     */
    fun clearFinished() {
        val settledIds = _tasks.value
            .filter { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }
            .mapTo(mutableSetOf()) { it.id }
        if (settledIds.isEmpty()) return
        _tasks.update { list -> list.filterNot { it.id in settledIds } }
        scheduleTaskDeletion(settledIds.toList())
    }

    /**
     * 取消任务（用户手动关闭）。
     *
     * 原先只有 [clearFinished]（清已完成/失败），排队中与下载中的任务**无法关闭**——
     * 遇到「相机已断开、任务一直转圈」时用户只能杀进程（真机反馈）。
     *
     * - 排队中：标记后由队列消费循环跳过（Channel 内已入队的项无法移除，只能消费时过滤）
     * - 下载中：取消该任务的协程；[download] 的 finally 负责 Room 记录。
     *   注意 PTP 的 socket 读不响应取消，实际中断会等到当前事务超时（≤30s）才生效，
     *   UI 侧会立即移除任务，不等它。
     *
     * 取消的任务不写入传输历史（它不是一次失败的传输），任务行也一并删除。
     */
    fun cancel(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (!task.state.isActive) return
        when (task.state) {
            DownloadState.QUEUED -> {
                cancelledIds.add(taskId)
                _tasks.update { list -> list.filterNot { it.id == taskId } }
                scheduleTaskDeletion(listOf(taskId))
                AppLog.i("download", "已取消排队任务：${task.filename}")
            }
            DownloadState.DOWNLOADING -> {
                // 先标记：download() 的 finally 据此判断是取消还是失败
                cancelledIds.add(taskId)
                _tasks.update { list -> list.filterNot { it.id == taskId } }
                val running = currentDownloadJob
                running?.cancel()
                scheduleTaskDeletion(listOf(taskId), waitFor = running)
                AppLog.i("download", "已取消下载中任务：${task.filename}")
            }
            else -> Unit
        }
    }

    /**
     * 取消全部进行中的任务（排队中 + 下载中）。
     *
     * 不能写成 `ids.forEach { cancel(it) }`：[cancel] 每次都要整表 `filterNot` 一次，
     * n 个待取消就是 n 次全表拷贝。任务行现在会跨重启累积（v3 起完成/失败不再被删），
     * 整卡选片一次就是几千条——实测 15 000 条时这条路直接把主线程跑到 ANR。
     * 语义与逐条取消完全一致，只是列表只重写一次。
     */
    fun cancelAllActive() {
        val active = _tasks.value.filter { it.state.isActive }
        if (active.isEmpty()) return
        val ids = active.map { it.id }
        val idSet = ids.toHashSet()
        val running = currentDownloadJob
        val hasDownloading = active.any { it.state == DownloadState.DOWNLOADING }
        // 与 [cancel] 同一套标记：排队中的项由消费循环按 cancelledIds 跳过，
        // 下载中的那一个先标记再取消，它的 finally 据此选择「删行」而不是「写失败」
        cancelledIds.addAll(ids)
        _tasks.update { list -> list.filterNot { it.id in idSet } }
        if (hasDownloading) running?.cancel()
        scheduleTaskDeletion(ids, waitFor = if (hasDownloading) running else null)
        AppLog.i("download", "已取消全部进行中任务：${ids.size} 个")
    }

    /**
     * Stop all transfers after Android revokes the dataSync foreground-service budget.
     *
     * PTP GetObject is a blocking whole-file operation, so calling Service.stopSelf() alone neither
     * pauses nor stops it. We mark the tasks failed, close the active channel to release the blocking
     * socket read and **persist** that outcome: the visible failed tasks are exactly what the user
     * retries after reconnecting the camera, so they must survive the process being killed next.
     * (v1/v2 deleted the restart row here, which threw the retry opportunity away with it.)
     */
    fun stopAllForSystemTimeout() {
        val active = _tasks.value.filter { it.state.isActive }
        if (active.isEmpty()) return
        val idSet = active.mapTo(mutableSetOf()) { it.id }
        // 只有「下载中」的那一个需要 finally 让位；排队中的靠消费循环按 cancelledIds 跳过，
        // 它们永远不会走到 finally。两边都别多塞，否则同一次重试会被误当成已取消而静默丢弃。
        val runningId = active.firstOrNull { it.state == DownloadState.DOWNLOADING }?.id
        if (runningId != null) systemStoppedIds.add(runningId)
        cancelledIds.addAll(active.filter { it.state == DownloadState.QUEUED }.map { it.id })
        _tasks.update { list ->
            list.map { task ->
                if (task.id in idSet) {
                    task.copy(
                        state = DownloadState.FAILED,
                        errorMessage = SYSTEM_TIMEOUT_MESSAGE
                    )
                } else task
            }
        }
        val running = currentDownloadJob
        running?.cancel()
        scope.launch {
            queueMutationMutex.withLock {
                for (task in active) {
                    runCatching {
                        taskDao.updateOutcome(
                            id = task.id,
                            state = DownloadState.FAILED.name,
                            progress = task.progress,
                            errorMessage = SYSTEM_TIMEOUT_MESSAGE,
                            savedUri = null
                        )
                    }.onFailure { AppLog.w("download", "系统超时结果落库失败：${it.message}") }
                }
            }
            // 与旧版一致：超时**不**写传输记录。这些项根本没传完（或还没开始），
            // 记进流水账会让「传输失败」变成「传输过但失败了」的假事实。
            // Closing the channel is what actually interrupts a blocking PTP/HTTP read.
            runCatching { repository.disconnect() }
                .onFailure { AppLog.w("download", "系统超时后断开相机失败：${it.message}") }
        }
    }

    /**
     * 用户已经站在传输页看着这些失败了 —— 把本批失败标记为已看，全局任务条随之消失。
     *
     * 先改内存再落库：这个标记只是「有没有必要再占一次底部」的记账，
     * 中间进程被杀的最坏后果是下次开机又提示一遍，而不是丢用户的传输数据。
     */
    fun markBatchFailuresViewed() {
        val batchId = latestBatchId(_tasks.value) ?: return
        if (_tasks.value.none {
                it.batchId == batchId && it.state == DownloadState.FAILED && !it.failureViewed
            }
        ) return
        _tasks.update { list ->
            list.map { task ->
                if (task.batchId == batchId && task.state == DownloadState.FAILED &&
                    !task.failureViewed
                ) task.copy(failureViewed = true) else task
            }
        }
        scope.launch {
            queueMutationMutex.withLock {
                runCatching { taskDao.markBatchFailuresViewed(batchId) }
                    .onFailure { AppLog.w("download", "失败已看标记落库失败：${it.message}") }
            }
        }
    }

    /** Serialize cancellation cleanup with re-enqueue and expose the pending delete to retries. */
    private fun scheduleTaskDeletion(ids: List<String>, waitFor: Job? = null) {
        if (ids.isEmpty()) return
        val distinctIds = ids.distinct()
        val deletion = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // A running task deletes its own row in finally. Wait until that cleanup has completed
            // before allowing a new task with the same id to persist.
            waitFor?.join()
            queueMutationMutex.withLock {
                runCatching { taskDao.deleteAll(distinctIds) }
                    .onFailure { AppLog.w("download", "取消任务落库失败：${it.message}") }
            }
        }
        distinctIds.forEach { id -> pendingTaskDeletes[id] = deletion }
        deletion.invokeOnCompletion {
            distinctIds.forEach { id -> pendingTaskDeletes.remove(id, deletion) }
        }
        deletion.start()
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

    /**
     * 串行下载单个任务（整文件流式下载，直写相册）。
     *
     * **为什么不用分块下载（GET_PARTIAL_OBJECT）**：0.1.5 引入分块断点续传后，
     * 真机（ZV-E10，整卡 ContentsTransfer 模式）实测相机对**每个分块**都返回
     * `0x2009（无效对象句柄）`——该机型在此模式下不支持分块读取，导致所有下载
     * 全部失败；而 0.1.3 的整文件下载（GetObject）在同一环境实测可用。
     * 稳定性优先，回归整文件路径；代价是中断后需整体重下（相机端单文件读取
     * 25MB RAW 实测 ~6s，可接受）。分块的协议实现保留在 `:ptp` / [CameraRepository]
     * 中，待确认机型的支持范围后再评估按机型启用。
     */
    private suspend fun download(queued: QueuedDownload) {
        val item = queued.item
        val taskId = item.thumbKey
        val startTime = System.currentTimeMillis()
        val cameraModel = repository.deviceModel
        updateTask(taskId) { it.copy(state = DownloadState.DOWNLOADING, progress = 0) }
        var savedUri: Uri? = null
        var success = false
        var failureMessage: String? = null
        try {
            // Keep the preflight inside try/finally. Otherwise its early return leaves the Room row
            // behind and the same task is restored and failed again on every process start.
            // （v3 起这一句的含义变了：留在库里的行不再被删，它是要被如实恢复成失败态的账目，
            //  而不是每次开机都要再撞一次的幽灵任务。）
            if (isCameraDisconnected()) {
                AppLog.w("download", "相机连接已断开，跳过下载：${item.filename}")
                failureMessage = "相机连接已断开，请重新连接后再下载"
                return
            }
            var lastProgressAt = 0L
            var lastProgress = -1
            savedUri = repository.downloadToGallery(item) { loaded, total ->
                val p = if (total > 0) (loaded * 100 / total).toInt() else 0
                val now = android.os.SystemClock.elapsedRealtime()
                if (p == 100 || (p != lastProgress && now - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_MS)) {
                    lastProgress = p
                    lastProgressAt = now
                    updateTask(taskId) { it.copy(progress = p.coerceIn(0, 100)) }
                }
            }
            success = savedUri != null
            if (!success) failureMessage = "下载失败"
        } catch (e: Exception) {
            // 用户取消：任务已从列表移除，不再标失败。
            // 这里必须吞掉 CancellationException——否则它会经 job.join() 抛给队列
            // 消费循环，导致后续所有任务都不再出队（整个下载队列停摆）。
            if (e is kotlinx.coroutines.CancellationException) {
                AppLog.i("download", "下载已取消：${item.filename}")
            } else {
                failureMessage = e.message ?: "下载失败"
            }
        } finally {
            finishDownload(taskId, item, savedUri, success, failureMessage, startTime, cameraModel)
        }
    }

    /**
     * 把一次传输的结局写进 Room、内存与流水账。
     *
     * 三件事必须在**同一把锁、同一段不可取消的上下文**里做完：
     * - 下载协程是被取消才走到这里的，普通挂起（拿锁、写库）会在这一行立刻抛出，
     *   结局就永远写不进库——那正是「重启后状态不丢」失效的地方，所以要 NonCancellable；
     * - 落库与内存发布同序，[enqueuePersisted] 才不会插到一半（这是本类从一开始的约定）；
     * - 「取消就删行」与「重试要插行」互斥，靠的还是同一把锁 + [pendingTaskDeletes]。
     */
    private suspend fun finishDownload(
        taskId: String,
        item: MediaItem,
        savedUri: Uri?,
        success: Boolean,
        failureMessage: String?,
        startTime: Long,
        cameraModel: String
    ) {
        // 系统时限的那次下载：结局已由 stopAllForSystemTimeout 写好，这里只认账不再改写
        if (systemStoppedIds.remove(taskId)) return
        val cancelled = cancelledIds.remove(taskId)
        withContext(NonCancellable) {
            queueMutationMutex.withLock {
                val state = if (success) DownloadState.DONE else DownloadState.FAILED
                val progress = when {
                    success -> 100
                    else -> _tasks.value.firstOrNull { it.id == taskId }?.progress ?: 0
                }
                val errorMessage = if (success) null else (failureMessage ?: "下载失败")
                if (cancelled) {
                    runCatching { taskDao.delete(taskId) }
                        .onFailure { AppLog.w("download", "取消任务落库失败：${it.message}") }
                } else {
                    runCatching {
                        taskDao.updateOutcome(
                            id = taskId,
                            state = state.name,
                            progress = progress,
                            errorMessage = errorMessage,
                            savedUri = savedUri?.toString()
                        )
                    }.onFailure { AppLog.w("download", "传输结果落库失败：${it.message}") }
                }
                if (!cancelled) {
                    updateTask(taskId) {
                        it.copy(
                            state = state,
                            progress = progress,
                            errorMessage = errorMessage,
                            // 记下相册 Uri —— 「查看」「编辑」「分享」都据此打开原图
                            savedUri = savedUri
                        )
                    }
                }
                // 追加传输记录（成功或失败都记，供「记录」页打开与重试）；
                // 用户主动取消不算一次传输，不记 —— 记了就把「我按了取消」写成「相机传坏了」
                if (!cancelled) {
                    runCatching {
                        historyDao.insert(
                            historyRecord(
                                item = item,
                                savedUri = savedUri,
                                startTime = startTime,
                                success = success,
                                cameraModel = cameraModel,
                                errorMessage = errorMessage
                            )
                        )
                    }
                }
            }
        }
    }

    private fun historyRecord(
        item: MediaItem,
        savedUri: Uri?,
        startTime: Long,
        success: Boolean,
        cameraModel: String,
        errorMessage: String?
    ) = DownloadHistoryEntity(
        filename = item.filename,
        savedPath = readablePathOf(context, savedUri),
        startTime = startTime,
        endTime = System.currentTimeMillis(),
        cameraModel = cameraModel,
        sizeBytes = item.sizeBytes,
        success = success,
        // 这四列就是「记录行能不能重试、能不能打开」的全部依据；缺一项界面就少一个动作
        channelKey = item.channelKey,
        handle = item.handle,
        photoType = item.photoType.name,
        captureDate = item.captureDate?.time,
        savedUri = savedUri?.toString(),
        errorMessage = errorMessage
    )

    /** PTP 与 UPnP 都由仓库转发真实连接状态；断线后不再消费已经失效的下载任务。 */
    private fun isCameraDisconnected(): Boolean = !repository.isConnected

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 150L
        const val SYSTEM_TIMEOUT_MESSAGE = "系统后台传输时限已到，请重新连接相机后重试"
    }
}
