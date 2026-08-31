package com.imagedge.camera.ptp

import java.io.InputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP/IP 数据包（ISO 15740：8 字节头 + 负载，14 种包类型）
 *     version: 1.0
 * </pre>
 */

/** 数据阶段信息 */
object DataPhaseInfo {
    const val NO_DATA = 0x00000001          // 无数据阶段
    const val DATA_OUT = 0x00000002         // 发起方随后发送数据
}

/**
 * 单个 PTP/IP 包负载的最大字节数（64MB）。
 *
 * 长度字段是 UINT32，理论取值 0..4294967295。不设上限有两种致命后果：
 * 1. length > 2^31+8 时 `(length - 8).toInt()` 溢出为**负数** → ByteArray(负数)
 *    抛 NegativeArraySizeException（RuntimeException，上层按 PtpIoException /
 *    PtpMalformedPacketException 捕获时漏掉）→ 崩溃；
 * 2. length 在 8..2^31 之间时按声称长度一次性分配，最多 **2GB** → 直接 OOM。
 *
 * 触发场景极易出现：只要发生一次流错位（前包解析抛异常后残留字节、TCP 分片边界、
 * 相机异常包），下一个「长度字段」就会读到垃圾值。
 *
 * 64MB 对 PTP/IP 已是极大冗余——合法包里最大的通常是 GetObject 的单个 DataPacket
 * （索尼实测 64KB 级）与 GetAllDevicePropInfo（数百 KB）。
 */
const val MAX_PTP_PAYLOAD_BYTES = 64 * 1024 * 1024

/**
 * PTP/IP 数据包基类
 */
sealed class PtpIpPacket(val packetType: Int) {

    open fun writePayload(buffer: PtpBuffer) {}
    open fun readPayload(buffer: PtpBuffer) {}

    /** 序列化为完整字节流（含 8 字节头） */
    fun serialize(): ByteArray {
        val payload = PtpBuffer.writer().also { writePayload(it) }.toByteArray()
        return PtpBuffer.writer()
            .writeUInt32((payload.size + 8).toLong())
            .writeUInt32(packetType.toLong())
            .writeBytes(payload)
            .toByteArray()
    }

    companion object {
        const val TYPE_INIT_COMMAND_REQUEST = 0x00000001
        const val TYPE_INIT_COMMAND_ACK = 0x00000002
        const val TYPE_INIT_EVENT_REQUEST = 0x00000003
        const val TYPE_INIT_EVENT_ACK = 0x00000004
        const val TYPE_INIT_FAIL = 0x00000005
        const val TYPE_OPERATION_REQUEST = 0x00000006
        const val TYPE_OPERATION_RESPONSE = 0x00000007
        const val TYPE_EVENT = 0x00000008
        const val TYPE_START_DATA = 0x00000009
        const val TYPE_DATA = 0x0000000A
        const val TYPE_CANCEL = 0x0000000B
        const val TYPE_END_DATA = 0x0000000C
        const val TYPE_PROBE_REQUEST = 0x0000000D
        const val TYPE_PROBE_RESPONSE = 0x0000000E

        /** 从输入流读取一个完整包 */
        fun read(input: InputStream, onProgress: (Int) -> Unit = {}): PtpIpPacket {
            val header = PtpBuffer.writer().fill(input, 8)
            val length = header.readUInt32()
            val type = header.readUInt32().toInt()
            // 包长必须在 [8, 8 + MAX_PTP_PAYLOAD_BYTES] 内，否则按畸形包处理并中断流。
            // 不做这个校验会因整数溢出/巨量分配导致崩溃或 OOM（详见 MAX_PTP_PAYLOAD_BYTES 注释）。
            if (length < 8 || length > MAX_PTP_PAYLOAD_BYTES + 8L) {
                throw PtpMalformedPacketException(
                    "PTP/IP 包长度非法：$length（合法区间 8..${MAX_PTP_PAYLOAD_BYTES + 8L}）——流可能已错位"
                )
            }

            val packet: PtpIpPacket = when (type) {
                TYPE_INIT_COMMAND_REQUEST -> InitCommandRequest()
                TYPE_INIT_COMMAND_ACK -> InitCommandAck()
                TYPE_INIT_EVENT_REQUEST -> InitEventRequest()
                TYPE_INIT_EVENT_ACK -> InitEventAck()
                TYPE_INIT_FAIL -> InitFail()
                TYPE_OPERATION_REQUEST -> OperationRequest()
                TYPE_OPERATION_RESPONSE -> OperationResponse()
                TYPE_EVENT -> Event()
                TYPE_START_DATA -> StartData()
                TYPE_DATA -> DataPacket()
                TYPE_CANCEL -> Cancel()
                TYPE_END_DATA -> EndData()
                TYPE_PROBE_REQUEST -> ProbeRequest()
                TYPE_PROBE_RESPONSE -> ProbeResponse()
                else -> throw PtpMalformedPacketException("未知包类型：0x${type.toString(16)}")
            }

            val payload = PtpBuffer.writer().fill(input, (length - 8).toInt(), onProgress)
            packet.readPayload(payload)
            return packet
        }
    }
}

// ── 初始化包 ─────────────────────────────────────────────────────────

/** 命令连接初始化请求 */
class InitCommandRequest(
    var guid: ByteArray = ByteArray(16),
    var friendlyName: String = "",
    var versionMajor: Int = 1,
    var versionMinor: Int = 0
) : PtpIpPacket(TYPE_INIT_COMMAND_REQUEST) {
    override fun writePayload(buffer: PtpBuffer) {
        for (b in guid) buffer.writeUInt8(b.toInt() and 0xFF)
        buffer.writeUtf16String(friendlyName)
        buffer.writeUInt32((versionMinor + (versionMajor shl 16)).toLong())
    }

    override fun readPayload(buffer: PtpBuffer) {
        for (i in 0 until 16) guid[i] = buffer.readUInt8().toByte()
        friendlyName = buffer.readUtf16String()
        versionMinor = buffer.readUInt16()
        versionMajor = buffer.readUInt16()
    }
}

/** 命令连接初始化确认 */
class InitCommandAck(
    var connectionNumber: Long = 0,
    var responseData: InitCommandRequest = InitCommandRequest()
) : PtpIpPacket(TYPE_INIT_COMMAND_ACK) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt32(connectionNumber)
        responseData.writePayload(buffer)
    }

    override fun readPayload(buffer: PtpBuffer) {
        connectionNumber = buffer.readUInt32()
        responseData.readPayload(buffer)
    }
}

/** 事件连接初始化请求 */
class InitEventRequest(var connectionNumber: Long = 0) : PtpIpPacket(TYPE_INIT_EVENT_REQUEST) {
    override fun writePayload(buffer: PtpBuffer) { buffer.writeUInt32(connectionNumber) }
    override fun readPayload(buffer: PtpBuffer) { connectionNumber = buffer.readUInt32() }
}

/** 事件连接初始化确认（无负载） */
class InitEventAck : PtpIpPacket(TYPE_INIT_EVENT_ACK)

/** 初始化失败 */
class InitFail(var reason: Long = 0) : PtpIpPacket(TYPE_INIT_FAIL) {
    override fun writePayload(buffer: PtpBuffer) { buffer.writeUInt32(reason) }
    override fun readPayload(buffer: PtpBuffer) { reason = buffer.readUInt32() }
}

// ── 事务包 ───────────────────────────────────────────────────────────

/** 操作请求 */
class OperationRequest(
    var dataPhaseInfo: Int = DataPhaseInfo.NO_DATA,
    var operationCode: Int = 0,
    var transactionId: Long = 0,
    var parameters: LongArray = LongArray(0)
) : PtpIpPacket(TYPE_OPERATION_REQUEST) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt32(dataPhaseInfo.toLong())
        buffer.writeUInt16(operationCode)
        buffer.writeUInt32(transactionId)
        for (p in parameters) buffer.writeUInt32(p)
    }

    override fun readPayload(buffer: PtpBuffer) {
        dataPhaseInfo = buffer.readUInt32().toInt()
        operationCode = buffer.readUInt16()
        transactionId = buffer.readUInt32()
        if (buffer.remaining > 20 || buffer.remaining % 4 != 0) {
            throw PtpMalformedPacketException("OperationRequest 参数非法（剩 ${buffer.remaining} 字节）")
        }
        parameters = LongArray(buffer.remaining / 4) { buffer.readUInt32() }
    }
}

/** 操作响应 */
class OperationResponse(
    var responseCode: Int = 0,
    var transactionId: Long = 0,
    var parameters: LongArray = LongArray(0)
) : PtpIpPacket(TYPE_OPERATION_RESPONSE) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt16(responseCode)
        buffer.writeUInt32(transactionId)
        for (p in parameters) buffer.writeUInt32(p)
    }

    override fun readPayload(buffer: PtpBuffer) {
        responseCode = buffer.readUInt16()
        transactionId = buffer.readUInt32()
        if (buffer.remaining > 20 || buffer.remaining % 4 != 0) {
            throw PtpMalformedPacketException("OperationResponse 参数非法（剩 ${buffer.remaining} 字节）")
        }
        parameters = LongArray(buffer.remaining / 4) { buffer.readUInt32() }
    }
}

/** 事件 */
class Event(
    var eventCode: Int = 0,
    var transactionId: Long = 0,
    var parameters: LongArray = LongArray(0)
) : PtpIpPacket(TYPE_EVENT) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt16(eventCode)
        buffer.writeUInt32(transactionId)
        for (p in parameters) buffer.writeUInt32(p)
    }

    override fun readPayload(buffer: PtpBuffer) {
        eventCode = buffer.readUInt16()
        transactionId = buffer.readUInt32()
        if (buffer.remaining > 12 || buffer.remaining % 4 != 0) {
            throw PtpMalformedPacketException("Event 参数非法（剩 ${buffer.remaining} 字节）")
        }
        parameters = LongArray(buffer.remaining / 4) { buffer.readUInt32() }
    }
}

/** 数据阶段开始 */
class StartData(var transactionId: Long = 0, var dataLength: Long = 0) : PtpIpPacket(TYPE_START_DATA) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt32(transactionId)
        buffer.writeUInt64(dataLength)
    }

    override fun readPayload(buffer: PtpBuffer) {
        transactionId = buffer.readUInt32()
        dataLength = buffer.readUInt64()
    }
}

/** 数据包 */
class DataPacket(var transactionId: Long = 0, var payload: ByteArray = ByteArray(0)) : PtpIpPacket(TYPE_DATA) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt32(transactionId)
        buffer.writeBytes(payload)
    }

    override fun readPayload(buffer: PtpBuffer) {
        transactionId = buffer.readUInt32()
        payload = buffer.readRemaining()
    }
}

/** 数据阶段结束 */
class EndData(var transactionId: Long = 0, var payload: ByteArray = ByteArray(0)) : PtpIpPacket(TYPE_END_DATA) {
    override fun writePayload(buffer: PtpBuffer) {
        buffer.writeUInt32(transactionId)
        buffer.writeBytes(payload)
    }

    override fun readPayload(buffer: PtpBuffer) {
        transactionId = buffer.readUInt32()
        payload = buffer.readRemaining()
    }
}

/** 取消 */
class Cancel(var transactionId: Long = 0) : PtpIpPacket(TYPE_CANCEL) {
    override fun writePayload(buffer: PtpBuffer) { buffer.writeUInt32(transactionId) }
    override fun readPayload(buffer: PtpBuffer) { transactionId = buffer.readUInt32() }
}

// ── 探测包 ───────────────────────────────────────────────────────────

/** 探测请求（无负载） */
class ProbeRequest : PtpIpPacket(TYPE_PROBE_REQUEST)

/** 探测响应（无负载） */
class ProbeResponse : PtpIpPacket(TYPE_PROBE_RESPONSE)
