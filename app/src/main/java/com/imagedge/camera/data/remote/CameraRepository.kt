package com.imagedge.camera.data.remote

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.core.io.BoundedOutputStream
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.model.PropertyWriteDecision
import com.imagedge.camera.data.remote.wifi.CameraWifiManager
import com.imagedge.camera.ptp.DeviceProperty
import com.imagedge.camera.ptp.SonyDevicePropCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相机数据仓库（通道路由）——连接时先 PTP/IP，失败自动降级 UPnP
 *     version: 1.0
 * </pre>
 */

/** 连接结果（通道类型 + 相机身份：型号/固件/传输方式/功能模式） */
data class ConnectionResult(
    val channelType: ChannelType,
    val identity: CameraIdentity
)

/** 一次能力探测的结果：身份 + 当前参数 + 能力快照 */
data class CameraSnapshot(
    val identity: CameraIdentity,
    val settings: CameraSettings,
    val capabilities: CameraCapabilities
)

/** 日志 tag */
private const val TAG = "camera"

/** Defensive ceiling for a single camera object; unknown-size streams use a tighter limit. */
private const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024 * 1024
private const val MAX_UNKNOWN_TRANSFER_BYTES = 8L * 1024 * 1024 * 1024

@Singleton
class CameraRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiManager: CameraWifiManager,
    private val ptpChannel: PtpChannel,
    private val upnpChannel: UpnpChannel
) {

    @Volatile
    private var activeChannel: CameraChannel? = null

    /** 仓库级作用域：承载跨页面存活的延迟任务（如整卡延迟退出） */
    private val repoScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ChannelConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ChannelConnectionState> = _connectionState.asStateFlow()

    init {
        // 仅转发当前被路由选中的通道，避免 PTP 降级到 UPnP 后 UI 仍恒显“已断开”。
        repoScope.launch {
            ptpChannel.connectionState.collect { state ->
                if (activeChannel === ptpChannel) _connectionState.value = state
            }
        }
        repoScope.launch {
            upnpChannel.connectionState.collect { state ->
                if (activeChannel === upnpChannel) _connectionState.value = state
            }
        }
    }

    val isConnected: Boolean get() =
        activeChannel != null && _connectionState.value == ChannelConnectionState.CONNECTED

    val currentChannelType: ChannelType? get() = activeChannel?.channelType

    /** 当前连接的相机型号（未连接返回空串） */
    val deviceModel: String get() = activeChannel?.deviceModel ?: ""

    private val _identity = MutableStateFlow(CameraIdentity.UNKNOWN)

    /** 当前相机身份（型号 + 固件 + 传输方式 + 功能模式），即能力快照的归档键 */
    val identity: StateFlow<CameraIdentity> = _identity.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities.UNKNOWN)

    /**
     * 当前能力快照。
     *
     * 未连接时为 [CameraCapabilities.UNKNOWN]；0x9209 读取失败时属性类能力为
     * UNKNOWN 且 stale=true（**不是** UNSUPPORTED——超时不等于不支持）。
     * 所有写操作都必须先经 [CameraCapabilities.canWrite] 校验，见 [writeProperty]。
     */
    val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    /** 相机内容变化事件（选片推送 / 内容集重建，用于事件驱动立即刷新） */
    val contentEvents: Flow<Unit> get() = ptpChannel.contentEvents

    /** 拍摄完成事件（携带新对象句柄，用于遥控拍摄照片自动拉回） */
    val captureEvents: Flow<Long> get() = ptpChannel.captureEvents

    /** 设备属性变化事件（0xC203/0x4006，相机端改动参数时推送）——参数双向同步 */
    val propEvents: Flow<Int> get() = ptpChannel.propEvents

    // ── 保活暂停管理（功耗标准：后台不得进行非必要的持续网络活动）──
    // 暂停条件 = App 退后台 且 无活跃下载（下载属「用户主动数据传输」例外，需保活维持 PTP 会话）

    @Volatile
    private var appInBackground = false

    @Volatile
    private var downloadActive = false

    private fun updateKeepAlive() {
        ptpChannel.keepAlivePaused = appInBackground && !downloadActive
    }

    /** App 前后台切换（MainActivity onStart/onStop 调用） */
    fun setAppInBackground(background: Boolean) {
        appInBackground = background
        updateKeepAlive()
        AppLog.d(TAG, "App 前后台切换：background=$background，下载活跃=$downloadActive")
    }

    /** 下载队列状态变化（DownloadManager 调用：有任务=true，队列空闲=false） */
    fun setDownloadActive(active: Boolean) {
        downloadActive = active
        updateKeepAlive()
    }

    /**
     * 连接相机（通道路由：先 PTP/IP 15740，失败降级 UPnP 64321）
     * @param host 手动指定相机 IP；null 时自动网关发现
     */
    suspend fun connect(host: String? = null): ConnectionResult = withContext(Dispatchers.IO) {
        disconnect()

        val targetHost = host ?: wifiManager.getCurrentGatewayIp()
            ?: throw IllegalStateException("未找到相机 WiFi 网关，请先连接相机热点")
        requirePrivateIpv4(targetHost)
        AppLog.i(TAG, "连接相机 $targetHost（先 PTP/IP，失败降级 UPnP）")

        wifiManager.bindProcessToWifi()

        // 主通道：PTP/IP
        try {
            ptpChannel.connect(targetHost)
            return@withContext adoptChannel(ptpChannel)
        } catch (ptpError: Exception) {
            AppLog.w(TAG, "PTP/IP 连接失败（${ptpError.message}），尝试降级 UPnP")
            // 降级通道：UPnP
            try {
                upnpChannel.connect(targetHost)
                return@withContext adoptChannel(upnpChannel)
            } catch (upnpError: Exception) {
                activeChannel = null
                _connectionState.value = ChannelConnectionState.DISCONNECTED
                resetCapabilitySnapshot()
                AppLog.e(TAG, "UPnP 连接失败（${upnpError.message}）")
                // P1-13：两条通道都失败时必须解绑进程网络。
                // 否则进程一直绑在已失效的相机 WiFi 上，用户切回家宽/蜂窝后，
                // App 内所有网络请求（含后续扫码配网）都走死网络 —— 表现为
                // 「一次连不上之后怎么都连不上」，只能杀进程。
                runCatching { wifiManager.unbindProcessNetwork() }
                val hint = "提示：相机「智能手机连接」模式走 PTP/IP，「发送到智能手机」模式走 UPnP，请确认相机已进入对应模式并处于等待连接状态"
                throw IllegalStateException(
                    "无法连接相机（PTP: ${ptpError.message}；UPnP: ${upnpError.message}）。$hint"
                )
            }
        }
    }

    /**
     * 采用某通道为当前活跃通道，并按通道**自我声明**的能力重置身份与能力快照。
     *
     * 这里刻意不做 0x9209 往返：连接路径要尽快返回，参数能力由 [refreshCapabilities]
     * 在进入遥控页时探测。重置后 PTP 通道是「未探测（UNKNOWN + stale）」，UPnP 通道是
     * 「结构性不支持」——两者都不必读描述符即可确定。
     */
    private fun adoptChannel(channel: CameraChannel): ConnectionResult {
        activeChannel = channel
        _connectionState.value = channel.connectionState.value
        resetCapabilitySnapshot()
        AppLog.i(
            TAG,
            "${channel.channelType} 连接成功，型号=${channel.deviceModel} 固件=${channel.deviceFirmware}"
        )
        return ConnectionResult(channel.channelType, _identity.value)
    }

    /**
     * 依据当前活跃通道重建身份与能力快照（不发起 0x9209 往返）。
     *
     * 调用时机：连接成功、断开、功能模式切换。三者都意味着上一轮快照的归档键已失效——
     * 换模式/换通道后继续沿用旧能力，正是「把某台相机某次的经验泛化」那类 bug 的来源。
     */
    private fun resetCapabilitySnapshot() {
        val channel = activeChannel
        if (channel == null) {
            _identity.value = CameraIdentity.UNKNOWN
            _capabilities.value = CameraCapabilities.UNKNOWN
            return
        }
        val identity = CameraIdentity(
            model = channel.deviceModel,
            firmware = channel.deviceFirmware,
            transport = channel.channelType.toTransport(),
            mode = if (channel.channelType == ChannelType.PTP_IP) currentFunctionMode
            else CameraIdentity.MODE_UNKNOWN
        )
        _identity.value = identity
        _capabilities.value = CameraCapabilities.fromDescriptors(
            identity = identity,
            props = null,
            supportsCapture = channel.supportsCapture
        )
    }

    /** 断开连接 */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        activeChannel?.disconnect()
        activeChannel = null
        _connectionState.value = ChannelConnectionState.DISCONNECTED
        resetCapabilitySnapshot()
        wifiManager.unbindProcessNetwork()
    }

    /** 浏览全部媒体（PTP，按当前功能模式：整卡 ContentsTransfer / 选片集 RemoteControl） */
    suspend fun listMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        activeChannel?.listMedia() ?: emptyList()
    }

    /**
     * 增量浏览全部媒体：分批回调，边扫描边返回。
     *
     * 整卡模式下一性枚举上千对象会长时间占用 PTP 通道、且首屏空白数分钟；
     * 相册页据此边收到边渲染。回调在 IO 线程执行，更新 StateFlow 是线程安全的。
     *
     * @return 返回的总条数
     */
    suspend fun listMediaIncremental(onBatch: suspend (List<MediaItem>) -> Unit): Int {
        return activeChannel?.listMediaIncremental(onBatch) ?: 0
    }

    /**
     * 切换功能模式并重连 PTP：0=RemoteControl（选片集），1=ContentsTransfer（整卡）。
     * @return 是否成功（未连接/同模式返回 false）
     */
    /** 当前相机功能模式（0=选片集/遥控 1=整卡传输），用于跨页面模式同步与延迟退出 */
    @Volatile
    var currentFunctionMode: Int = 0
        private set

    /** 延迟退出整卡的挂起任务（新的显式切换会取消它） */
    private var pendingFullCardExit: kotlinx.coroutines.Job? = null

    /** 是否已登记「退出整卡」请求（等待下载空闲期间为真） */
    @Volatile
    var fullCardExitRequested: Boolean = false
        private set

    /** 退出整卡前轮询等待下载空闲的节拍 */
    private val FULL_CARD_EXIT_POLL_MS = 2_000L

    /** 等待下载队列空闲的上限（超时兜底：避免用户长时间挂起导致整卡模式迟迟不退） */
    private val FULL_CARD_EXIT_WAIT_TIMEOUT_MS = 10 * 60 * 1000L

    suspend fun switchFunctionMode(mode: Int): Boolean {
        pendingFullCardExit?.cancel()
        pendingFullCardExit = null
        // 显式切换（含重新进入整卡）即撤销待处理的退出请求
        fullCardExitRequested = false
        val ok = withContext(Dispatchers.IO) {
            ptpChannel.switchFunctionMode(mode)
        }
        if (ok) {
            currentFunctionMode = mode
            // 功能模式是能力归档键的一部分：模式一变，上一轮快照立即作废，必须重新探测
            resetCapabilitySnapshot()
        }
        return ok
    }

    /**
     * 延迟退出整卡读取（相册页在整卡模式下离开时调用）。
     * 给相机 5 秒完成当前操作后再切回选片集模式；期间用户重新进入相册
     * 或显式切换模式会取消本任务（switchFunctionMode 入口统一取消）。
     *
     * **必须等下载队列空闲后再切换**（真机 P0）：
     * [switchFunctionMode] 会断开并重连 PTP 会话，会话重建后原内容集的对象句柄
     * 全部失效——相机对分块下载回 `0x2009（无效对象句柄）`，正在进行的下载瞬间
     * 失败且无法续传（用户感知：「刚开始下载，相机就退出整卡模式，任务全部失败」）。
     * 与 [setDownloadActive] 的活跃状态联动：队列一空闲就继续退出流程，
     * 无需用户回到整卡页。
     */
    fun exitFullCardDelayed(delayMs: Long = 5_000) {
        if (currentFunctionMode != 1) return
        fullCardExitRequested = true
        pendingFullCardExit?.cancel()
        pendingFullCardExit = repoScope.launch {
            kotlinx.coroutines.delay(delayMs)
            // 下载进行中：等待队列空闲（PTP 会话切换会让所有进行中的对象句柄失效）
            var waited = 0L
            while (downloadActive && waited < FULL_CARD_EXIT_WAIT_TIMEOUT_MS) {
                if (waited == 0L) {
                    AppLog.i(TAG, "整卡退出已挂起：等待下载队列空闲后再切回选片集")
                }
                kotlinx.coroutines.delay(FULL_CARD_EXIT_POLL_MS)
                waited += FULL_CARD_EXIT_POLL_MS
            }
            if (waited >= FULL_CARD_EXIT_WAIT_TIMEOUT_MS) {
                AppLog.w(TAG, "等待下载空闲超时（${FULL_CARD_EXIT_WAIT_TIMEOUT_MS}ms），强制退出整卡模式")
            } else if (waited > 0) {
                AppLog.i(TAG, "下载队列已空闲（等待 ${waited}ms），继续退出整卡模式")
            }
            if (currentFunctionMode == 1) {
                // 先清引用：否则下方 switchFunctionMode 会 cancel 掉当前任务自己，
                // 导致 withContext 在挂起点被取消、切换失败（整卡返回未切回的根因）
                pendingFullCardExit = null
                runCatching { switchFunctionMode(0) }
                    .onSuccess { AppLog.i("album", "整卡模式已延迟退出（切回选片集）") }
                    .onFailure { AppLog.w("album", "延迟退出整卡失败：${it.message}") }
            }
            fullCardExitRequested = false
        }
    }

    /** 进入 PTP 静默期：拍照/录像后暂停保活与扫描，让相机专心写卡 */
    fun silencePtp(durationMs: Long = 6000) {
        ptpChannel.silence(durationMs)
    }

    /** 获取缩略图 */
    suspend fun getThumbnail(item: MediaItem): ByteArray? = withContext(Dispatchers.IO) {
        activeChannel?.getThumbnail(item)
    }

    /**
     * 遥控拍摄（PTP InitiateCapture，「电脑遥控」模式实测可用）。
     * 拍摄的照片是否自动进入待传输内容集由相机固件决定，通常需相机端选片后到相册下载。
     *
     * 通道未声明遥控拍摄能力时直接拒绝，不发命令——UPnP 通道下 [PtpChannel.takePicture]
     * 注定失败，让调用方靠捕获异常来判断可用性既慢又容易漏。
     */
    suspend fun takePicture(): Long = withContext(Dispatchers.IO) {
        if (!_capabilities.value.canWrite(CameraCapability.CAPTURE)) {
            val detail = _capabilities.value.detail(CameraCapability.CAPTURE)
            throw IllegalStateException("当前通道不支持遥控拍摄（${detail.state}：${detail.note}）")
        }
        ptpChannel.takePicture()
    }

    // ── 设备属性（PTP DeviceProp）参数控制 ─────────────────────────────

    /**
     * 写设备属性（所有参数下发的**唯一出口**）。
     *
     * 决策与下发分离（见 [CameraCapabilities.decideWrite]）：只有拿到 Send 决策才触达通道，
     * UNKNOWN（未探测/读取超时）、UNSUPPORTED、READ_ONLY 一律 Reject 并只记日志。
     *
     * @return 相机是否返回 OK；能力不足或通道不可用时返回 false 且**不发命令**
     */
    private suspend fun writeProperty(capability: CameraCapability, raw: Long): Boolean =
        withContext(Dispatchers.IO) {
            when (val decision = _capabilities.value.decideWrite(capability, raw)) {
                is PropertyWriteDecision.Reject -> {
                    AppLog.w(
                        TAG,
                        "拒绝下发 ${decision.capability.name}：状态=${decision.state}（${decision.reason}）"
                    )
                    false
                }

                is PropertyWriteDecision.Send -> ptpChannel.setDeviceProperty(
                    decision.propCode,
                    decision.value,
                    decision.valueSize
                )
            }
        }

    /**
     * 设置 ISO（索尼私有 0xD21E，UINT32 原始值：低 24 位 = ISO 值，0x00FFFFFF = Auto）。
     *
     * 接收原始值而非显示字符串：可选项来自相机 0x9209 上报的枚举表，
     * 不需要再「字符串 → raw」反推（反推既不支持 Auto，也覆盖不到机型特有档位）。
     */
    suspend fun setIso(raw: Long): Boolean = writeProperty(CameraCapability.ISO, raw)

    /** 设置光圈（标准 0x5007，UINT16 = f 值 ×100） */
    suspend fun setFNumber(raw: Long): Boolean = writeProperty(CameraCapability.F_NUMBER, raw)

    /** 设置快门速度（索尼私有 0xD20D，UINT32 高 16 分子 / 低 16 分母） */
    suspend fun setShutterSpeed(raw: Long): Boolean = writeProperty(CameraCapability.SHUTTER_SPEED, raw)

    /** 设置白平衡（0x5005，枚举值见 CameraSettings.formatWhiteBalance 值表） */
    suspend fun setWhiteBalance(code: Long): Boolean = writeProperty(CameraCapability.WHITE_BALANCE, code)

    /** 设置曝光补偿（0x5010，INT16 EV×1000：+0.3EV → 300，-0.3EV → -300） */
    suspend fun setExposureBias(raw: Long): Boolean = writeProperty(CameraCapability.EXPOSURE_BIAS, raw)

    /** 设置照相模式（0x500E ExposureProgramMode，官方 APP 同款通道 0x9205） */
    suspend fun setExposureProgramMode(raw: Long): Boolean =
        writeProperty(CameraCapability.EXPOSURE_PROGRAM_MODE, raw)

    /**
     * 探测并刷新「身份 + 当前参数 + 能力快照」（一次 0x9209 往返同时得到后两者）。
     *
     * 读取失败（超时/断线/未连接）时能力落到 UNKNOWN 且 stale=true：**绝不记成不支持**，
     * 也不保留上一轮的「可写」态（那会让界面继续放行注定失败的命令）。下一次成功读取
     * 即恢复真实状态，因此失败不是永久性的。
     */
    suspend fun refreshCapabilities(): CameraSnapshot = withContext(Dispatchers.IO) {
        val channel = activeChannel
        if (channel == null) {
            resetCapabilitySnapshot()
            return@withContext CameraSnapshot(CameraIdentity.UNKNOWN, CameraSettings(), CameraCapabilities.UNKNOWN)
        }
        // 身份可能因保活自愈重连而变化（型号/固件重新读取），先同步再探测
        resetCapabilitySnapshot()
        val identity = _identity.value

        val props = if (identity.transport == CameraTransport.PTP_IP) {
            ptpChannel.getAllDeviceProperties()?.let { CameraCapabilities.parseDescriptors(it) }
        } else {
            null
        }
        val capabilities = CameraCapabilities.fromDescriptors(
            identity = identity,
            props = props,
            supportsCapture = channel.supportsCapture
        )
        _capabilities.value = capabilities
        logCapabilitySnapshot(capabilities)
        CameraSnapshot(identity, settingsFrom(props), capabilities)
    }

    /** 能力快照落日志：这是兼容矩阵的原始证据，也是排查「为什么这项被禁用」的唯一线索 */
    private fun logCapabilitySnapshot(capabilities: CameraCapabilities) {
        val identity = capabilities.identity
        AppLog.i(
            TAG,
            "能力快照 ${identity.snapshotKey}（descriptorRead=${capabilities.descriptorRead} stale=${capabilities.stale}）"
        )
        CameraCapability.entries.forEach { capability ->
            val detail = capabilities.detail(capability)
            AppLog.i(
                TAG,
                "  ${capability.name} = ${detail.state}（证据=${detail.evidence}，${detail.note}）"
            )
        }
    }

    /** 由 0x9209 解析结果构建当前参数；[props] 为 null（读取失败）时全部为未知 */
    private fun settingsFrom(props: Map<Int, DeviceProperty>?): CameraSettings {
        if (props == null) return CameraSettings()
        fun valueOf(code: Int) = props[code]?.currentValue
        // 曝光补偿是有符号 INT16：必须按 dataType 扩展，否则 -0.3EV 会被读成 +65.2EV
        val bias = props[SonyDevicePropCode.EXPOSURE_BIAS]
        return CameraSettings(
            isoRaw = valueOf(SonyDevicePropCode.ISO),
            fNumberRaw = valueOf(SonyDevicePropCode.F_NUMBER),
            shutterRaw = valueOf(SonyDevicePropCode.SHUTTER_SPEED),
            exposureProgramMode = valueOf(SonyDevicePropCode.EXPOSURE_PROGRAM_MODE),
            whiteBalance = valueOf(SonyDevicePropCode.WHITE_BALANCE),
            exposureBias = bias?.let { it.signed(it.currentValue) }
        )
    }

    /**
     * 流式下载到系统相册（DCIM/Imagedge）
     * @return 保存后的 MediaStore Uri
     */
    suspend fun downloadToGallery(
        item: MediaItem,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Uri? = withContext(Dispatchers.IO) {
        val channel = activeChannel ?: return@withContext null

        val resolver = context.contentResolver
        val displayName = sanitizeDisplayName(item.filename)
        val mimeType = inferMimeType(displayName)

        // 用户在设置页选择了自定义目录（SAF）：写入该目录（默认路径见下方 MediaStore 分支）
        val treeUriStr = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("download_tree_uri", null)
        if (treeUriStr != null) {
            val treeUri = treeUriStr.toUri()
            // SAF 树目录：createDocument 建文件（重名自动追加 " (1)"）
            val dirId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
            val fileUri = DocumentsContract.createDocument(resolver, dirUri, mimeType, displayName)
                ?: throw IllegalStateException("无法在所选目录创建文件（权限或路径无效）")
            val output: OutputStream = resolver.openOutputStream(fileUri)
                ?: run {
                    // 建好了文档却打不开流：必须先删掉，否则留下一个 0 字节废文件
                    runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
                    throw IllegalStateException("无法打开输出流")
                }
            // 下载失败（超时 / 相机断链 / 被保活 forceClose）时必须删除半成品：
            // 否则相册里会留下一堆打不开的 0 字节文件，且永不清理、越积越多（P1-5）
            try {
                output.use { stream ->
                    downloadVerified(channel, item, stream, onProgress)
                }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
                    .onFailure { AppLog.w("camera", "删除半成品文件失败：$fileUri：${it.message}") }
                throw e
            }
            return@withContext fileUri
        }
        val collection = if (mimeType.startsWith("video")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Imagedge"
            )
            // 下载中标记为 pending：否则大文件（几十 MB 视频）下载的几分钟里，
            // 相册/其他应用能看到一个打不开的残缺文件（P0）
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // 拍摄时间决定系统相册的排序。相机枚举时已经拿到 captureDate，
            // 不写它的话下载回来的照片会按「下载时间」排序，用户看到顺序全乱（P0）
            item.captureDate?.time?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }

        val uri = resolver.insert(collection, contentValues) ?: return@withContext null
        val output: OutputStream = resolver.openOutputStream(uri)
            ?: run {
                runCatching { resolver.delete(uri, null, null) }
                throw IllegalStateException("无法打开输出流")
            }

        // 下载失败时删除 MediaStore 条目，避免相册里留下 0 字节半成品（P1-5）
        try {
            output.use { stream ->
                downloadVerified(channel, item, stream, onProgress)
            }
            // 写完才发布条目（IS_PENDING=0），此后图库才可见完整文件
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }.onFailure { AppLog.w("camera", "清除 IS_PENDING 失败（文件已完整，仅标记残留）：$uri：${it.message}") }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { AppLog.w("camera", "删除半成品文件失败：$uri：${it.message}") }
            throw e
        }
        uri
    }

    /**
     * 分块下载（断点续传）：从 [offset] 起最多 [maxBytes] 字节写入 [output]。
     * 转发到当前通道；UPnP 通道不支持时抛 UnsupportedOperationException。
     */
    suspend fun downloadRange(
        item: MediaItem,
        output: OutputStream,
        offset: Long,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        activeChannel?.downloadRange(item, output, offset, maxBytes, onProgress)
            ?: throw IllegalStateException("未连接相机")
    }

    /**
     * 把已落盘到本地的 [source] 文件提交到系统相册（DCIM/Imagedge 或用户 SAF 目录）。
     * 供断点续传使用：先分块写入临时文件，完成后再一次性搬入相册目的地
     * （MediaStore/SAF 的 OutputStream 不支持随机写/seek，无法在下载中途续写）。
     * @return 提交后的 MediaStore/SAF Uri
     */
    suspend fun commitToGallery(item: MediaItem, source: File): Uri? = withContext(Dispatchers.IO) {
        val channel = activeChannel ?: return@withContext null
        val displayName = sanitizeDisplayName(item.filename)
        val mimeType = inferMimeType(displayName)
        val resolver = context.contentResolver
        val treeUriStr = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("download_tree_uri", null)
        if (treeUriStr != null) {
            val treeUri = treeUriStr.toUri()
            val dirId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
            val fileUri = DocumentsContract.createDocument(resolver, dirUri, mimeType, displayName)
                ?: throw IllegalStateException("无法在所选目录创建文件（权限或路径无效）")
            // 注意：不能用 `?.use {}` 静默跳过——打不开流时必须删掉刚建的文档，
            // 否则会返回一个 0 字节废文件，调用方还以为提交成功了（P1-5）
            commitInto(fileUri, source) { DocumentsContract.deleteDocument(resolver, fileUri) }
            return@withContext fileUri
        }
        val collection = if (mimeType.startsWith("video")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Imagedge")
            // 与 downloadToGallery 同样：先 pending，搬完再发布；并写入拍摄时间（P0）
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            item.captureDate?.time?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(collection, contentValues) ?: return@withContext null
        commitInto(uri, source) { resolver.delete(uri, null, null) }
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        }
        uri
    }

    /**
     * 把 [source] 文件内容写入 [target]（MediaStore/SAF 已建好的目标 Uri）。
     * 任一步失败都先删掉目标再抛异常，杜绝残留 0 字节半成品（P1-5）。
     *
     * @param deleteTarget 删除目标的回调（MediaStore 用 resolver.delete，SAF 用 deleteDocument）
     */
    private fun commitInto(target: Uri, source: File, deleteTarget: () -> Unit) {
        val resolver = context.contentResolver
        val out = resolver.openOutputStream(target)
            ?: run {
                runCatching(deleteTarget)
                throw IllegalStateException("无法打开输出流：$target")
            }
        try {
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        } catch (e: Exception) {
            runCatching(deleteTarget)
                .onFailure { AppLog.w("camera", "删除半成品文件失败：$target：${it.message}") }
            throw e
        }
    }

    /**
     * 下载媒体到内存（大图查看器用：JPEG 全量 / RAW 提取内嵌预览）
     * 注意大对象内存开销，调用方负责缓存淘汰。
     *
     * 硬上限 [maxBytes]（默认 96MB，覆盖常见 RAW 与内嵌预览）：
     * 上限由 [BoundedOutputStream] 对实际收到的字节执行，不能只信任相机上报的 size。
     * ByteArrayOutputStream + toByteArray() 仍会产生复制，因此这里不接受视频或超大原图。
     * 视频/超大文件必须走 [downloadToFile] 落盘，绝不进堆。
     * 注意 sizeBytes 为 0（相机未上报）或 >2GB（toInt 溢出为负）时一律拒绝，
     * 避免 ByteArray(负数) 抛 NegativeArraySizeException。
     *
     * @throws IllegalArgumentException 大小未知或超过上限时抛出（调用方应改用 [downloadToFile]）
     */
    suspend fun downloadToMemory(
        item: MediaItem,
        maxBytes: Long = 96L * 1024 * 1024
    ): ByteArray = withContext(Dispatchers.IO) {
        val channel = activeChannel ?: throw IllegalStateException("未连接相机")
        require(item.sizeBytes in 1..maxBytes) {
            "文件大小未知或过大（${item.sizeBytes} 字节，上限 $maxBytes），请改用 downloadToFile 落盘"
        }
        val output = ByteArrayOutputStream(minOf(item.sizeBytes, 1024L * 1024L).toInt())
        val bounded = BoundedOutputStream(output, minOf(item.sizeBytes, maxBytes))
        channel.download(item, bounded) { _, _ -> }
        if (bounded.bytesWritten != item.sizeBytes) {
            throw IOException(
                "相机返回长度与对象信息不一致：实际 ${bounded.bytesWritten}，声明 ${item.sizeBytes}"
            )
        }
        output.toByteArray()
    }

    /**
     * 下载媒体到本地文件（视频预览用：流式下载到缓存目录，大文件不 OOM）。
     */
    suspend fun downloadToFile(
        item: MediaItem,
        file: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val channel = activeChannel ?: throw IllegalStateException("未连接相机")
        FileOutputStream(file).use { stream ->
            downloadVerified(channel, item, stream, onProgress)
        }
    }

    /** Enforce byte limits against the stream actually received, not camera-controlled metadata. */
    private suspend fun downloadVerified(
        channel: CameraChannel,
        item: MediaItem,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit,
    ) {
        require(item.sizeBytes <= MAX_TRANSFER_BYTES) {
            "文件过大（${item.sizeBytes} 字节，上限 $MAX_TRANSFER_BYTES）"
        }
        val expected = item.sizeBytes.takeIf { it > 0 }
        val bounded = BoundedOutputStream(output, expected ?: MAX_UNKNOWN_TRANSFER_BYTES)
        channel.download(item, bounded) { loaded, total -> onProgress(loaded, total) }
        if (expected != null && bounded.bytesWritten != expected) {
            throw IOException(
                "相机返回长度与对象信息不一致：实际 ${bounded.bytesWritten}，声明 $expected"
            )
        }
    }

    /** 按扩展名推断 MIME 类型 */
    private fun inferMimeType(filename: String): String {
        val lower = filename.lowercase(Locale.US)
        return when {
            lower.endsWith(".arw") -> "image/x-sony-arw"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            // HEIF 系列（索尼新机型、手机出片）：缺这三条会被写成 octet-stream，
            // 图库不索引或显示为未知文件（P0）
            lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".hif") -> "image/heif"
            lower.endsWith(".tif") || lower.endsWith(".tiff") -> "image/tiff"
            lower.endsWith(".dng") -> "image/x-adobe-dng"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".m4v") -> "video/x-m4v"
            lower.endsWith(".mts") || lower.endsWith(".m2ts") -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }

    /** 相机/UPnP 元数据属于不可信输入，不将路径分隔符、控制字符或超长名称交给 MediaStore/SAF。 */
    private fun sanitizeDisplayName(raw: String): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .map { character ->
                when {
                    character.code < 0x20 || character.code == 0x7F -> '_'
                    character in charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') -> '_'
                    else -> character
                }
            }
            .joinToString("")
            .trim()
            .trim('.')
            .take(180)
        return cleaned.ifBlank { "IMAGEDGE_MEDIA" }
    }

    /** Cleartext camera transports are deliberately restricted to local IPv4 targets. */
    private fun requirePrivateIpv4(host: String) {
        val parts = host.split('.')
        val octets = parts.mapNotNull { it.toIntOrNull() }
        val wellFormed = parts.size == 4 && octets.size == 4 && octets.all { it in 0..255 }
        val local = wellFormed && when (octets[0]) {
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            169 -> octets[1] == 254
            else -> false
        }
        require(local) { "相机地址必须是私有或链路本地 IPv4 地址" }
    }
}
