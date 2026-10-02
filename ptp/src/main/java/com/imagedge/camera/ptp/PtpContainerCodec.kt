package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : USB 上的 PTP 容器编解码（MTP 容器 ↔ PtpIpPacket）
 *     version: 2.0 —— 按官方实现校正数据容器的载荷布局
 * </pre>
 */

/** 容器类型 */
object PtpContainerType {
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4
}

/**
 * 容器头长度：长度(4) + 类型(2) + 码(2) + 事务 ID(4) = 12。
 *
 * 事务 ID 属于**容器头**而不是载荷——PTP/IP 把它放在包载荷开头，这里放在头里，
 * 线上字节一致，差别只在建模边界。
 *
 * 类型与码都是 16 位：操作码、响应码、事件码本身就是 16 位的，
 * 按 1 字节写会把 0x9207 截成 0x07，事务打到另一个操作上去且不报错。
 */
const val HEADER_BYTES = 12

/** 单个容器允许的最大长度。长文件按多个数据容器分片，不会触到这个上限。 */
private const val MAX_CONTAINER_BYTES = 64 * 1024 * 1024

/**
 * 一个 USB 容器，按官方布局忠实还原。
 *
 * @param code 命令/响应/事件时为操作码/响应码/事件码；**数据容器时也是操作码**——
 *        数据容器没有别的标识可用，数据阶段的归属就靠它
 * @param payload 命令/响应/事件时是参数（4 字节整数序列）；数据容器时是原始数据
 */
data class UsbContainer(
    val type: Int,
    val code: Int,
    val transactionId: Long,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is UsbContainer && type == other.type && code == other.code &&
            transactionId == other.transactionId && payload.contentEquals(other.payload))

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + code
        result = 31 * result + transactionId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * PTP 容器编解码。
 *
 * 只做字节翻译，不碰 IO——所以它可以在纯 JVM 上把每一种包型往返一遍，
 * 这正是 USB 通路唯一能在没有相机的情况下验证的部分。
 *
 * **数据容器里没有长度字段。** 整条 USB 通路没有任何地方声明「这次数据阶段有多长」：
 * 接收方把数据容器一路累积直到收到响应容器，总长就是已收长度。
 * PTP/IP 用 StartData 声明长度、用 EndData 收尾，那两个包在 USB 上不存在；
 * 需要它们的调用方自行合成，见 `UsbPtpCommandChannel`。
 */
object PtpContainerCodec {

    /**
     * 把一个包编码成 USB 容器字节。
     *
     * @throws IllegalArgumentException 该包在 USB 上没有对应形态（初始化/探测包属于
     *         PTP/IP 独有的握手，USB 链路没有这一步）
     */
    fun encode(packet: PtpIpPacket): ByteArray = when (packet) {
        is OperationRequest -> wrap(
            PtpContainerType.COMMAND,
            packet.operationCode,
            packet.transactionId,
            encodeParameters(packet.parameters)
        )
        // StartData/EndData 在 USB 上没有对应物，它们带的字节由通道在合成时补；
        // 真走到这里时没有负载可发
        is StartData -> wrap(PtpContainerType.DATA, DATA_OPERATION_CODE, packet.transactionId, ByteArray(0))
        is DataPacket -> wrap(PtpContainerType.DATA, DATA_OPERATION_CODE, packet.transactionId, packet.payload)
        is EndData -> wrap(PtpContainerType.DATA, DATA_OPERATION_CODE, packet.transactionId, packet.payload)
        is Event -> wrap(
            PtpContainerType.EVENT,
            packet.eventCode,
            packet.transactionId,
            encodeParameters(packet.parameters)
        )
        else -> throw IllegalArgumentException(
            "${packet::class.simpleName} 是 PTP/IP 独有的握手/响应包，USB 链路不需要它"
        )
    }

    /** 解出一个容器。 */
    fun decode(container: ByteArray): UsbContainer {
        if (container.size < HEADER_BYTES) {
            throw PtpMalformedPacketException("容器长度不足头部长度：${container.size}")
        }
        val declaredLength = readUInt32(container, 0)
        if (declaredLength < HEADER_BYTES || declaredLength > MAX_CONTAINER_BYTES) {
            throw PtpMalformedPacketException(
                "容器声明长度非法：$declaredLength（合法区间 $HEADER_BYTES..$MAX_CONTAINER_BYTES）"
            )
        }
        if (container.size < declaredLength) {
            throw PtpMalformedPacketException(
                "容器不完整：声明 $declaredLength 字节，实际只有 ${container.size}"
            )
        }
        return UsbContainer(
            type = readUInt16(container, 4),
            code = readUInt16(container, 6),
            transactionId = readUInt32(container, 8),
            payload = container.copyOfRange(HEADER_BYTES, declaredLength.toInt())
        )
    }

    /**
     * 把容器变成 PTP 包。
     *
     * 数据容器逐个变成 [DataPacket]，**不带长度也不带结束标记**——那两样在 USB 上不存在，
     * 由持有跨容器状态的调用方合成。
     */
    fun toPacket(container: UsbContainer): PtpIpPacket = when (container.type) {
        PtpContainerType.COMMAND -> OperationRequest(
            dataPhaseInfo = DataPhaseInfo.NO_DATA,
            operationCode = container.code,
            transactionId = container.transactionId,
            parameters = readParameters(container.payload)
        )
        PtpContainerType.RESPONSE -> OperationResponse(
            responseCode = container.code,
            transactionId = container.transactionId,
            parameters = readParameters(container.payload)
        )
        PtpContainerType.EVENT -> Event(
            eventCode = container.code,
            transactionId = container.transactionId,
            parameters = readParameters(container.payload)
        )
        PtpContainerType.DATA -> DataPacket(container.transactionId, container.payload)
        else -> throw PtpMalformedPacketException("未知容器类型：${container.type}")
    }

    /**
     * 参数区必须是 4 字节的整数倍且不超过 5 个参数。
     *
     * 数据容器的负载是原始数据而非参数，不走这里。
     */
    private fun readParameters(payload: ByteArray): LongArray {
        if (payload.size % 4 != 0 || payload.size > 20) {
            throw PtpMalformedPacketException("容器参数非法（${payload.size} 字节）")
        }
        val buffer = PtpBuffer.reader(payload)
        return LongArray(payload.size / 4) { buffer.readUInt32() }
    }

    private fun encodeParameters(values: LongArray): ByteArray =
        PtpBuffer.writer().apply { values.forEach { writeUInt32(it) } }.toByteArray()

    private fun wrap(type: Int, code: Int, transactionId: Long, payload: ByteArray): ByteArray {
        val total = HEADER_BYTES + payload.size
        if (total > MAX_CONTAINER_BYTES) {
            throw IllegalArgumentException("容器超出上限：$total > $MAX_CONTAINER_BYTES")
        }
        return PtpBuffer.writer()
            .writeUInt32(total.toLong())
            .writeUInt16(type)
            .writeUInt16(code)
            .writeUInt32(transactionId)
            .writeBytes(payload)
            .toByteArray()
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    /**
     * 写出数据容器时代填的「操作码」。
     *
     * 官方实现把本次操作的操作码放在这里，标记这段数据属于哪个事务。本类在写侧
     * 拿不到那个操作码（PTP 包本身不携带），而相机按**事务 ID** 归并数据阶段，
     * 所以填 0 即可：事务 ID 一致时相机会正确归并。
     */
    private const val DATA_OPERATION_CODE = 0
}