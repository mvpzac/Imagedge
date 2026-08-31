package com.imagedge.camera.ptp

import com.imagedge.camera.core.common.AppLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP/IP 客户端（ISO 15740，端口 15740）
 *             TCP 双连接（命令 + 事件），会话管理、对象浏览、流式下载
 *     version: 1.0
 * </pre>
 */

/** 默认 PTP/IP 端口 */
const val PTP_IP_PORT = 15740

/** 日志 tag */
private const val TAG = "ptp"

/** 握手阶段读超时：相机 30s 无有效序列会主动断开，配对确认也需留时间 */
private const val HANDSHAKE_TIMEOUT_MS = 35_000

/** 事务阶段读超时：避免相机静默时无限挂死 */
private const val TRANSACTION_TIMEOUT_MS = 15_000

/** 事件流读取超时（事件轮询间隔，短于事务超时） */
private const val EVENT_POLL_TIMEOUT_MS = 3_000

/**
 * GetObjectHandles 返回数量的合法上限（P2-2）。
 * 数量字段是 UINT32，畸形值（如 0xFFFFFFFF）若不校验会按值分配列表 → OOM。
 * 单张 SD 卡的对象数远达不到 10 万，超出即判定为流错位/协议异常。
 */
private const val MAX_OBJECT_HANDLES = 100_000

/**
 * 走内存缓冲的事务数据上限（P2-3）。
 * 内存路径只承接小对象（缩略图/属性表/对象信息，通常 < 1MB）；
 * 大文件必须走流式输出（GetObject/GetPartialObject 传入 OutputStream）。
 * 超限说明相机回了异常数据或流已错位，继续缓冲只会 OOM。
 */
private const val MAX_IN_MEMORY_BYTES = 64 * 1024 * 1024

/** 数据阶段接收回调 */
fun interface DataLoadListener {
    fun onDataLoaded(loadedBytes: Long, totalBytes: Long)
}

/**
 * PTP/IP 客户端
 *
 * 用法：
 * ```
 * val client = PtpIpClient("192.168.122.1")
 * client.connect()
 * client.openSession()
 * val model = client.deviceInfo.model
 * val handles = client.getObjectHandles(storageId)
 * client.getObject(handle, outputStream) { loaded, total -> ... }
 * client.close()
 * ```
 */
class PtpIpClient(
    private val host: String,
    private val port: Int = PTP_IP_PORT,
    private val friendlyName: String = "Imagedge",
    private val connectTimeoutMs: Int = 5000
) {

    /** 任意 16 字节 GUID（固定前缀 + 随机后缀，避免全零/半零被部分固件拒绝） */
    private val guid: ByteArray = ByteArray(16).also {
        it[0] = 0x49; it[1] = 0x6D; it[2] = 0x61; it[3] = 0x67  // "Imag"
        it[4] = 0x65; it[5] = 0x64; it[6] = 0x67; it[7] = 0x65  // "edge"
        // 注意：copyOfRange 会返回新数组，必须手动拷回原数组
        val suffix = ByteArray(8)
        java.security.SecureRandom().nextBytes(suffix)
        System.arraycopy(suffix, 0, it, 8, 8)
    }

    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandIn: BufferedInputStream? = null
    private var commandOut: BufferedOutputStream? = null
    private var eventIn: BufferedInputStream? = null
    private var eventOut: BufferedOutputStream? = null

    private var connectionNumber: Long = 0
    private var sessionId: Long = 0
    private var opened = false

    val isConnected: Boolean
        get() = commandSocket?.isConnected == true

    // ── 连接与初始化 ─────────────────────────────────────────────────

    /**
     * 建立连接并完成初始化握手（alpha-fairy 顺序，对 ZV-E10 验证有效）
     *
     * 顺序：两条 TCP 连接**在握手前全部建立** → InitCommandRequest/Ack（命令流）
     * → InitEventRequest/Ack（**事件流**）。
     * 此前 bug：InitEventAck 到达事件 socket 却从命令 socket 读，相机等不到
     * 事件握手确认，30s 后断开全部连接。
     *
     * @param useEventConnection 是否建立事件连接（默认开启；失败自动降级单连接）
     */
    @Synchronized
    fun connect(useEventConnection: Boolean = true) {
        // 先回收上一次可能残留的 socket：不握手直接关，避免旧会话拖慢重连
        forceClose()
        try {
            connectHandshake(useEventConnection)
        } catch (t: Throwable) {
            // 握手任一环节失败（相机未进遥控模式 / 用户未在相机端点确认 / 相机 30s 超时断链）
            // 都必须回收已创建的 socket。原先这里直接抛出，commandSocket 与 eventSocket
            // 全部泄漏——这些都是高频失败场景，反复重试会耗尽进程 fd 上限（通常 1024），
            // 之后 App 内所有 socket / 文件 / 数据库操作都会失败。
            AppLog.w(TAG, "连接失败，回收已建立的 socket：${t::class.simpleName}: ${t.message}")
            forceClose()
            throw t
        }
    }

    /**
     * 握手实现（失败时的资源回收由 [connect] 统一负责）。
     * 非 @Synchronized：只会被 [connect] 调用，锁在 [connect] 上。
     */
    private fun connectHandshake(useEventConnection: Boolean) {
        AppLog.i(TAG, "连接 PTP/IP $host:$port（GUID=${guid.joinToString("") { "%02X".format(it) }}，事件连接=$useEventConnection）")

        // ① 命令 socket
        val cmd = Socket()
        cmd.connect(InetSocketAddress(host, port), connectTimeoutMs)
        cmd.tcpNoDelay = true
        cmd.soTimeout = HANDSHAKE_TIMEOUT_MS
        commandSocket = cmd
        commandIn = BufferedInputStream(cmd.getInputStream())
        commandOut = BufferedOutputStream(cmd.getOutputStream())
        AppLog.d(TAG, "命令 socket 已建立")

        // ② 事件 socket（握手前建立——alpha-fairy 顺序）
        if (useEventConnection) {
            runCatching {
                val evt = Socket()
                evt.connect(InetSocketAddress(host, port), connectTimeoutMs)
                evt.tcpNoDelay = true
                evt.soTimeout = HANDSHAKE_TIMEOUT_MS
                eventSocket = evt
                eventIn = BufferedInputStream(evt.getInputStream())
                eventOut = BufferedOutputStream(evt.getOutputStream())
                AppLog.d(TAG, "事件 socket 已建立")
            }.onFailure { e ->
                AppLog.w(TAG, "事件 socket 建立失败（降级单连接模式）：${e.message}")
                runCatching { eventSocket?.close() }
                eventSocket = null
                eventIn = null
                eventOut = null
            }
        }

        // ③ InitCommandRequest → InitCommandAck（命令流）
        try {
            sendPacket(InitCommandRequest(guid, friendlyName, 1, 0))
            AppLog.d(TAG, "已发送 InitCommandRequest")
            when (val initAck = readPacket()) {
                is InitCommandAck -> {
                    connectionNumber = initAck.connectionNumber
                    AppLog.i(TAG, "命令连接建立，connectionNumber=$connectionNumber")
                }
                is InitFail -> {
                    AppLog.e(TAG, "相机拒绝握手：InitFail reason=${initAck.reason}")
                    throw PtpResponseException(initAck.reason.toInt(), "相机拒绝握手（reason=${initAck.reason}）")
                }
                else -> {
                    AppLog.e(TAG, "握手收到非预期包：${initAck::class.simpleName}")
                    throw PtpMalformedPacketException("期望 InitCommandAck，收到 ${initAck::class.simpleName}")
                }
            }
        } catch (io: java.io.IOException) {
            AppLog.e(TAG, "命令握手 IO 异常：${io::class.simpleName}: ${io.message}")
            throw PtpIoException("命令握手失败（相机可能未开启 PC 遥控、正在等待屏幕确认或已超时）：${io.message}", io)
        }

        // ④ InitEventRequest → InitEventAck（事件流！此前误读命令流导致 30s 超时）
        if (eventOut != null && eventIn != null) {
            try {
                sendEventPacket(InitEventRequest(connectionNumber))
                AppLog.d(TAG, "已发送 InitEventRequest（事件流）")
                when (val evtAck = readEventPacket()) {
                    is InitEventAck -> AppLog.i(TAG, "事件连接建立，双连接握手完成")
                    else -> throw PtpMalformedPacketException("期望 InitEventAck，收到 ${evtAck::class.simpleName}")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "事件握手失败（非致命，仅命令连接继续）：${e::class.simpleName}: ${e.message}")
                runCatching { eventSocket?.close() }
                eventSocket = null
                eventIn = null
                eventOut = null
            }
        } else {
            AppLog.i(TAG, "跳过事件连接（单连接模式），握手完成")
        }

        // ⑤ 握手完成后收紧读超时，事务阶段不再长时间等待
        runCatching { cmd.soTimeout = TRANSACTION_TIMEOUT_MS }
    }

    /** 断开连接 */
    @Synchronized
    fun disconnect() {
        runCatching { if (opened) closeSession() }
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        commandSocket = null
        eventSocket = null
        commandIn = null
        commandOut = null
        eventIn = null
        eventOut = null
        opened = false
    }

    /**
     * 强制关闭底层 socket（不握手、不发 CloseSession）。
     * 事务超时自愈用：让阻塞在 read 上的线程立刻收到 SocketException 而解除，
     * 避免相机在内容库重建（如第二次发送）期间不响应导致整个通道永久挂死。
     */
    @Synchronized
    fun forceClose() {
        AppLog.w(TAG, "强制关闭 PTP 底层连接（事务超时自愈）")
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        commandSocket = null
        eventSocket = null
        commandIn = null
        commandOut = null
        eventIn = null
        eventOut = null
        opened = false
    }

    // ── 会话管理 ─────────────────────────────────────────────────────

    /** 打开会话 */
    fun openSession(): Long {
        val response = executeTransaction(PtpOperationCode.OPEN_SESSION, longArrayOf(1))
        checkResponse(response)
        sessionId = response.parameters.firstOrNull() ?: 1
        opened = true
        AppLog.i(TAG, "会话已打开，sessionId=$sessionId")
        return sessionId
    }

    /**
     * 索尼 SDIO_OpenSession (0x9210)：以指定「功能模式」打开会话（替代标准 OpenSession）。
     * 参考 Sony-ZV-E10-RX / CokeeZVE：
     *   functionMode 0 = RemoteControl（遥控，选片集）；1 = ContentsTransfer（内容传输，整卡）
     * 整卡读取必须切到 ContentsTransfer(1)。注意：与标准 OpenSession(0x1002) 二选一，
     * 二者同开会返回 0x201e。
     */
    fun sonyOpenSession(functionMode: Int): Long {
        val response = executeTransaction(SonySdioOperationCode.SDIO_OPEN_SESSION, longArrayOf(1, functionMode.toLong()))
        checkResponse(response)
        sessionId = 1
        opened = true
        AppLog.i(TAG, "SDIO_OpenSession(0x9210) 完成，functionMode=$functionMode，sessionId=$sessionId")
        return sessionId
    }

    /** 关闭会话 */
    fun closeSession() {
        if (!opened) return
        val response = executeTransaction(PtpOperationCode.CLOSE_SESSION)
        checkResponse(response)
        opened = false
    }

    // ── 设备信息 ─────────────────────────────────────────────────────

    /**
     * 索尼（ZV-E10 等 α 系列）初始化序列，与 alpha-fairy init_table 逐条对应：
     *
     * GetDeviceInfo → GetStorageIDs → SDIOConnect{1,0,0} → SDIOConnect{2,0,0}
     * → SDIOGetExtDeviceInfo{0x12C,0,0} → SDIOConnect{3,0,0} → SDIOGetExtDeviceInfo{0x12C,0,0}
     *
     * 「电脑遥控」模式下 15740 跑 Imaging Edge 私有协议：标准 OpenSession 之后
     * 相机在等这条私有序列，30s 内收不到会断开全部连接。
     * 调试策略：单步响应码异常仅记录并继续（便于一轮测试暴露全部卡点）；
     * IO 异常（相机断开/静默）立即中断并抛出。
     */
    fun sonyInitSequence() {
        AppLog.i(TAG, "── 索尼初始化序列开始（alpha-fairy init_table）──")
        var failedSteps = 0
        failedSteps += runSdioStep("①GetDeviceInfo") {
            executeDataTransaction(PtpOperationCode.GET_DEVICE_INFO).size
        }
        failedSteps += runSdioStep("②GetStorageIDs") {
            executeDataTransaction(PtpOperationCode.GET_STORAGE_IDS).size
        }
        failedSteps += runSdioStep("③SDIOConnect{1,0,0}") {
            executeTransaction(SonySdioOperationCode.SDIO_CONNECT, longArrayOf(1, 0, 0)); 0
        }
        failedSteps += runSdioStep("④SDIOConnect{2,0,0}") {
            executeTransaction(SonySdioOperationCode.SDIO_CONNECT, longArrayOf(2, 0, 0)); 0
        }
        failedSteps += runSdioStep("⑤SDIOGetExtDeviceInfo{0x12C}") {
            executeDataTransaction(SonySdioOperationCode.SDIO_GET_EXT_DEVICE_INFO, longArrayOf(0x12C, 0, 0)).size
        }
        failedSteps += runSdioStep("⑥SDIOConnect{3,0,0}") {
            executeTransaction(SonySdioOperationCode.SDIO_CONNECT, longArrayOf(3, 0, 0)); 0
        }
        failedSteps += runSdioStep("⑦SDIOGetExtDeviceInfo{0x12C}") {
            executeDataTransaction(SonySdioOperationCode.SDIO_GET_EXT_DEVICE_INFO, longArrayOf(0x12C, 0, 0)).size
        }
        if (failedSteps > 0) {
            AppLog.w(TAG, "索尼初始化序列走完，但 $failedSteps 步响应异常（见上方日志）")
        } else {
            AppLog.i(TAG, "── 索尼初始化序列全部成功 ──")
        }
    }

    /**
     * 执行一步初始化子序列。
     * @return 0 = 成功；1 = 响应码异常（记录后继续）；IO 异常直接抛出（相机已断开，继续无意义）
     */
    private fun runSdioStep(name: String, step: () -> Int): Int = try {
        val dataLength = step()
        AppLog.i(TAG, "索尼初始化 [$name] OK（数据 $dataLength 字节）")
        0
    } catch (e: PtpResponseException) {
        AppLog.e(TAG, "索尼初始化 [$name] 响应异常：${e.message}，继续观察后续步骤")
        1
    } catch (e: PtpIoException) {
        AppLog.e(TAG, "索尼初始化 [$name] IO 失败，序列中断：${e.message}")
        throw e
    } catch (e: Exception) {
        AppLog.e(TAG, "索尼初始化 [$name] 异常，序列中断：${e::class.simpleName}: ${e.message}")
        throw e
    }

    /**
     * 设置索尼「内容传输模式」（0x9212 SDIO_SetContentsTransferMode，失败不影响连接）。
     *
     * **线程契约**：本方法内部用 [Thread.sleep] 做固件要求的时序等待（整卡模式约 1.7s），
     * **只允许在后台线程调用**（当前唯一调用点 PtpChannel.connectInternal 位于
     * Dispatchers.IO）。本类不依赖协程，故不改为 delay——若未来从主线程调用会 ANR。
     *
     * 按功能模式区分参数（反编译 CokeeZVE 印证）：
     * - functionMode=1（ContentsTransfer 整卡）：OFF[2,0,0] → 200ms → ON[2,1,0] → 1500ms
     *   （REMOTE_DEVICE=2 / OFF=0 / ON=1）
     * - functionMode=0（RemoteControl 选片集）：{1,0,0}（旧参数，解锁选片集推送）
     */
    fun sonyTryContentsTransferMode(functionMode: Int = 1) {
        if (functionMode == 1) {
            AppLog.i(TAG, "── 内容传输模式设置（整卡：OFF[2,0,0] → ON[2,1,0]）──")
            try {
                executeTransaction(SonySdioOperationCode.SDIO_SET_CONTENTS_TRANSFER_MODE, longArrayOf(2, 0, 0))
                AppLog.i(TAG, "SetContentsTransferMode[2,0,0]（OFF）OK")
                Thread.sleep(200)
                val resp = executeTransaction(SonySdioOperationCode.SDIO_SET_CONTENTS_TRANSFER_MODE, longArrayOf(2, 1, 0))
                AppLog.i(TAG, "SetContentsTransferMode[2,1,0]（ON）响应：0x${resp.responseCode.toString(16)}（${PtpResponseCode.description(resp.responseCode)}）")
                Thread.sleep(1500)
            } catch (e: Exception) {
                AppLog.w(TAG, "SetContentsTransferMode 异常：${e::class.simpleName}: ${e.message}")
            }
        } else {
            AppLog.i(TAG, "── 内容传输模式设置（选片集：{1,0,0}）──")
            try {
                val resp = executeTransaction(SonySdioOperationCode.SDIO_SET_CONTENTS_TRANSFER_MODE, longArrayOf(1, 0, 0))
                AppLog.i(TAG, "SetContentsTransferMode{1,0,0} 响应：0x${resp.responseCode.toString(16)}（${PtpResponseCode.description(resp.responseCode)}）")
            } catch (e: Exception) {
                AppLog.w(TAG, "SetContentsTransferMode{1,0,0} 异常：${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * 触发快门（PTP 标准 InitiateCapture 0x100E）。
     * 「电脑遥控」模式下可用；新照片是否进入待传输内容集由相机决定，
     * 通常需在相机端选片后经相册下载。
     * @return 拍摄结果对象句柄（部分固件返回 0xFFFFFFFF 表示无立即句柄）
     */
    fun initiateCapture(): Long {
        val response = executeTransaction(PtpOperationCode.INITIATE_CAPTURE, longArrayOf(0))
        checkResponse(response)
        val handle = response.parameters.firstOrNull() ?: 0xFFFFFFFFL
        AppLog.i(TAG, "快门已触发（InitiateCapture），对象句柄=0x" + handle.toString(16))
        return handle
    }

    // ── 设备属性（DeviceProp）读写 ─────────────────────────────────────

    /**
     * 设置设备属性（索尼 SDIO 扩展 + 数据阶段）。
     *
     * 候选操作码（按顺序尝试，命中即返回）：
     * 1. `0x9205 SDIO_SetExtDevicePropValue`，params=[propCode]（消费级相机如 ZV-E10；
     *    与 0x9209 同一族，0x9209 能用即说明这族支持）
     * 2. `0x9207 SDIO_ControlDevice`，params=[propCode, 0]（索尼电影机如 FX30/FX3；
     *    sony-alpha-python set_device_property_sync 实测路径）
     *
     * DataPhaseInfo 仍用 NO_DATA(0x1)——与工程其余事务一致（ZV-E10 依据操作码判断方向）。
     *
     * @param propCode  设备属性码
     * @param value     值（小端写入 valueSize 字节）
     * @param valueSize 值字节数（2 = UINT16，4 = UINT32）
     * @return 任一候选操作码返回 OK 即为 true
     */
    fun setDeviceProperty(propCode: Int, value: Long, valueSize: Int): Boolean {
        val payload = PtpBuffer.writer().apply {
            if (valueSize == 4) writeUInt32(value) else writeUInt16(value.toInt())
        }.toByteArray()
        val payloadHex = payload.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        // 候选 1：消费级相机路径
        AppLog.i(TAG, "设属性 0x${propCode.toString(16)} value=0x${value.toString(16)} 试 0x9205 SetExtDevicePropValue params=[propCode]")
        if (trySetDeviceProperty(
                SonySdioOperationCode.SDIO_SET_EXT_DEVICE_PROP,
                longArrayOf(propCode.toLong()),
                payload
            )
        ) {
            AppLog.i(TAG, "设属性 0x${propCode.toString(16)} 成功（0x9205）")
            return true
        }
        // 候选 2：电影机回退路径
        AppLog.w(TAG, "0x9205 未成功，回退 0x9207 SDIO_ControlDevice params=[propCode, 0]")
        if (trySetDeviceProperty(
                SonySdioOperationCode.SDIO_CONTROL_DEVICE,
                longArrayOf(propCode.toLong(), 0L),
                payload
            )
        ) {
            AppLog.i(TAG, "设属性 0x${propCode.toString(16)} 成功（0x9207 回退）")
            return true
        }
        AppLog.w(TAG, "设属性 0x${propCode.toString(16)} 两个候选 opcode 均未成功，payload=$payloadHex")
        return false
    }

    /**
     * 单次设属性尝试。发送 OperationRequest + StartData + Data + EndData，
     * 读 OperationResponse 判定 OK / 错误码。
     *
     * 注意：用显式 return（而非 return try{while{...}}）规避 Kotlin
     * 把 try 块推断为 Unit 的问题——与 executeTransaction 同款读取模式。
     */
    private fun trySetDeviceProperty(opCode: Int, params: LongArray, payload: ByteArray): Boolean {
        val tid = nextTransactionId()
        try {
            sendPacket(OperationRequest(DataPhaseInfo.NO_DATA, opCode, tid, params))
            sendPacket(StartData(tid, payload.size.toLong()))
            sendPacket(DataPacket(tid, payload))
            sendPacket(EndData(tid, ByteArray(0)))

            val response = readOperationResponse()
            if (response.responseCode != PtpResponseCode.OK) {
                AppLog.w(
                    TAG,
                    "op=0x${opCode.toString(16)} params=${params.toList()} 失败：0x${response.responseCode.toString(16)}（${PtpResponseCode.description(response.responseCode)}）"
                )
                return false
            }
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "op=0x${opCode.toString(16)} 异常：${e::class.simpleName}: ${e.message}")
            return false
        }
    }

    /** 读取命令连接上的 OperationResponse（跳过非响应包；与 executeTransaction 同模式） */
    private fun readOperationResponse(): OperationResponse {
        while (true) {
            when (val packet = readPacket()) {
                is OperationResponse -> return packet
                else -> continue
            }
        }
    }

    /**
     * 读取全部设备属性（索尼 SDIO_GET_ALL_EXT_DEVICE_PROP_INFO 0x9209）。
     * 返回原始字节（含各属性描述符 + 当前值），解析在 app 层按描述符逐项搜索。
     */
    fun getAllDeviceProperties(): ByteArray {
        AppLog.i(TAG, "读取全部设备属性（0x9209 SDIO_GetAllExtDevicePropInfo）")
        return executeDataTransaction(SonySdioOperationCode.SDIO_GET_ALL_EXT_DEVICE_PROP_INFO)
    }

    /** 获取设备信息（相机型号等） */
    fun getDeviceInfo(): DeviceInfo {
        val data = executeDataTransaction(PtpOperationCode.GET_DEVICE_INFO)
        val info = DeviceInfo.parse(PtpBuffer.reader(data))
        AppLog.i(TAG, "设备信息：${info.manufacturer} ${info.model} ${info.deviceVersion}")
        return info
    }

    // ── 对象浏览 ─────────────────────────────────────────────────────

    /** 获取存储 ID 列表 */
    fun getStorageIds(): List<Long> {
        val data = executeDataTransaction(PtpOperationCode.GET_STORAGE_IDS)
        val buffer = PtpBuffer.reader(data)
        val count = buffer.readUInt32().toInt()
        val ids = (0 until count).map { buffer.readUInt32() }
        AppLog.i(TAG, "存储数量：$count（${ids.joinToString()}）")
        return ids
    }

    /**
     * 获取对象句柄
     * @param parent 0x00000000 = 全卡所有对象（含子文件夹内文件）；0xFFFFFFFF = 仅根层级
     */
    fun getObjectHandles(storageId: Long, parent: Long = 0x0L): List<Long> {
        val data = executeDataTransaction(
            PtpOperationCode.GET_OBJECT_HANDLES,
            longArrayOf(storageId, 0, parent)
        )
        val buffer = PtpBuffer.reader(data)
        val count = buffer.readUInt32().toInt()
        // P2-2：count 来自相机且无符号 32 位回读，畸形值（流错位时常见 0xFFFFFFFF → -1）
        // 会让 `(0 until count).map` 分配 42 亿元素或直接抛 NegativeArraySizeException。
        if (count < 0 || count > MAX_OBJECT_HANDLES) {
            throw PtpMalformedPacketException(
                "GetObjectHandles 返回非法数量：$count（上限 $MAX_OBJECT_HANDLES）——流可能已错位"
            )
        }
        AppLog.i(TAG, "存储 $storageId 对象数量：$count（parent=0x${parent.toString(16)}）")
        return (0 until count).map { buffer.readUInt32() }
    }

    /** 获取对象信息 */
    fun getObjectInfo(handle: Long): ObjectInfo {
        val data = executeDataTransaction(PtpOperationCode.GET_OBJECT_INFO, longArrayOf(handle))
        return ObjectInfo.parse(PtpBuffer.reader(data))
    }

    /** 获取缩略图（JPEG 字节） */
    fun getThumbnail(handle: Long): ByteArray =
        executeDataTransaction(PtpOperationCode.GET_THUMB, longArrayOf(handle))

    /** 获取对象大小（用于进度显示） */
    fun getObjectSize(handle: Long): Long = runCatching {
        getObjectInfo(handle).compressedSize
    }.getOrDefault(0L)

    // ── 文件下载 ─────────────────────────────────────────────────────

    /**
     * 流式下载对象到 [output]（支持 2GB+ 视频，不 OOM）
     * @param onProgress 进度回调（已下载 / 总字节）
     */
    fun getObject(
        handle: Long,
        output: OutputStream,
        onProgress: DataLoadListener = DataLoadListener { _, _ -> }
    ) {
        AppLog.i(TAG, "开始下载对象 handle=$handle")
        executeDataTransaction(PtpOperationCode.GET_OBJECT, longArrayOf(handle), output, onProgress)
        AppLog.i(TAG, "下载完成 handle=$handle")
    }

    /**
     * 分块下载对象（PTP 标准 GetPartialObject 0x101B），用于断点续传与失败重试。
     * 从 [offset] 字节起、最多取 [maxBytes] 字节写入 [output]。
     * 数据阶段布局与 getObject 完全一致（StartData→Data→EndData→Response），
     * 故直接复用 executeDataTransaction；[onProgress] 报告的是本块内进度。
     *
     * 参数布局（ISO 15740）：[ObjectHandle] + [Offset(UINT64): 低32/高32] + [MaxBytes(UINT32)]。
     * Offset 为 64 位，单文件 >4GB 也可通过累加 offset 续传；若单请求需拉取 >4GB，
     * 可用索尼扩展 SDIO_GET_PARTIAL_LARGE_OBJECT(0x9219) 替代（同布局、MaxBytes 扩为 UINT64）。
     */
    fun getPartialObject(
        handle: Long,
        offset: Long,
        maxBytes: Long,
        output: OutputStream,
        onProgress: DataLoadListener = DataLoadListener { _, _ -> }
    ) {
        AppLog.i(TAG, "分块下载对象 handle=$handle offset=$offset maxBytes=$maxBytes")
        val params = longArrayOf(
            handle,
            offset and 0xFFFFFFFFL,
            offset ushr 32,
            maxBytes and 0xFFFFFFFFL
        )
        executeDataTransaction(PtpOperationCode.GET_PARTIAL_OBJECT, params, output, onProgress)
        AppLog.i(TAG, "分块下载完成 handle=$handle offset=$offset")
    }

    // ── 事务执行 ─────────────────────────────────────────────────────

    /**
     * 生成下一个事务 ID（P2-1）。
     *
     * 用 AtomicLong 而非裸 `++`：本类的线程安全契约是「事务序列由调用方
     * （PtpChannel.ptpMutex）串行化」，connect/disconnect 的 @Synchronized 只管
     * 生命周期。一旦出现绕过互斥的并发调用，裸 `++` 会产生重复事务 ID →
     * 响应错配 → 流错位（比崩溃更难排查）。AtomicLong 是零成本兜底。
     */
    private val transactionCounter = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextTransactionId(): Long = transactionCounter.incrementAndGet()

    // 线程安全契约（P2-1）：sendPacket/readPacket 操作同一条 TCP 流，
    // 必须串行调用。生命周期方法（connect/disconnect/forceClose）由 @Synchronized
    // 保护；事务方法由上层 PtpChannel.ptpMutex 互斥——这里刻意不加锁，
    // 避免「包中间被打断」这种锁粒度错误造成的半包写入。

    /** 发送包（命令连接） */
    private fun sendPacket(packet: PtpIpPacket) {
        val out = commandOut ?: throw PtpIoException("未连接")
        out.write(packet.serialize())
        out.flush()
    }

    /** 发送包（事件连接） */
    private fun sendEventPacket(packet: PtpIpPacket) {
        val out = eventOut ?: throw PtpIoException("事件连接未建立")
        out.write(packet.serialize())
        out.flush()
    }

    /** 读取一个包（命令连接） */
    private fun readPacket(): PtpIpPacket {
        val input = commandIn ?: throw PtpIoException("未连接")
        return PtpIpPacket.read(input)
    }

    /** 读取一个包（事件连接）——InitEventAck 及后续相机事件都走这条流 */
    private fun readEventPacket(): PtpIpPacket {
        val input = eventIn ?: throw PtpIoException("事件连接未建立")
        return PtpIpPacket.read(input)
    }

    /**
     * 把事件连接切到轮询模式（短读超时），供事件监听循环使用。
     * 此前事件流握手完成后从未读取——相机推送的事件全部堆积在 TCP 缓冲，
     * 选片发送的内容集也因此不刷新（RequestObjectTransfer 无人消费）。
     */
    fun eventPollMode() {
        runCatching { eventSocket?.soTimeout = EVENT_POLL_TIMEOUT_MS }
    }

    /**
     * 从事件流读取一个事件（阻塞至 EVENT_POLL_TIMEOUT_MS）。
     * @return 相机推送的事件；超时（无事件）返回 null；ProbeRequest 自动回复后返回 null
     */
    fun readEvent(): Event? {
        val packet = try {
            readEventPacket()
        } catch (e: java.net.SocketTimeoutException) {
            return null
        }
        return when (packet) {
            is Event -> packet
            is ProbeRequest -> { runCatching { sendEventPacket(ProbeResponse()) }; null }
            else -> null
        }
    }

    /**
     * 执行无数据阶段事务（OpenSession/CloseSession 等）
     */
    private fun executeTransaction(
        operationCode: Int,
        parameters: LongArray = LongArray(0)
    ): OperationResponse {
        val tid = nextTransactionId()
        val request = OperationRequest(DataPhaseInfo.NO_DATA, operationCode, tid, parameters)
        sendPacket(request)

        // 读响应（可能先收到 StartData/Data/EndData，再是 OperationResponse；无数据阶段直接是 Response）
        while (true) {
            when (val packet = readPacket()) {
                is OperationResponse -> return packet
                else -> continue
            }
        }
    }

    /**
     * 执行含响应数据阶段的事务（DATA_IN：相机回 StartData→Data→EndData→Response）
     * 返回接收到的完整数据（或流式写出到 [output]）
     */
    private fun executeDataTransaction(
        operationCode: Int,
        parameters: LongArray = LongArray(0),
        output: OutputStream? = null,
        onProgress: DataLoadListener = DataLoadListener { _, _ -> }
    ): ByteArray {
        val tid = nextTransactionId()
        val request = OperationRequest(DataPhaseInfo.NO_DATA, operationCode, tid, parameters)
        sendPacket(request)

        var dataLength = 0L
        var received = 0L
        val memoryBuffer = if (output == null) PtpBuffer.writer() else null

        while (true) {
            when (val packet = readPacket()) {
                is StartData -> {
                    dataLength = packet.dataLength
                }
                is DataPacket -> {
                    received += packet.payload.size
                    if (output != null) {
                        output.write(packet.payload)
                    } else {
                        // P2-3：内存路径必须有上限，否则畸形/错位数据会一直缓冲直至 OOM
                        if (received > MAX_IN_MEMORY_BYTES) {
                            throw PtpMalformedPacketException(
                                "事务 0x${operationCode.toString(16)} 数据超出内存上限" +
                                    "（$received > $MAX_IN_MEMORY_BYTES）——大对象必须走流式输出"
                            )
                        }
                        memoryBuffer?.writeBytes(packet.payload)
                    }
                    onProgress.onDataLoaded(received, if (dataLength > 0) dataLength else received)
                }
                is EndData -> {
                    received += packet.payload.size
                    if (packet.payload.isNotEmpty()) {
                        if (output != null) {
                            output.write(packet.payload)
                        } else {
                            memoryBuffer?.writeBytes(packet.payload)
                        }
                    }
                }
                is OperationResponse -> {
                    if (packet.responseCode != PtpResponseCode.OK) {
                        AppLog.e(
                            TAG,
                            "操作 0x${operationCode.toString(16)} 失败：响应码 0x${packet.responseCode.toString(16)}（${PtpResponseCode.description(packet.responseCode)}）"
                        )
                        throw PtpResponseException(
                            packet.responseCode,
                            PtpResponseCode.description(packet.responseCode)
                        )
                    }
                    output?.flush()
                    return memoryBuffer?.toByteArray() ?: ByteArray(0)
                }
                else -> continue
            }
        }
    }

    /** 校验响应码 */
    private fun checkResponse(response: OperationResponse) {
        if (response.responseCode != PtpResponseCode.OK) {
            AppLog.e(TAG, "操作失败：响应码 0x${response.responseCode.toString(16)}（${PtpResponseCode.description(response.responseCode)}）")
            throw PtpResponseException(
                response.responseCode,
                PtpResponseCode.description(response.responseCode)
            )
        }
    }
}
