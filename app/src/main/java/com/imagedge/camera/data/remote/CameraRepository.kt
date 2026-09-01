package com.imagedge.camera.data.remote

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.remote.wifi.CameraWifiManager
import com.imagedge.camera.ptp.DevicePropParser
import com.imagedge.camera.ptp.SonyDevicePropCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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

/** 连接结果（通道类型 + 相机型号） */
data class ConnectionResult(
    val channelType: ChannelType,
    val deviceModel: String
)

/** 日志 tag */
private const val TAG = "camera"

@Singleton
class CameraRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiManager: CameraWifiManager,
    private val ptpChannel: PtpChannel,
    private val upnpChannel: UpnpChannel
) {

    private var activeChannel: CameraChannel? = null

    /** 仓库级作用域：承载跨页面存活的延迟任务（如整卡延迟退出） */
    private val repoScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    val isConnected: Boolean get() = activeChannel != null

    val currentChannelType: ChannelType? get() = activeChannel?.channelType

    /** 当前连接的相机型号（未连接返回空串） */
    val deviceModel: String get() = activeChannel?.deviceModel ?: ""

    /**
     * 连接状态流（转发 PTP 通道；UPnP 通道暂无状态跟踪，恒为 DISCONNECTED——
     * UPnP 是历史兼容通道，实际路由始终优先 PTP/IP）
     */
    val connectionState: StateFlow<ChannelConnectionState> get() = ptpChannel.connectionState

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
        AppLog.i(TAG, "连接相机 $targetHost（先 PTP/IP，失败降级 UPnP）")

        wifiManager.bindProcessToWifi()

        // 主通道：PTP/IP
        try {
            ptpChannel.connect(targetHost)
            activeChannel = ptpChannel
            AppLog.i(TAG, "PTP/IP 连接成功，型号=${ptpChannel.deviceModel}")
            return@withContext ConnectionResult(ptpChannel.channelType, ptpChannel.deviceModel)
        } catch (ptpError: Exception) {
            AppLog.w(TAG, "PTP/IP 连接失败（${ptpError.message}），尝试降级 UPnP")
            // 降级通道：UPnP
            try {
                upnpChannel.connect(targetHost)
                activeChannel = upnpChannel
                AppLog.i(TAG, "UPnP 连接成功，型号=${upnpChannel.deviceModel}")
                return@withContext ConnectionResult(upnpChannel.channelType, upnpChannel.deviceModel)
            } catch (upnpError: Exception) {
                activeChannel = null
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

    /** 断开连接 */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        activeChannel?.disconnect()
        activeChannel = null
        wifiManager.unbindProcessNetwork()
    }

    /** 浏览全部媒体（PTP，按当前功能模式：整卡 ContentsTransfer / 选片集 RemoteControl） */
    suspend fun listMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        activeChannel?.listMedia() ?: emptyList()
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
        if (ok) currentFunctionMode = mode
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
     */
    suspend fun takePicture(): Long = withContext(Dispatchers.IO) {
        ptpChannel.takePicture()
    }

    // ── 设备属性（PTP DeviceProp）参数控制 ─────────────────────────────

    /** 设置设备属性（PTP SDIO_CONTROL_DEVICE 0x9207）。@return 相机是否返回 OK */
    suspend fun setDeviceProperty(propCode: Int, value: Long, valueSize: Int): Boolean =
        withContext(Dispatchers.IO) {
            ptpChannel.setDeviceProperty(propCode, value, valueSize)
        }

    /**
     * 设置 ISO（索尼私有 0xD21E，UINT32 原始值：低 24 位 = ISO 值，0x00FFFFFF = Auto）。
     *
     * 改为接收原始值而非显示字符串：可选项来自相机 0x9209 上报的枚举表，
     * 不需要再「字符串 → raw」反推（反推既不支持 Auto，也覆盖不到机型特有档位）。
     */
    suspend fun setIso(raw: Long): Boolean = withContext(Dispatchers.IO) {
        ptpChannel.setDeviceProperty(SonyDevicePropCode.ISO, raw, 4)
    }

    /** 设置光圈（标准 0x5007，UINT16 = f 值 ×100） */
    suspend fun setFNumber(raw: Long): Boolean = withContext(Dispatchers.IO) {
        ptpChannel.setDeviceProperty(SonyDevicePropCode.F_NUMBER, raw, 2)
    }

    /** 设置快门速度（索尼私有 0xD20D，UINT32 高 16 分子 / 低 16 分母） */
    suspend fun setShutterSpeed(raw: Long): Boolean = withContext(Dispatchers.IO) {
        ptpChannel.setDeviceProperty(SonyDevicePropCode.SHUTTER_SPEED, raw, 4)
    }

    /**
     * 读取相机当前参数（ISO/光圈/快门 + 照相模式/白平衡/曝光补偿）。
     * 走 0x9209 一次读全部属性，再按属性码搜索；任一属性缺失不抛异常。
     */
    suspend fun readCameraSettings(): CameraSettings = withContext(Dispatchers.IO) {
        val data = ptpChannel.getAllDeviceProperties()
        if (data == null) return@withContext CameraSettings()
        val props = DevicePropParser.parse(
            data,
            listOf(
                SonyDevicePropCode.ISO,
                SonyDevicePropCode.F_NUMBER,
                SonyDevicePropCode.SHUTTER_SPEED,
                SonyDevicePropCode.EXPOSURE_PROGRAM_MODE,
                SonyDevicePropCode.WHITE_BALANCE,
                SonyDevicePropCode.EXPOSURE_BIAS
            )
        )
        // supported 用于驱动下拉可选项，settable 决定渲染成选择器还是只读文本
        fun supportedOf(code: Int) = props[code]?.supported ?: emptyList()
        fun settableOf(code: Int) = props[code]?.settable == true

        CameraSettings(
            iso = props[SonyDevicePropCode.ISO]?.currentValue?.let { CameraSettings.formatIso(it) },
            isoRaw = props[SonyDevicePropCode.ISO]?.currentValue,
            isoSupported = supportedOf(SonyDevicePropCode.ISO),
            isoSettable = settableOf(SonyDevicePropCode.ISO),

            fNumber = props[SonyDevicePropCode.F_NUMBER]?.currentValue?.let { CameraSettings.formatFNumber(it) },
            fNumberRaw = props[SonyDevicePropCode.F_NUMBER]?.currentValue,
            fNumberSupported = supportedOf(SonyDevicePropCode.F_NUMBER),
            fNumberSettable = settableOf(SonyDevicePropCode.F_NUMBER),

            shutter = props[SonyDevicePropCode.SHUTTER_SPEED]?.currentValue?.let { CameraSettings.formatShutter(it) },
            shutterRaw = props[SonyDevicePropCode.SHUTTER_SPEED]?.currentValue,
            shutterSupported = supportedOf(SonyDevicePropCode.SHUTTER_SPEED),
            shutterSettable = settableOf(SonyDevicePropCode.SHUTTER_SPEED),

            exposureProgramMode = props[SonyDevicePropCode.EXPOSURE_PROGRAM_MODE]?.currentValue,
            exposureProgramModeSupported = supportedOf(SonyDevicePropCode.EXPOSURE_PROGRAM_MODE),
            exposureProgramModeSettable = settableOf(SonyDevicePropCode.EXPOSURE_PROGRAM_MODE),

            whiteBalance = props[SonyDevicePropCode.WHITE_BALANCE]?.currentValue,
            exposureBias = props[SonyDevicePropCode.EXPOSURE_BIAS]?.currentValue
        )
    }

    /** 设置白平衡（0x5005，uint16 枚举值，见 CameraSettings.formatWhiteBalance 值表） */
    suspend fun setWhiteBalance(code: Long): Boolean =
        ptpChannel.setDeviceProperty(SonyDevicePropCode.WHITE_BALANCE, code, 2)

    /** 设置曝光补偿（0x5010，INT16 EV×1000：+0.3EV → 300） */
    suspend fun setExposureBias(raw: Long): Boolean =
        ptpChannel.setDeviceProperty(SonyDevicePropCode.EXPOSURE_BIAS, raw and 0xFFFF, 2)

    /**
     * 设置照相模式（0x500E ExposureProgramMode，官方 APP 同款通道 0x9205）。
     * 值宽度按 0x9209 上报的 dataType（ZV-E10 上报 UINT32，如 P=0x00010002）。
     */
    suspend fun setExposureProgramMode(raw: Long, valueSize: Int = 4): Boolean =
        ptpChannel.setDeviceProperty(SonyDevicePropCode.EXPOSURE_PROGRAM_MODE, raw, valueSize)

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
        val mimeType = inferMimeType(item.filename)

        // 用户在设置页选择了自定义目录（SAF）：写入该目录（默认路径见下方 MediaStore 分支）
        val treeUriStr = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("download_tree_uri", null)
        if (treeUriStr != null) {
            val treeUri = Uri.parse(treeUriStr)
            // SAF 树目录：createDocument 建文件（重名自动追加 " (1)"）
            val dirId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
            val fileUri = DocumentsContract.createDocument(resolver, dirUri, mimeType, item.filename)
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
                    channel.download(item, stream) { loaded, total -> onProgress(loaded, total) }
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/Imagedge"
            )
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
                channel.download(item, stream) { loaded, total -> onProgress(loaded, total) }
            }
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
        val mimeType = inferMimeType(item.filename)
        val resolver = context.contentResolver
        val treeUriStr = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("download_tree_uri", null)
        if (treeUriStr != null) {
            val treeUri = Uri.parse(treeUriStr)
            val dirId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
            val fileUri = DocumentsContract.createDocument(resolver, dirUri, mimeType, item.filename)
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Imagedge")
        }
        val uri = resolver.insert(collection, contentValues) ?: return@withContext null
        commitInto(uri, source) { resolver.delete(uri, null, null) }
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
     * 硬上限 [maxBytes]（默认 256MB，覆盖 24MP RAW 的 ~25MB 与内嵌预览）：
     * ByteArrayOutputStream 会无上限扩容，且 toByteArray() 再复制一份，峰值 = 2× 文件大小。
     * 视频/超大文件必须走 [downloadToFile] 落盘，绝不进堆。
     * 注意 sizeBytes 为 0（相机未上报）或 >2GB（toInt 溢出为负）时一律拒绝，
     * 避免 ByteArray(负数) 抛 NegativeArraySizeException。
     *
     * @throws IllegalArgumentException 大小未知或超过上限时抛出（调用方应改用 [downloadToFile]）
     */
    suspend fun downloadToMemory(
        item: MediaItem,
        maxBytes: Long = 256L * 1024 * 1024
    ): ByteArray = withContext(Dispatchers.IO) {
        val channel = activeChannel ?: throw IllegalStateException("未连接相机")
        require(item.sizeBytes in 1..maxBytes) {
            "文件大小未知或过大（${item.sizeBytes} 字节，上限 $maxBytes），请改用 downloadToFile 落盘"
        }
        val output = ByteArrayOutputStream(item.sizeBytes.toInt())
        channel.download(item, output) { _, _ -> }
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
            channel.download(item, stream) { loaded, total -> onProgress(loaded, total) }
        }
    }

    /** 按扩展名推断 MIME 类型 */
    private fun inferMimeType(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".arw") -> "image/x-sony-arw"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mts") || lower.endsWith(".m2ts") -> "video/mp2t"
            else -> "application/octet-stream"
        }
    }
}
