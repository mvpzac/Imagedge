package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : USB 上的 PTP 容器编解码（MTP 容器 ↔ PtpIpPacket）
 *     version: 1.0
 * </pre>
 */

/**
 * 容器类型。
 *
 * 与 PTP/IP 的差别在于**载荷布局也不同**，不是套一层壳就完事：
 * PTP/IP 的操作请求载荷以 4 字节数据阶段标记开头，USB 容器的操作码/响应码/事件码
 * 放在头部 Code 字节里，载荷直接是 `事务 ID + 参数`；数据容器的长度字段也
 * 从 PTP/IP 的 8 字节收窄成 4 字节。因此这里做的是真正的翻译，而不是重新包装。
 */
object PtpContainerType {
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4
}

/** 数据容器的数据类型（存放在容器的 Code 字段里） */
object PtpDataType {
    /** 首个数据容器，带数据长度声明 */
    const val DATA_INIT = 1

    /** 后续数据容器，只带负载 */
    const val DATA = 2

    /** 数据阶段结束 */
    const val DATA_END = 3
}

/**
 * 容器固定头：长度(4) + 类型(2) + 码(2) = 8 字节。
 *
 * 类型与码都是 16 位——操作码、响应码、事件码本身就是 16 位的，
 * 按 1 字节写会直接截断（0x9207 变成 0x07），事务会打到另一个操作上去。
 */
const val HEADER_BYTES = 8

private const val CONTAINER_HEADER_BYTES = HEADER_BYTES

/** 单个容器允许的最大长度。长文件按多个数据容器分片，不会触到这个上限。 */
private const val MAX_CONTAINER_BYTES = 64 * 1024 * 1024

/**
 * PTP 容器编解码。
 *
 * 只做字节翻译，不碰 IO——所以它可以在纯 JVM 上把每一种包往返一遍，
 * 这正是 USB 通路唯一能在没有相机的情况下验证的部分。
 */
object PtpContainerCodec {

    /**
     * 把一个包编码成 USB 容器字节。
     *
     * @throws IllegalArgumentException 该包在 USB 上没有对应形态（初始化/探测包属于
     *         PTP/IP 独有的握手，USB 链路没有这一步）
     */
    fun encode(packet: PtpIpPacket): ByteArray = when (packet) {
        is OperationRequest -> encodeCommand(packet)
        is StartData -> encodeData(PtpDataType.DATA_INIT, packet.transactionId, packet.dataLength, null)
        is DataPacket -> encodeData(PtpDataType.DATA, packet.transactionId, 0L, packet.payload)
        is EndData -> encodeData(PtpDataType.DATA_END, packet.transactionId, 0L, packet.payload)
        else -> throw IllegalArgumentException(
            "${packet::class.simpleName} 是 PTP/IP 独有的握手/响应包，USB 链路不需要它"
        )
    }

    /**
     * 从容器字节解出一个包。
     *
     * @param container 完整的容器（含 6 字节头）
     * @return 解析出的包；容器类型未知时抛错
     */
    fun decode(container: ByteArray): PtpIpPacket {
        if (container.size < CONTAINER_HEADER_BYTES) {
            throw PtpMalformedPacketException("容器长度不足头部长度：${container.size}")
        }
        val declaredLength = readUInt32(container, 0)
        if (declaredLength < CONTAINER_HEADER_BYTES || declaredLength > MAX_CONTAINER_BYTES) {
            throw PtpMalformedPacketException(
                "容器声明长度非法：$declaredLength（合法区间 $CONTAINER_HEADER_BYTES..$MAX_CONTAINER_BYTES）"
            )
        }
        // 读到的字节数多于自己声明的长度：多半是多读进来了下一段，按畸形处理而不是照用
        if (container.size < declaredLength) {
            throw PtpMalformedPacketException(
                "容器不完整：声明 $declaredLength 字节，实际只有 ${container.size}"
            )
        }
        val type = readUInt16(container, 4)
        val code = readUInt16(container, 6)
        val body = PtpBuffer.reader(container, CONTAINER_HEADER_BYTES, (declaredLength - CONTAINER_HEADER_BYTES).toInt())

        return when (type) {
            PtpContainerType.COMMAND -> OperationRequest(
                dataPhaseInfo = DataPhaseInfo.NO_DATA,
                operationCode = code,
                transactionId = body.readTransactionId(),
                parameters = body.readParameters()
            )
            PtpContainerType.RESPONSE -> OperationResponse(
                responseCode = code,
                transactionId = body.readTransactionId(),
                parameters = body.readParameters()
            )
            PtpContainerType.EVENT -> Event(
                eventCode = code,
                transactionId = body.readTransactionId(),
                parameters = body.readParameters()
            )
            PtpContainerType.DATA -> decodeData(code, body)
            else -> throw PtpMalformedPacketException("未知容器类型：$type")
        }
    }

    private fun decodeData(dataType: Int, body: PtpBuffer): PtpIpPacket {
        val transactionId = body.readTransactionId()
        // 长度字段每个数据容器都有，必须先吃掉。不读的话它会混进负载里，
        // 表现为每个数据包凭空多出 4 个字节——那是靠偏移传染的错，后面全错位
        val dataLength = body.readUInt32()
        return when (dataType) {
            PtpDataType.DATA_END -> EndData(transactionId, body.readRemaining())
            // 首个容器带长度声明，之后的只带负载——PTP/IP 用 StartData/Data 两个包型
            // 区分，USB 只靠数据类型是不是「首个」
            PtpDataType.DATA_INIT -> StartData(transactionId, dataLength)
            PtpDataType.DATA -> DataPacket(transactionId, body.readRemaining())
            else -> throw PtpMalformedPacketException("未知数据类型：$dataType")
        }
    }

    private fun encodeCommand(request: OperationRequest): ByteArray {
        val payload = PtpBuffer.writer()
            .writeUInt32(request.transactionId)
            .apply { request.parameters.forEach { writeUInt32(it) } }
            .toByteArray()
        return wrap(PtpContainerType.COMMAND, request.operationCode, payload)
    }

    private fun encodeData(
        dataType: Int,
        transactionId: Long,
        dataLength: Long,
        payload: ByteArray?
    ): ByteArray {
        val body = PtpBuffer.writer()
            .writeUInt32(transactionId)
            .writeUInt32(dataLength)
            .apply { if (payload != null) writeBytes(payload) }
            .toByteArray()
        // 数据类型放在码字段——它不是操作码，而是这次数据阶段的角色标记
        return wrap(PtpContainerType.DATA, dataType, body)
    }

    private fun wrap(type: Int, code: Int, body: ByteArray): ByteArray {
        val total = CONTAINER_HEADER_BYTES + body.size
        if (total > MAX_CONTAINER_BYTES) {
            throw IllegalArgumentException("容器超出上限：$total > $MAX_CONTAINER_BYTES")
        }
        return PtpBuffer.writer()
            .writeUInt32(total.toLong())
            .writeUInt16(type)
            .writeUInt16(code)
            .writeBytes(body)
            .toByteArray()
    }

    private fun PtpBuffer.readTransactionId(): Long = readUInt32()

    /** 剩余字节必须是 4 的倍数且不超过 5 个参数，与 PTP/IP 侧同款约束。 */
    private fun PtpBuffer.readParameters(): LongArray {
        if (remaining % 4 != 0 || remaining > 20) {
            throw PtpMalformedPacketException("容器参数非法（剩 $remaining 字节）")
        }
        return LongArray(remaining / 4) { readUInt32() }
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}