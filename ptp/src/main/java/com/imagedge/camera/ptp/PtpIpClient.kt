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

/** A camera normally exposes one or two storages; keep malformed counts from allocating freely. */
private const val MAX_STORAGE_IDS = 32

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
    /** 仅 [connect] 使用；USB 通路走 [connectVia]，不经过这里 */
    private val host: String = "",
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

    /**
     * 最近一次 GetDeviceInfo 的结果，用于查操作码支持情况。
     *
     * 只在 [getDeviceInfo] 里写入——它是唯一权威来源，缓存别处会与相机真实能力脱节。
     */
    @Volatile
    private var cachedDeviceInfo: DeviceInfo? = null

    /**
     * 命令通道。默认是 TCP 承载；传入 USB 通道时跳过 PTP/IP 握手——
     * USB 上没有 InitCommandRequest 这一步，会话直接开。
     */
    private var commandChannel: PtpCommandChannel? = null
    private var eventIn: CountingInputStream? = null
    /** 事件流已读走的字节数：用来区分「一个包都没开始读」和「读到一半卡住」 */
    private var eventBytesRead: Long = 0L
    private var eventOut: BufferedOutputStream? = null

    private var connectionNumber: Long = 0
    private var sessionId: Long = 0
    private var opened = false

    val isConnected: Boolean
        get() = commandChannel?.isOpen == true

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
        AppLog.i(TAG, "连接 PTP/IP（事件连接=$useEventConnection）")

        // ① 命令 socket
        val cmd = Socket()
        cmd.connect(InetSocketAddress(host, port), connectTimeoutMs)
        cmd.tcpNoDelay = true
        cmd.soTimeout = HANDSHAKE_TIMEOUT_MS
        commandSocket = cmd
        commandChannel = SocketPtpCommandChannel(
            input = BufferedInputStream(cmd.getInputStream()),
            output = BufferedOutputStream(cmd.getOutputStream()),
            isAlive = { cmd.isConnected && !cmd.isClosed },
            onClose = { runCatching { cmd.close() } }
        )
        AppLog.d(TAG, "命令 socket 已建立")

        // ② 事件 socket（握手前建立——alpha-fairy 顺序）
        if (useEventConnection) {
            runCatching {
                val evt = Socket()
                evt.connect(InetSocketAddress(host, port), connectTimeoutMs)
                evt.tcpNoDelay = true
                evt.soTimeout = HANDSHAKE_TIMEOUT_MS
                eventSocket = evt
                eventIn = CountingInputStream(evt.getInputStream()) { eventBytesRead += it }
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

    /**
 * 在一条已建立的命令通道上启动会话，跳过 PTP/IP 握手。
     *
     * USB 承载走这里：USB 上没有 `InitCommandRequest`/`InitEventRequest` 那一套
     * （那是 ISO 15740 为 TCP 定义的会话建立步骤），插上即用，会话由 [openSession]
     * 直接开。因此**不能**复用 [connect]——那会把握手包发到 USB 端点上。
     *
     * 事件通道在 USB 上来自中断端点而非第二条连接，[eventPollMode]/[readEventOutcome]
     * 在这种链路下不可用；需要事件（拍完照片自动拉回）时用 Wi-Fi 通路。
     *
     * @param channel 调用方负责建好并已打开（例如 USB 端点通道）
     */
    @Synchronized
    fun connectVia(channel: PtpCommandChannel) {
        forceClose()
        commandChannel = channel
        AppLog.i(TAG, "已在既有命令通道上建立 PTP 会话（无握手）")
    }

    /** 断开连接 */
    @Synchronized
    fun disconnect() {
        runCatching { if (opened) closeSession() }
        runCatching { commandChannel?.close() }
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        commandChannel = null
        commandSocket = null
        eventSocket = null
        eventIn = null
        eventOut = null
        // 能力清单随会话失效：留着上一台相机的答案，会让下一次连接问出
        // 属于别的相机的结论——而能力判定正是拿来当依据用的
        cachedDeviceInfo = null
        opened = false
    }

    /**
     * 强制关闭底层连接（不握手、不发 CloseSession）。
     * 事务超时自愈用：让阻塞在 read 上的线程立刻收到异常而解除，
     * 避免相机在内容库重建（如第二次发送）期间不响应导致整个通道永久挂死。
     */
    @Synchronized
    fun forceClose() {
        AppLog.w(TAG, "强制关闭 PTP 底层连接（事务超时自愈）")
        runCatching { commandChannel?.close() }
        runCatching { commandSocket?.close() }
        runCatching { eventSocket?.close() }
        commandChannel = null
        commandSocket = null
        eventSocket = null
        eventIn = null
        eventOut = null
        // 能力清单随会话失效：留着上一台相机的答案，会让下一次连接问出
        // 属于别的相机的结论——而能力判定正是拿来当依据用的
        cachedDeviceInfo = null
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

    // ── 设备属性（DeviceProp）读写 ─────────────────────────────────────

    /**
     * 设置设备属性（索尼 SDIO 扩展 + 数据阶段）。
     *
     * 候选操作码（按顺序尝试，命中即返回）：
     * 1. `0x9205 SDIO_SetExtDevicePropValue`，params=[propCode]（消费级相机如 ZV-E10；
     *    与 0x9209 同一族，0x9209 能用即说明这族支持）
     * 2. `0x9207 SDIO_ControlDevice`，params=[propCode]（索尼电影机如 FX30/FX3）
     *
     * 两条都带数据阶段，故声明为 `DATA_OUT`。此前声明成 `NO_DATA` 却仍发出
     * StartData/Data/EndData——声明与实际不一致，这条回退路径的可信度一直存疑。
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
        // 候选 2：电影机回退路径。
        // 参数与官方一致，只带属性码一个——此前这里多挂了一个 0，
        // 且把数据阶段声明成「无数据」却又发了 StartData/Data/EndData。
        AppLog.w(TAG, "0x9205 未成功，回退 0x9207 SDIO_ControlDevice params=[propCode]")
        if (trySetDeviceProperty(
                SonySdioOperationCode.SDIO_CONTROL_DEVICE,
                longArrayOf(propCode.toLong()),
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
     * [dataPhase] 必须与实际发出的数据阶段一致：声明「无数据」却跟着发
     * StartData/Data/EndData，相机一侧无法自洽，这条事务此前就是这样写错的。
     *
     * 注意：用显式 return（而非 return try{while{...}}）规避 Kotlin
     * 把 try 块推断为 Unit 的问题——与 executeTransaction 同款读取模式。
     */
    private fun trySetDeviceProperty(
        opCode: Int,
        params: LongArray,
        payload: ByteArray,
        dataPhase: Int = DataPhaseInfo.DATA_OUT
    ): Boolean {
        val tid = nextTransactionId()
        try {
            sendPacket(OperationRequest(dataPhase, opCode, tid, params))
            sendPacket(StartData(tid, payload.size.toLong()))
            sendPacket(DataPacket(tid, payload))
            sendPacket(EndData(tid, ByteArray(0)))

            val response = readOperationResponse(tid)
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

    // ── 设备控制（按键类动作）─────────────────────────────────────────

    /**
     * 下发一条设备控制指令（[SonySdioOperationCode.SDIO_CONTROL_DEVICE]）。
     *
     * 与设属性的区别：这条走的是「按键」通道——参数只有一个控制码，
     * 值放在数据阶段里按该控制码约定的宽度排列。相机的快门、对焦驱动、
     * 触摸 AF 都走这里，属性事务按不动快门。
     *
     * @param controlCode 见 [SonyControlCode]
     * @param value       控制值，见 [SonyControlValue]；宽度由控制码决定
     * @return true 表示相机回了 OK；false 表示被拒或抛异常，调用方需自行决定退路
     */
    fun sendControl(controlCode: Int, value: Int): Boolean {
        // 刻意**不**在这里拦截。快门是本应用最要紧的动作，而「索尼固件会不会
        // 把 SDIO 扩展列进 GetDeviceInfo 的 OperationsSupported」没有依据可查：
        // 官方 App 自己从不查这份清单，所以它是保守的实现，但也可能保守到不列扩展。
        // 误判成不支持的代价是**快门被静默关掉**，远大于省下一次注定失败的往返
        // ——那一次往返本来就会失败并返回 false，行为完全一致。
        // 因此只把不一致记下来，让日志能指出来，而不是替相机做决定。
        if (supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE) == false) {
            AppLog.w(
                TAG,
                "相机未在 GetDeviceInfo 中上报 0x9207，仍照常下发控制指令；" +
                    "若该机型快门始终无响应，先查它的操作码清单是否完整"
            )
        }
        val width = SonyControlCode.payloadBytes(controlCode)
        val payload = PtpBuffer.writer().apply {
            when (width) {
                1 -> writeUInt8(value)
                2 -> writeUInt16(value)
                else -> writeUInt32(value.toLong())
            }
        }.toByteArray()

        return trySetDeviceProperty(
            SonySdioOperationCode.SDIO_CONTROL_DEVICE,
            longArrayOf(controlCode.toLong()),
            payload
        )
    }

    /**
     * 按下快门（半按）：相机进入对焦，不出图。
     */
    fun pressShutter(): Boolean = sendControl(SonyControlCode.S1_BUTTON, SonyControlValue.DOWN)

    /**
     * 松开快门：相机在已对焦的基础上释放快门出图。
     *
     * 与 [pressShutter] 是两次独立事务，相机按值区分这两个动作——
     * 只发其中一次分别得到「只对焦」和「不重新对焦直接释放」。
     */
    fun releaseShutter(): Boolean = sendControl(SonyControlCode.S1_BUTTON, SonyControlValue.RELEASE)

    /**
     * 手动对焦驱动一步。向 [direction] 为 0 时停止。
     *
     * 单步驱动而非连续：连续驱动需要在相机侧保持一个未完成的状态，
     * 而每一步独立事务的实现不需要维护那种状态，中断时也不会留下卡住的对焦。
     */
    fun driveFocus(direction: Int): Boolean = sendControl(SonyControlCode.NEAR_FAR, direction)

    fun focusNear(): Boolean = driveFocus(SonyControlValue.NEAR)

    fun focusFar(): Boolean = driveFocus(SonyControlValue.FAR)

    fun stopFocusDrive(): Boolean = driveFocus(SonyControlValue.STOP)

    /**
     * 在实时取景画面上指定对焦区域。
     *
     * 坐标按取景画面的千分比给出（0..1000），调用方不必知道实际像素分辨率。
     *
     * 该控制码的负载宽度是 4 字节，两坐标各占一个小端 UINT16；
     * **x 在低 16 位、y 在高 16 位这个排布是推断的**——依据是相机回报对焦区域
     * 的那个设备属性把坐标描述为 (x,y)，顺序与之一致。两坐标数量纲相同但互不
     * 可交换，发反了的表现是「对焦点落到了关于画面中心对称的位置」，
     * 而不是报错，所以真机验证时优先看这一点。
     */
    fun setFocusArea(xMilli: Int, yMilli: Int): Boolean {
        val cx = xMilli.coerceIn(0, 1000)
        val cy = yMilli.coerceIn(0, 1000)
        return sendControl(SonyControlCode.AF_AREA_POSITION, (cy shl 16) or cx)
    }

    /** 读取命令连接上的 OperationResponse（跳过非响应包；与 executeTransaction 同模式） */
    private fun readOperationResponse(expectedTransactionId: Long): OperationResponse {
        while (true) {
            when (val packet = readPacket()) {
                is OperationResponse -> {
                    requireTransactionId(
                        packet.transactionId,
                        expectedTransactionId,
                        "OperationResponse",
                    )
                    return packet
                }
                is StartData -> {
                    requireTransactionId(packet.transactionId, expectedTransactionId, "StartData")
                    throw PtpMalformedPacketException("Unexpected StartData while waiting for response")
                }
                is DataPacket -> {
                    requireTransactionId(packet.transactionId, expectedTransactionId, "Data")
                    throw PtpMalformedPacketException("Unexpected Data while waiting for response")
                }
                is EndData -> {
                    requireTransactionId(packet.transactionId, expectedTransactionId, "EndData")
                    throw PtpMalformedPacketException("Unexpected EndData while waiting for response")
                }
                else -> throw PtpMalformedPacketException("Unexpected command packet while waiting for response")
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

    // ── 对象属性（文件级元数据）──────────────────────────────────────

    /** 一个「支持哪些对象属性」响应里允许的最大条目数。真实相机远达不到。 */
    private val MAX_OBJECT_PROP_CODES = 4096

    /**
     * 相机支持哪些对象属性。
     *
     * 整族不支持时相机回 `OPERATION_NOT_SUPPORTED` 而不是空列表，
     * 该情况映射为 null——调用方必须区分「不支持」与「支持但当前无内容」。
     */
    fun getSupportedObjectProps(): List<Int>? {
        if (supportsOperation(SonyObjectPropOperationCode.GET_OBJECT_PROPS_SUPPORTED) == false) return null
        return try {
            val data = executeDataTransaction(SonyObjectPropOperationCode.GET_OBJECT_PROPS_SUPPORTED)
            val buffer = PtpBuffer.reader(data)
            val count = buffer.readUInt32().toInt()
            if (count < 0 || count > MAX_OBJECT_PROP_CODES) {
                throw PtpMalformedPacketException("支持的对象属性数量非法：$count")
            }
            List(count) { buffer.readUInt16() }
        } catch (e: PtpResponseException) {
            if (e.responseCode == PtpResponseCode.OPERATION_NOT_SUPPORTED) null else throw e
        }
    }

    /**
     * 一次读多个「文件 × 属性」。
     *
     * @param queries `(句柄, 属性码)` 的配对列表
     * @return 相机实际回报的部分。相机会静默略过不支持或读不到的组合，
     *         所以**结果里缺的项是常态而非错误**——调用方要按「没问出来」处理，
     *         不能把缺失断言成「该文件没有这个属性」。
     */
    fun getObjectProps(queries: List<Pair<Long, Int>>): ObjectPropMap {
        if (queries.isEmpty()) return ObjectPropMap.parse(ByteArray(0))
        require(queries.size <= MAX_OBJECT_PROP_CODES) {
            "对象属性查询 ${queries.size} 项超出单次上限 $MAX_OBJECT_PROP_CODES"
        }
        if (!isUsable(SonyObjectPropOperationCode.GET_OBJECT_PROP_LIST, "对象属性列表")) {
            return ObjectPropMap.parse(ByteArray(0))
        }

        val parameters = LongArray(queries.size * 2)
        queries.forEachIndexed { index, (handle, propCode) ->
            parameters[index * 2] = handle
            parameters[index * 2 + 1] = propCode.toLong()
        }

        val data = executeDataTransaction(
            SonyObjectPropOperationCode.GET_OBJECT_PROP_LIST,
            parameters
        )
        return ObjectPropMap.parse(data)
    }

    /** 读单个文件的单个属性；相机没回报时为 null。 */
    fun getObjectProp(objectHandle: Long, propCode: Int): ObjectPropValue? =
        getObjectProps(listOf(objectHandle to propCode))
            .propertiesOf(objectHandle)[propCode]?.value

    /**
     * 批量取文件元数据，按句索引返回。
     *
     * 这是传输列表在拿到字节之前认出 RAW / 代理 / 多帧合成片的入口：
     * [MediaMetadata.isComposite] 依赖的合成帧数只有相机端知道。
     */
    fun getMediaMetadata(handles: List<Long>): Map<Long, MediaMetadata> {
        val wanted = listOf(
            SonyObjectPropCode.WIDTH,
            SonyObjectPropCode.HEIGHT,
            SonyObjectPropCode.OBJECT_SIZE,
            SonyObjectPropCode.IS_MOVIE_PROXY,
            SonyObjectPropCode.PRIMARY_IMAGE_COUNT,
            SonyObjectPropCode.MPTYPE_CODE,
            SonyObjectPropCode.VIDEO_BIT_DEPTH,
            SonyObjectPropCode.COLOR_FORMAT
        )
        if (handles.isEmpty()) return emptyMap()

        val queries = handles.flatMap { handle -> wanted.map { handle to it } }
        val props = getObjectProps(queries)
        return handles.associateWith { handle ->
            MediaMetadata.from(props.propertiesOf(handle))
        }
    }

    // ── 实时取景地址 ────────────────────────────────────────────────

    /**
     * 相机下发的实时取景地址。
     *
     * 端口与路径由相机自己给出；此前端口是写死的，只因为没有读它的地方。
     *
     * @return URL 字符串；相机未开放取景（未就绪、被占用）时为 null
     */
    fun readLiveViewUrl(): String? =
        DevicePropParser.findString(getAllDeviceProperties(), SonyDevicePropCode.LIVE_VIEW_URL)

    // ── 色彩档案 ────────────────────────────────────────────────────

    /**
     * 读相机当前的 Picture Profile 与创意风格设置。
     *
     * 一次 `0x9209` 就能全部拿到，因此这是所有色彩读取里最便宜的一条路径。
     */
    fun readColorProfile(): CameraColorProfile {
        val codes = listOf(
            SonyDevicePropCode.PICTURE_PROFILE,
            SonyDevicePropCode.CREATIVE_STYLE,
            SonyDevicePropCode.CREATIVE_LOOK_CONTRAST,
            SonyDevicePropCode.CREATIVE_LOOK_HIGHLIGHTS,
            SonyDevicePropCode.CREATIVE_LOOK_SHADOWS,
            SonyDevicePropCode.CREATIVE_LOOK_FADE,
            SonyDevicePropCode.CREATIVE_LOOK_SATURATION,
            SonyDevicePropCode.CREATIVE_LOOK_SHARPNESS,
            SonyDevicePropCode.CREATIVE_LOOK_CLARITY
        )
        return CameraColorProfile.from(DevicePropParser.parse(getAllDeviceProperties(), codes))
    }

    // ── 像素位移多帧拍摄 ──────────────────────────────────────────────

    /**
     * 配置像素位移多帧拍摄。
     *
     * 相机端自己完成多帧位移与合成，手机只负责下发参数并读进度。
     *
     * @param frameCount 合成的帧数
     * @param intervalMs 帧间隔（毫秒）
     */
    fun configurePixelShiftShooting(frameCount: Int, intervalMs: Int): Boolean {
        val count = frameCount.coerceIn(2, 999)
        val interval = intervalMs.coerceIn(0, 60_000)
        val ok = setDeviceProperty(SonyDevicePropCode.PIXEL_SHIFT_SHOOTING_NUMBER, count.toLong(), 2)
        if (!ok) return false
        return setDeviceProperty(SonyDevicePropCode.PIXEL_SHIFT_SHOOTING_INTERVAL, interval.toLong(), 2)
    }

    /** 像素位移拍摄当前进度：已完成帧数。相机合成中时递增。 */
    fun pixelShiftProgress(): Int? {
        val props = DevicePropParser.parse(
            getAllDeviceProperties(),
            listOf(
                SonyDevicePropCode.PIXEL_SHIFT_SHOOTING_STATUS,
                SonyDevicePropCode.PIXEL_SHIFT_SHOOTING_PROGRESS
            )
        )
        return props[SonyDevicePropCode.PIXEL_SHIFT_SHOOTING_PROGRESS]?.currentValue?.toInt()
    }

    // ── 焦点包围 ────────────────────────────────────────────────────

    /**
     * 配置焦点包围：对焦行程与张数。
     *
     * 相机沿近到远逐张对焦拍摄后自行合成；手机只设参数、按快门、等结果。
     *
     * @param shotCount   张数
     * @param focusRange  对焦行程（相机定义的行程单位，不是毫米）
     */
    fun configureFocusBracketing(shotCount: Int, focusRange: Int): Boolean {
        val shots = shotCount.coerceIn(2, 99)
        val range = focusRange.coerceAtLeast(0)
        val ok = setDeviceProperty(SonyDevicePropCode.FOCUS_BRACKET_SHOT_NUM, shots.toLong(), 2)
        if (!ok) return false
        return setDeviceProperty(SonyDevicePropCode.FOCUS_BRACKET_FOCUS_RANGE, range.toLong(), 2)
    }

    /** 焦点包围是否被相机接受：行程为 0 或张数为 0 时表示未启用。 */
    fun focusBracketingReady(): Boolean {
        val props = DevicePropParser.parse(
            getAllDeviceProperties(),
            listOf(SonyDevicePropCode.FOCUS_BRACKET_SHOT_NUM, SonyDevicePropCode.FOCUS_BRACKET_FOCUS_RANGE)
        )
        val shots = props[SonyDevicePropCode.FOCUS_BRACKET_SHOT_NUM]?.currentValue ?: return false
        return shots >= 2
    }

    /** 获取设备信息（相机型号等），并记下它上报的操作码清单 */
    fun getDeviceInfo(): DeviceInfo {
        val data = executeDataTransaction(PtpOperationCode.GET_DEVICE_INFO)
        val info = DeviceInfo.parse(PtpBuffer.reader(data))
        cachedDeviceInfo = info
        AppLog.i(TAG, "设备信息：${info.manufacturer} ${info.model} ${info.deviceVersion}（上报 ${info.operationsSupported.size} 个操作码）")
        return info
    }

    /**
     * 相机是否上报支持某个操作码。
     *
     * **null 表示判定不了**——还没读过 GetDeviceInfo，或这次会话没连上。
     * 「判定不了」与「明确不支持」必须分开：前者按原有行为走，
     * 后者才据此跳过下发。把两者混为一谈会让首次连接前的探测全部变成静默失败。
     *
     * 判定的意义有两层：省掉一次注定失败的往返，以及给能力判定一个**不发报文**的依据。
     * 后者才是关键——过去想知道相机支不支持某能力，只能真的发一次然后等 `0x2005`。
     *
     * @return true/false = 相机明确上报支持/不支持；null = 未知
     */
    fun supportsOperation(operationCode: Int): Boolean? =
        cachedDeviceInfo?.operationsSupported?.contains(operationCode) ?: return null

    /**
     * 可选操作码的前置检查。
     *
     * 相机**明确**没上报支持时返回 false 并记一行日志，让调用方直接放弃；
     * 未知（尚未读设备信息）时返回 true，照原样发出去——把「不知道」当成「不支持」
     * 会让首次连接前的那次探测静默失效。
     */
    private fun isUsable(operationCode: Int, what: String): Boolean {
        if (supportsOperation(operationCode) != false) return true
        AppLog.i(TAG, "相机未上报支持 $what（0x${operationCode.toString(16)}），跳过下发")
        return false
    }

    // ── 对象浏览 ─────────────────────────────────────────────────────

    /** 获取存储 ID 列表 */
    fun getStorageIds(): List<Long> {
        val data = executeDataTransaction(PtpOperationCode.GET_STORAGE_IDS)
        val buffer = PtpBuffer.reader(data)
        val countRaw = buffer.readUInt32()
        if (countRaw > MAX_STORAGE_IDS.toLong() || countRaw > buffer.remaining / 4L) {
            throw PtpMalformedPacketException(
                "GetStorageIds returned invalid count: $countRaw (remaining=${buffer.remaining})"
            )
        }
        val count = countRaw.toInt()
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
        val countRaw = buffer.readUInt32()
        // P2-2：count 来自相机且无符号 32 位回读，畸形值（流错位时常见 0xFFFFFFFF → -1）
        // 会让 `(0 until count).map` 分配 42 亿元素或直接抛 NegativeArraySizeException。
        if (countRaw > MAX_OBJECT_HANDLES.toLong() || countRaw > buffer.remaining / 4L) {
            throw PtpMalformedPacketException(
                "GetObjectHandles 返回非法数量：$countRaw（上限 $MAX_OBJECT_HANDLES，剩余 ${buffer.remaining} 字节）——流可能已错位"
            )
        }
        val count = countRaw.toInt()
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
     * 可用索尼扩展 [SonySdioOperationCode.SDIO_GET_PARTIAL_LARGE_OBJECT] 替代
     * （同布局、MaxBytes 扩为 UINT64）。
     *
     * 分块大小应当逐机型调：libgphoto2 用 1 MiB 并注明「EOS R 不喜欢 5MB，但喜欢 1MB」。
     * 本函数目前把分块大小交给调用方，尚无实测机型背书。
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
        val channel = commandChannel ?: throw PtpIoException("未连接")
        channel.write(packet)
    }

    /** 发送包（事件连接） */
    private fun sendEventPacket(packet: PtpIpPacket) {
        val out = eventOut ?: throw PtpIoException("事件连接未建立")
        out.write(packet.serialize())
        out.flush()
    }

    /** 读取一个包（命令连接） */
    private fun readPacket(): PtpIpPacket {
        val channel = commandChannel ?: throw PtpIoException("未连接")
        return channel.read()
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
     *
     * 返回结局而不是裸事件：**「安静」与「错位」必须可区分**。
     * 读到一半超时时 socket 已从中途开始，此前一律返回 null，
     * 事件通道会静默坏死到用户重连为止——相册不再刷新，按键不再触发，
     * 而界面与日志都看不出异常。
     *
     * @return [EventReadOutcome.EVENT] 时 [EventRead.last] 才是有效载荷
     */
    fun readEventOutcome(): EventReadResult {
        val before = eventBytesRead
        val packet = try {
            readEventPacket()
        } catch (e: java.net.SocketTimeoutException) {
            val consumed = eventBytesRead - before
            return EventReadResult(
                classifyEventRead(timedOut = true, bytesConsumed = consumed, malformed = false),
                null
            )
        } catch (e: PtpMalformedPacketException) {
            return EventReadResult(
                classifyEventRead(timedOut = false, bytesConsumed = 0L, malformed = true),
                null
            )
        }
        return when (packet) {
            is Event -> EventReadResult(EventReadOutcome.EVENT, packet)
            is ProbeRequest -> {
                runCatching { sendEventPacket(ProbeResponse()) }
                EventReadResult(EventReadOutcome.HANDLED, null)
            }
            else -> EventReadResult(EventReadOutcome.HANDLED, null)
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
                is OperationResponse -> {
                    requireTransactionId(packet.transactionId, tid, "OperationResponse")
                    return packet
                }
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
        var started = false
        var ended = false
        val memoryBuffer = if (output == null) PtpBuffer.writer() else null

        while (true) {
            when (val packet = readPacket()) {
                is StartData -> {
                    requireTransactionId(packet.transactionId, tid, "StartData")
                    if (started) throw PtpMalformedPacketException("Duplicate StartData for transaction $tid")
                    if (packet.dataLength < 0) {
                        throw PtpMalformedPacketException("Negative data length for transaction $tid")
                    }
                    if (output == null && packet.dataLength > MAX_IN_MEMORY_BYTES) {
                        throw PtpMalformedPacketException(
                            "事务 0x${operationCode.toString(16)} 声明长度超出内存上限" +
                                "（${packet.dataLength} > $MAX_IN_MEMORY_BYTES）"
                        )
                    }
                    dataLength = packet.dataLength
                    started = true
                }
                is DataPacket -> {
                    requireTransactionId(packet.transactionId, tid, "Data")
                    if (!started || ended) {
                        throw PtpMalformedPacketException("Data outside active data phase for transaction $tid")
                    }
                    ensureTransactionBytes(operationCode, received, packet.payload.size, dataLength, output == null)
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
                    requireTransactionId(packet.transactionId, tid, "EndData")
                    if (!started || ended) {
                        throw PtpMalformedPacketException("Unexpected EndData for transaction $tid")
                    }
                    ensureTransactionBytes(operationCode, received, packet.payload.size, dataLength, output == null)
                    received += packet.payload.size
                    if (packet.payload.isNotEmpty()) {
                        if (output != null) {
                            output.write(packet.payload)
                        } else {
                            memoryBuffer?.writeBytes(packet.payload)
                        }
                    }
                    ended = true
                    onProgress.onDataLoaded(received, if (dataLength > 0) dataLength else received)
                }
                is OperationResponse -> {
                    requireTransactionId(packet.transactionId, tid, "OperationResponse")
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
                    if (started && !ended) {
                        throw PtpMalformedPacketException("Response arrived before EndData for transaction $tid")
                    }
                    if (started && dataLength > 0 && received != dataLength) {
                        // dataLength == 0 表示「链路未声明长度」而非「长度为 0」：
                        // USB 通路整条线上都没有这个字段，总长只能等收完才知道。
                        // 拿 0 当真实长度会把每一次 USB 数据事务都判成不匹配
                        throw PtpMalformedPacketException(
                            "事务 0x${operationCode.toString(16)} 长度不匹配：received=$received, declared=$dataLength"
                        )
                    }
                    output?.flush()
                    return memoryBuffer?.toByteArray() ?: ByteArray(0)
                }
                else -> continue
            }
        }
    }

    private fun requireTransactionId(actual: Long, expected: Long, packetName: String) {
        if (actual != expected) {
            throw PtpMalformedPacketException(
                "$packetName transaction id mismatch: expected=$expected, actual=$actual"
            )
        }
    }

    private fun ensureTransactionBytes(
        operationCode: Int,
        received: Long,
        incoming: Int,
        declared: Long,
        inMemory: Boolean,
    ) {
        val next = received + incoming.toLong()
        if (next < received) {
            throw PtpMalformedPacketException("Transaction byte count overflow")
        }
        if (declared > 0 && next > declared) {
            // declared == 0 是「链路未声明长度」（USB 通路），此时只能靠内存上限兜住
            throw PtpMalformedPacketException(
                "事务 0x${operationCode.toString(16)} 数据超过声明长度（$next > $declared）"
            )
        }
        if (inMemory && next > MAX_IN_MEMORY_BYTES) {
            throw PtpMalformedPacketException(
                "事务 0x${operationCode.toString(16)} 数据超出内存上限" +
                    "（$next > $MAX_IN_MEMORY_BYTES）——大对象必须走流式输出"
            )
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
