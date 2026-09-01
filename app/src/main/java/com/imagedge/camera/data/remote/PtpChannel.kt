package com.imagedge.camera.data.remote

import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.ptp.PtpIpClient
import com.imagedge.camera.ptp.PtpResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.OutputStream
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP/IP 通道（RAW / 视频 / 全存储 / 缩略图）
 *     version: 1.1 —— 事务互斥可取消 + 超时自愈 + 断链自动重连
 * </pre>
 */

/** 日志 tag */
private const val TAG = "ptp"

/** 单个 PTP 事务超时：相机在内容库重建（如第二次发送）期间可能短暂不响应 */
private const val PTP_CALL_TIMEOUT_MS = 30_000L

/** 相册扫描整体超时（GetObjectHandles + 多个 GetObjectInfo） */
private const val SCAN_TIMEOUT_MS = 60_000L

/**
 * 相册扫描单批句柄数（分批持锁粒度）。
 *
 * 20 个句柄 × 约 200ms/次 ≈ 单批持锁 4s，远低于保活的 30s 超时阈值，
 * 保活与下载可在批间正常插队。数值越大吞吐越高但越容易被保活误判为「卡死」。
 */
private const val SCAN_BATCH_SIZE = 20

/** 大文件下载超时（25MB RAW 实测 ~6s；10 分钟余量覆盖大视频） */
private const val DOWNLOAD_TIMEOUT_MS = 600_000L

/**
 * 对象枚举超时（用于整卡的 GetObjectHandles）。
 *
 * 整卡（ContentsTransfer）下相机要遍历整张 SD 卡建立索引，上千对象时单次调用
 * 就可能超过默认的 30s；用默认超时会被判成「相机忙」并触发 forceClose 自愈——
 * 连接一断，刚枚举到的句柄全部作废，之后的下载统统返回 0x2009。
 * 这里给足余量，让慢的枚举也能完整跑完。
 */
private const val ENUMERATE_TIMEOUT_MS = 120_000L

/**
 * 索尼「内容传输虚拟存储」：GetStorageIDs 返回的 15794177 = 0xF10001 即此虚拟存储
 * （参考 Sony-ZV-E10-RX 实现）。相机端选片集合挂在它下面。
 * 注意：枚举的 association 参数必须用 0x0（全枚举）——参考实现硬编码的 0x10 是
 * 「当次会话 folder 对象的 handle」，动态分配，第二次发送后失效（0x2009）。
 */
private const val SONY_CONTENT_TRANSFER_STORAGE_ID = 0xF10001L

@Singleton
class PtpChannel @Inject constructor() : CameraChannel {

    private var client: PtpIpClient? = null
    private var keepAliveJob: Job? = null
    private var eventJob: Job? = null
    private var lastHost: String? = null

    /** 功能模式：0=RemoteControl（遥控/选片集，默认），1=ContentsTransfer（整卡）。 */
    var functionMode: Int = 0
        private set

    /**
     * PTP 静默期截止时间戳。拍照/录像后相机会写 SD 卡，此时若 PTP 保活/轮询持续发命令
     * 会干扰写卡（表现为"一直写不进去"）。静默期内跳过保活与相册扫描，让相机专心写卡。
     */
    @Volatile
    private var silenceUntil = 0L

    /** 进入静默期：暂停 PTP 活动 [durationMs] 毫秒（拍照/录像写卡让路） */
    fun silence(durationMs: Long = 6000) {
        silenceUntil = System.currentTimeMillis() + durationMs
    }

    private fun isSilenced(): Boolean = System.currentTimeMillis() < silenceUntil

    /**
     * PTP 事务互斥（可取消的 suspend 锁，替代原 PtpIpClient 的 @Synchronized）。
     * 背景：下载 25MB RAW 会持锁数十秒，@Synchronized 的 monitor 等待不可取消、
     * 不受 soTimeout 管辖——相机一旦在持锁期间不响应，轮询/保活全部无限挂死（真机实测）。
     */
    private val ptpMutex = Mutex()

    private val _connectionState = MutableStateFlow(ChannelConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ChannelConnectionState> = _connectionState.asStateFlow()

    /** 相机内容变化事件（选片推送 / 内容集重建），触发 UI 立即刷新（免等 4s 轮询） */
    override val contentEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 拍摄完成事件（CaptureComplete 0x400A），携带相机返回的新对象句柄。
     * 用于「遥控拍摄照片自动拉回」：收到后即可增量拉取刚拍的照片。
     */
    val captureEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 设备属性变化事件：相机端拨盘/菜单改动参数时推送（真机实测走
     * 0xC203 SonyDevicePropChanged，标准 0x4006 亦兼容），触发参数回显刷新。
     */
    val propEvents = MutableSharedFlow<Int>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override val channelType: ChannelType = ChannelType.PTP_IP
    override var deviceModel: String = ""
        private set

    override suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        connectInternal(host, functionMode)
    }

    /**
     * 切换功能模式并重连（选片集 RemoteControl=0 / 整卡 ContentsTransfer=1）。
     * 会断开当前会话重建，正在进行的下载可能中断。
     * 相机断开旧会话后可能短暂拒绝新连接，失败时延迟重试一次；仍失败返回 false 并回退原模式。
     */
    suspend fun switchFunctionMode(mode: Int): Boolean = withContext(Dispatchers.IO) {
        val host = lastHost ?: return@withContext false
        if (mode == functionMode) return@withContext true
        val prevMode = functionMode
        AppLog.i(TAG, "切换功能模式：$prevMode → $mode，重连 $host")
        functionMode = mode
        // 断开旧会话后稍等，让相机释放连接
        delay(500)
        val ok = runCatching { connectInternal(host, mode) }.isSuccess
        if (!ok) {
            AppLog.w(TAG, "功能模式切换失败，延迟重试一次…")
            delay(1000)
            val retry = runCatching { connectInternal(host, mode) }.isSuccess
            if (!retry) {
                AppLog.e(TAG, "功能模式切换最终失败，回退到原模式 $prevMode")
                functionMode = prevMode
                runCatching { connectInternal(host, prevMode) }
                return@withContext false
            }
        }
        AppLog.i(TAG, "功能模式切换成功：mode=$mode")
        true
    }

    private suspend fun connectInternal(host: String, mode: Int) {
        disconnect()
        lastHost = host
        val newClient = PtpIpClient(host)
        // 「电脑遥控 / 发送到智能手机」模式为 Imaging Edge 私有协议：
        // 双连接握手（alpha-fairy 顺序）→ OpenSession（按功能模式）→ SDIO 初始化 → 内容传输模式
        newClient.connect()
        try {
            if (mode == 1) {
                newClient.sonyOpenSession(1)  // 0x9210 [1,1]：ContentsTransfer 整卡
            } else {
                newClient.openSession()  // 标准 0x1002：RemoteControl 选片集
            }
            newClient.sonyInitSequence()
            newClient.sonyTryContentsTransferMode(mode)  // 0x9212：整卡 [2,0,0]→[2,1,0]；选片集 {1,0,0}
            deviceModel = newClient.getDeviceInfo().model
        } catch (t: Throwable) {
            // 握手半途失败必须回收半成品客户端的 socket，否则每次失败都泄漏一对连接
            runCatching { newClient.forceClose() }
            throw t
        }
        client = newClient
        _connectionState.value = ChannelConnectionState.CONNECTED
        startKeepAlive(newClient)
        startEventMonitor(newClient)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        eventJob?.cancel()
        eventJob = null
        runCatching { client?.disconnect() }
            .onFailure { runCatching { client?.forceClose() } }
        client = null
        _connectionState.value = ChannelConnectionState.DISCONNECTED
    }

    /**
     * 带互斥与超时的 PTP 调用。
     *
     * 超时（相机忙/内容库重建不响应）且 [selfHeal] 为 true 时，强制关闭底层 socket——
     * 让阻塞在 read 上的线程立刻收到 SocketException 而解除，避免整个通道永久挂死。
     *
     * **为什么需要 [selfHeal] 开关（真机 P0 修复）**：
     * 保活协程与业务协程共享同一把 [ptpMutex]，而 withTimeout 的计时从「开始等锁」起算。
     * 原先保活等待 30s 超时后无条件 forceClose，会把**正在持锁的下载/扫描**的 socket
     * 一起关掉——表现为大相册扫描到一半全部失败、只返回残缺列表，且 UI 仍显示已连接。
     * 因此：业务调用（下载/扫描/参数）保留自愈；保活等后台心跳只记录并放弃本次，
     * 绝不能替业务做主关掉连接。
     *
     * @param selfHeal 超时时是否强制关闭底层连接自愈。业务调用用 true，保活心跳用 false。
     */
    private suspend fun <T> ptpCall(
        timeoutMs: Long = PTP_CALL_TIMEOUT_MS,
        selfHeal: Boolean = true,
        block: suspend () -> T
    ): T = try {
        withTimeout(timeoutMs) { ptpMutex.withLock { block() } }
    } catch (e: TimeoutCancellationException) {
        if (selfHeal) {
            AppLog.e(TAG, "PTP 事务超时（${timeoutMs}ms）——相机可能正在忙，强制关闭连接自愈")
            runCatching { client?.forceClose() }
            // P2-4：forceClose 后必须同步状态。原先只有保活失败才置 DISCONNECTED，
            // 自愈强关后 UI 仍显示「已连接」，但通道实际已死，用户点任何操作都失败
            // 却看不到断线提示，只能等下一次 listMedia 兜底重连。
            _connectionState.value = ChannelConnectionState.DISCONNECTED
        } else {
            AppLog.w(TAG, "PTP 事务超时（${timeoutMs}ms）——通道正被业务占用，跳过本次不强制关闭")
        }
        throw IOException("PTP 事务超时（相机可能正在忙）", e)
    }

    /**
     * 事件流监听：持续消费相机推送的事件（RequestObjectTransfer/ObjectAdded/Probe…）。
     * 此前事件流从未读取——选片发送的 RequestObjectTransfer 无人消费，
     * 相机的内容集因此不刷新（第二次发送后 GetObjectHandles 返回旧快照/0x2009）。
     */
    private fun startEventMonitor(newClient: PtpIpClient) {
        eventJob?.cancel()
        eventJob = CoroutineScope(Dispatchers.IO).launch {
            // P2-5：外层重试循环。原先监听协程一旦读异常就 break 静默死亡，
            // 相机事件（选片推送/拍摄完成/参数变化）从此永久失效，且没有任何自愈——
            // 用户感知是「相机上选了片，手机相册永远不刷新」，只能重连。
            // 事件流中断（TCP 抖动、相机瞬时忙）并不等于整个会话断开，因此这里
            // 只重试事件流本身；真正的断链由保活失败统一判定并置 DISCONNECTED。
            while (isActive) {
                try {
                    newClient.eventPollMode()
                    AppLog.i(TAG, "事件流监听启动（3s 轮询）")
                    while (isActive) {
                        // 静默期（拍照/录像写卡）内暂停读事件，避免占用事件通道干扰写卡
                        if (isSilenced()) {
                            delay(500)
                            continue
                        }
                        val event = newClient.readEvent() ?: continue
                        handleCameraEvent(newClient, event)
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    AppLog.w(TAG, "事件流中断（5s 后重试）：${e::class.simpleName}: ${e.message}")
                    delay(5_000)
                }
            }
        }
    }

    /** 处理单个相机事件（事件码 → 语义名 + 分发到对应 Flow） */
    private fun handleCameraEvent(newClient: PtpIpClient, event: com.imagedge.camera.ptp.Event) {
        val name: String
        val contentChanged: Boolean
        when (event.eventCode) {
            0x4002 -> { name = "ObjectAdded"; contentChanged = true }
            0x4003 -> { name = "ObjectRemoved"; contentChanged = true }
            0x4004 -> { name = "StoreAdded（内容集重建）"; contentChanged = true }
            0x4005 -> { name = "StoreRemoved（内容集销毁）"; contentChanged = false }
            0x4009 -> { name = "RequestObjectTransfer（相机请求手机接收）"; contentChanged = true }
            0x400D -> { name = "StoreFull"; contentChanged = false }
            0x4006 -> {
                name = "DevicePropChanged（属性变化）"
                contentChanged = false
                event.parameters.firstOrNull()?.let {
                    propEvents.tryEmit(it.toInt() and 0xFFFF)
                }
            }
            0x400A -> {
                name = "CaptureComplete（拍摄完成，自动拉回）"
                contentChanged = true
                val handle = event.parameters.firstOrNull() ?: 0xFFFFFFFFL
                captureEvents.tryEmit(handle)
            }
            0xC203 -> {
                name = "SonyDevicePropChanged（索尼扩展属性变化）"
                contentChanged = false
                // 真机实测：拨盘/菜单改动推 0xC203（参数=0 表示全量变化），
                // 触发手机端参数回显刷新
                event.parameters.firstOrNull()?.let {
                    propEvents.tryEmit(it.toInt() and 0xFFFF)
                }
            }
            0xC223 -> {
                name = "SonyObjectPropChanged"
                contentChanged = false
                event.parameters.firstOrNull()?.let {
                    propEvents.tryEmit(it.toInt() and 0xFFFF)
                }
            }
            else -> { name = "其他事件"; contentChanged = false }
        }
        AppLog.i(TAG, "收到相机事件 0x${event.eventCode.toString(16)} $name（参数=${event.parameters.joinToString()}）")
        if (contentChanged) contentEvents.tryEmit(Unit)
    }

    /**
     * 是否为「等锁超时」——即通道正被别的长事务（整卡枚举 / 大文件下载）占用，
     * 而非连接本身出了问题。
     *
     * [ptpCall] 超时时统一抛 `IOException("PTP 事务超时（相机可能正在忙）")`，
     * 即使是 `selfHeal = false` 的调用方（如保活）拿到的也是这个类型，
     * 因此按消息区分：超时视为「忙」，其余 IO 异常才视为真断连。
     */
    private fun isBusyTimeout(e: Throwable): Boolean =
        e is IOException && e.message?.contains("PTP 事务超时") == true

    /**
     * PTP 会话保活：相机 ~30s 无活动会主动断开连接（实测多次）。
     * 每 10s 发一次 GetDeviceInfo 复位相机的闲置计时器；失败即停止（下次连接重建）。
     * keepAlivePaused=true 时暂停 ping（App 退后台且无活跃下载时由 CameraRepository 置位，
     * 遵守功耗标准：后台不得进行非必要的持续网络活动）。
     */
    @Volatile
    var keepAlivePaused: Boolean = false

    private fun startKeepAlive(newClient: PtpIpClient) {
        keepAliveJob?.cancel()
        keepAliveJob = CoroutineScope(Dispatchers.IO).launch {
            var failureLogged = false
            while (isActive) {
                // 后台暂停保活：轻量等待（2s 轮询标志位，无网络开销），回前台/下载开始即恢复
                while (isActive && keepAlivePaused) delay(2_000L)
                delay(10_000L)
                // 静默期内等待到结束，避免保活干扰相机写卡
                val remaining = silenceUntil - System.currentTimeMillis()
                if (remaining > 0) {
                    delay(remaining)
                    continue
                }
                // 保活不得触发 forceClose：它等的是别的业务持有的锁，超时只说明「通道正忙」，
                // 不代表通道已死。原先这里会强关 socket，把进行中的下载/扫描一起杀掉。
                runCatching { ptpCall(selfHeal = false) { newClient.getDeviceInfo() } }.onFailure { e ->
                    // 等待锁超时 ≠ 连接断开。整卡枚举这类长事务会独占通道数十秒，
                    // 保活等不到锁就会超时；此时若判为断连并停掉保活协程，相机在
                    // 30s 无活动后会真的把我们踢掉——于是「扫描越久越容易断线」。
                    if (isBusyTimeout(e)) {
                        AppLog.d(TAG, "保活本轮跳过（通道正忙，等锁超时）：${e.message}")
                        return@onFailure
                    }
                    if (!failureLogged) {
                        AppLog.w(TAG, "PTP 保活失败（相机可能已断开，停止保活）：${e.message}")
                        failureLogged = true
                    }
                    _connectionState.value = ChannelConnectionState.DISCONNECTED
                    return@launch
                }
            }
        }
    }

    override suspend fun listMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val acc = mutableListOf<MediaItem>()
        listMediaIncremental { batch -> acc.addAll(batch) }
        acc.sortedByDescending { it.captureDate ?: Date(0) }
    }

    override suspend fun listMediaIncremental(
        onBatch: suspend (List<MediaItem>) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        try {
            scanMedia(c, onBatch)
        } catch (e: Exception) {
            // 只是等锁超时（通道正被长事务占用）：**不要重连**。
            // 重建会话会让已枚举的对象句柄全部失效，正在下载的任务随即 0x2009；
            // 而通道忙是暂时状态，直接抛出让上层稍后重试即可。
            if (isBusyTimeout(e)) throw e
            // 连接类异常（相机第二次发送时重置会话/内容库、或事务超时已 forceClose）
            // → 自动重连一次再扫描：用户无需手动重连，新照片下一轮轮询即可出现
            val recoverable = e is IOException || e is PtpResponseException
            if (!recoverable || lastHost == null) throw e
            AppLog.w(TAG, "相册扫描连接异常（${e::class.simpleName}: ${e.message}），尝试自动重连…")
            runCatching { connect(lastHost!!) }
                .onFailure {
                    AppLog.w(TAG, "自动重连失败：${it::class.simpleName}: ${it.message}")
                    _connectionState.value = ChannelConnectionState.DISCONNECTED
                    throw e
                }
            AppLog.i(TAG, "自动重连成功（${deviceModel}），重新扫描")
            scanMedia(client ?: throw e, onBatch)
        }
    }

    private suspend fun scanMedia(
        c: PtpIpClient,
        onBatch: suspend (List<MediaItem>) -> Unit
    ): Int {
        // 静默期（相机正在写卡）：**等待**其结束再枚举，而不是直接返回空列表。
        // 直接返回空会让整卡页显示「没有照片」，而整卡是快照语义、不会自动补全，
        // 用户只能反复手动点重试（真机反馈：进整卡经常要重试好几次才出图）。
        if (isSilenced()) {
            val remaining = silenceUntil - System.currentTimeMillis()
            if (remaining > 0) {
                AppLog.i(TAG, "扫描命中 PTP 静默期（相机写卡中），等待 ${remaining}ms 后继续枚举")
                delay(remaining)
            }
        }
        // 扫描全程 selfHeal = false：扫描是**只读**操作，超时了重试即可，
        // 不需要杀掉连接。而 forceClose 的代价极高——连接一断，会话里的对象句柄
        // 全部失效，之后每个下载都回 0x2009（正是「所有图片下载失败」的来源）。
        try {
            // 获取真实存储 ID（ContentsTransfer 模式下为 0x10001 等，非硬编码 0xF10001）
            var storageIds = ptpCall(selfHeal = false) { c.getStorageIds() }
            AppLog.i(TAG, "GetStorageIDs = [${storageIds.joinToString { "0x${it.toString(16)}" }}]")
            if (storageIds.isEmpty()) {
                AppLog.w(TAG, "存储列表为空，回退硬编码 0xF10001")
                storageIds = listOf(SONY_CONTENT_TRANSFER_STORAGE_ID)
            }
            // 枚举所有存储的句柄（CokeeZVE：GetObjectHandles(storageId, 0, 0)）
            var handles = enumerateAll(c, storageIds)
            var retry = 0
            // 刚切到整卡（ContentsTransfer）时相机的 SD 卡索引可能仍在建立，
            // 首次 GetObjectHandles 常返回空。原先只重试 2 次（2s / 4s），等待窗口
            // 偏短，索引慢的相机会一直空着；放宽为 3 次（2s / 3s / 5s，合计约 10s）。
            val emptyRetryDelays = longArrayOf(2_000L, 3_000L, 5_000L)
            while (handles.isEmpty() && retry < emptyRetryDelays.size) {
                val wait = emptyRetryDelays[retry]
                retry++
                AppLog.w(TAG, "内容集为空，${wait}ms 后重试 $retry/${emptyRetryDelays.size}")
                delay(wait)
                storageIds = ptpCall(selfHeal = false) { c.getStorageIds() }.ifEmpty { storageIds }
                handles = enumerateAll(c, storageIds)
            }
            AppLog.i(TAG, "内容集扫描：${handles.size} 个对象（存储 ${storageIds.joinToString { "0x${it.toString(16)}" }}）")
            // 分批持锁：每批 SCAN_BATCH_SIZE 个句柄一次 ptpCall。
            //
            // 原先「整个扫描一把锁」在整卡模式（上千对象）下会持锁数十秒，期间保活抢锁
            // 超时触发 forceClose，把扫描自己的 socket 关掉 → 只返回残缺列表（真机 P0）。
            // 分批后单批持锁约 4s（20×200ms），保活/下载可在批间插队，互不掐断；
            // 代价是批与批之间相机内容可能变化——对相册浏览场景可接受（下一轮轮询会修正）。
            //
            // 每批处理完即回调，让上层**边扫描边渲染**，而不是等上千对象全部枚举完
            // 才一次性返回（否则首屏要等数分钟）。
            var total = 0
            handles.chunked(SCAN_BATCH_SIZE).forEach { batch ->
                val batchItems = ptpCall(timeoutMs = SCAN_TIMEOUT_MS, selfHeal = false) {
                    val acc = mutableListOf<MediaItem>()
                    for (handle in batch) {
                        val info = runCatching { c.getObjectInfo(handle) }
                            .onFailure { AppLog.w(TAG, "GetObjectInfo($handle) 失败：${it.message}") }
                            .getOrNull() ?: continue
                        if (info.photoType == PhotoType.OTHER) {
                            AppLog.i(
                                TAG,
                                "跳过对象 handle=$handle：format=0x${info.formatCode.toString(16)} filename=${info.filename}"
                            )
                            continue
                        }
                        acc.add(
                            MediaItem(
                                handle = handle,
                                channelKey = handle.toString(),
                                filename = info.filename,
                                sizeBytes = info.compressedSize,
                                photoType = info.photoType,
                                captureDate = info.captureDate
                            )
                        )
                    }
                    acc
                }
                if (batchItems.isNotEmpty()) {
                    total += batchItems.size
                    onBatch(batchItems)
                }
            }
            AppLog.i(TAG, "相册扫描完成：$total 个媒体项")
            return total
        } catch (e: Exception) {
            AppLog.e(TAG, "相册列表获取失败：${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * 枚举所有存储的句柄（parent=0x0，CokeeZVE 实测）。
     *
     * 用 [ENUMERATE_TIMEOUT_MS] 而非默认事务超时：整卡下这一调用本身就可能耗时
     * 数十秒，按默认 30s 判定会在它**正常执行中**被自己的超时打断并 forceClose。
     */
    private suspend fun enumerateAll(c: PtpIpClient, storageIds: List<Long>): List<Long> {
        val all = mutableListOf<Long>()
        for (sid in storageIds) {
            val handles = ptpCall(timeoutMs = ENUMERATE_TIMEOUT_MS, selfHeal = false) {
                c.getObjectHandles(sid, 0x0)
            }
            AppLog.i(TAG, "存储 0x${sid.toString(16)} → ${handles.size} 个句柄")
            all += handles
        }
        return all
    }

    override suspend fun getThumbnail(item: MediaItem): ByteArray? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        runCatching { ptpCall { c.getThumbnail(item.handle) } }.getOrElse { e ->
            AppLog.w(TAG, "缩略图获取失败 handle=${item.handle}：${e.message}")
            null
        }
    }

    override suspend fun download(
        item: MediaItem,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        ptpCall(DOWNLOAD_TIMEOUT_MS) {
            c.getObject(item.handle, output) { loaded, total -> onProgress(loaded, total) }
        }
    }

    override suspend fun downloadRange(
        item: MediaItem,
        output: OutputStream,
        offset: Long,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        ptpCall(DOWNLOAD_TIMEOUT_MS) {
            c.getPartialObject(item.handle, offset, maxBytes, output) { loaded, total -> onProgress(loaded, total) }
        }
    }

    override suspend fun takePicture(): Long = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        ptpCall { c.initiateCapture() }
    }

    // ── 设备属性（PTP DeviceProp）读写 ──────────────────────────────

    /**
     * 设置设备属性（走事务互斥 + 超时自愈）。
     * @return 相机是否返回 OK；未连接/异常返回 false
     */
    suspend fun setDeviceProperty(propCode: Int, value: Long, valueSize: Int): Boolean =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext false
            runCatching { ptpCall { c.setDeviceProperty(propCode, value, valueSize) } }
                .getOrElse { e ->
                    AppLog.w(TAG, "设置设备属性 0x${propCode.toString(16)} 异常：${e.message}")
                    false
                }
        }

    /**
     * 读取全部设备属性原始字节（0x9209）。
     * @return 原始字节；未连接/异常返回 null
     */
    suspend fun getAllDeviceProperties(): ByteArray? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        runCatching { ptpCall { c.getAllDeviceProperties() } }.getOrNull()
    }
}
