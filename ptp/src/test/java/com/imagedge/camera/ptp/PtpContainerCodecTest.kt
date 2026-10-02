package com.imagedge.camera.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : USB 容器编解码往返
 * </pre>
 *
 * USB 通路在没有相机时唯一能被验证的部分就是这层翻译，所以往返覆盖到每一种包型。
 */
class PtpContainerCodecTest {

    @Test
    fun `operation request round-trips`() {
        val request = OperationRequest(
            dataPhaseInfo = DataPhaseInfo.DATA_OUT,
            operationCode = 0x9207,
            transactionId = 7,
            parameters = longArrayOf(0xD2C1)
        )

        val decoded = PtpContainerCodec.decode(PtpContainerCodec.encode(request))

        val op = decoded as OperationRequest
        assertEquals(0x9207, op.operationCode)
        assertEquals(7L, op.transactionId)
        assertArrayEquals(longArrayOf(0xD2C1), op.parameters)
    }

    @Test
    fun `command container puts the operation code in the header code word`() {
        val bytes = PtpContainerCodec.encode(
            OperationRequest(operationCode = 0x9207, transactionId = 1)
        )

        // 头是 长度(4) + 类型(2) + 码(2)。码必须容得下 16 位操作码——
        // 按 1 字节写会把 0x9207 截成 0x07，事务打到另一个操作上去
        assertEquals(PtpContainerType.COMMAND, bytes[4].toInt() and 0xFF)
        assertEquals(0x9207, (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8))
    }

    @Test
    fun `start data round-trips with its declared length`() {
        val start = StartData(transactionId = 3, dataLength = 2L)

        val decoded = PtpContainerCodec.decode(PtpContainerCodec.encode(start)) as StartData

        assertEquals(3L, decoded.transactionId)
        assertEquals(2L, decoded.dataLength)
    }

    @Test
    fun `data packet round-trips its payload`() {
        val payload = ByteArray(300) { (it % 251).toByte() }

        val decoded = PtpContainerCodec.decode(
            PtpContainerCodec.encode(DataPacket(transactionId = 4, payload = payload))
        ) as DataPacket

        assertEquals(4L, decoded.transactionId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `end data round-trips and stays distinguishable from a plain data packet`() {
        val end = PtpContainerCodec.encode(EndData(transactionId = 5, payload = byteArrayOf(1, 2)))

        // 端点：容器类型仍是数据，靠码字段里的数据类型区分——这正是它必须能还原的原因
        assertEquals(PtpContainerType.DATA, end[4].toInt() and 0xFF)
        assertEquals(PtpDataType.DATA_END, end[6].toInt() and 0xFF)

        val decoded = PtpContainerCodec.decode(end)
        assertTrue(decoded is EndData)
        assertEquals(5L, (decoded as EndData).transactionId)
    }

    @Test
    fun `response container decodes code transaction id and parameters`() {
        // 相机构造：长度(4) + 类型=响应(2) + 码(2) + 事务 ID(4) + 参数(4×2)
        val raw = PtpBuffer.writer()
            .writeUInt32(20)
            .writeUInt16(PtpContainerType.RESPONSE)
            .writeUInt16(0x2001)       // OK
            .writeUInt32(9)
            .writeUInt32(0xF10001)
            .writeUInt32(2)
            .toByteArray()

        val response = PtpContainerCodec.decode(raw) as OperationResponse

        assertEquals(0x2001, response.responseCode)
        assertEquals(9L, response.transactionId)
        assertArrayEquals(longArrayOf(0xF10001, 2), response.parameters)
    }

    @Test
    fun `event container decodes`() {
        val raw = PtpBuffer.writer()
            .writeUInt32(16)
            .writeUInt16(PtpContainerType.EVENT)
            .writeUInt16(0x400A)       // CaptureComplete
            .writeUInt32(11)
            .writeUInt32(0xABCD)
            .toByteArray()

        val event = PtpContainerCodec.decode(raw) as Event

        assertEquals(0x400A, event.eventCode)
        assertEquals(11L, event.transactionId)
        assertArrayEquals(longArrayOf(0xABCD), event.parameters)
    }

    @Test
    fun `the first data container decodes as StartData while continuations do not`() {
        // 数据类型字段是唯一区分手段：首个容器带长度声明，之后的只带负载
        val first = PtpBuffer.writer()
            .writeUInt32(16)
            .writeUInt16(PtpContainerType.DATA)
            .writeUInt16(PtpDataType.DATA_INIT)
            .writeUInt32(12)
            .writeUInt32(2048)
            .toByteArray()
        val rest = PtpBuffer.writer()
            .writeUInt32(16)          // 8 头 + 4 事务 + 4 长度（后续容器的长度字段为 0）
            .writeUInt16(PtpContainerType.DATA)
            .writeUInt16(PtpDataType.DATA)
            .writeUInt32(12)
            .writeUInt32(0)
            .toByteArray()

        val decodedFirst = PtpContainerCodec.decode(first)
        val decodedRest = PtpContainerCodec.decode(rest)

        assertTrue(decodedFirst is StartData)
        assertEquals(2048L, (decodedFirst as StartData).dataLength)
        assertTrue(decodedRest is DataPacket)
    }

    @Test
    fun `handshake packets are rejected because usb has no such step`() {
        val rejected = listOf(
            InitCommandRequest(),
            InitEventRequest(),
            ProbeRequest()
        )
        rejected.forEach { packet ->
            try {
                PtpContainerCodec.encode(packet)
                throw AssertionError("${packet::class.simpleName} 不该能被编码成 USB 容器")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("PTP/IP"))
            }
        }
    }

    @Test
    fun `truncated container is rejected instead of parsed from partial bytes`() {
        val full = PtpContainerCodec.encode(
            OperationRequest(operationCode = 0x9205, transactionId = 2, parameters = longArrayOf(0xD23F))
        )

        try {
            PtpContainerCodec.decode(full.copyOf(full.size - 3))
            throw AssertionError("被截断的容器应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("不完整"))
        }
    }

    @Test
    fun `a container shorter than its header is rejected`() {
        try {
            PtpContainerCodec.decode(ByteArray(6))
            throw AssertionError("比头还短的容器应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("头部"))
        }
    }

    @Test
    fun `an unknown container type is rejected`() {
        val raw = PtpBuffer.writer()
            .writeUInt32(8)
            .writeUInt16(99)
            .writeUInt16(0)
            .toByteArray()

        try {
            PtpContainerCodec.decode(raw)
            throw AssertionError("未知容器类型应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("未知容器类型"))
        }
    }

    @Test
    fun `parameters that are not a whole number of words are rejected`() {
        // 参数区必须是 4 字节的整数倍，否则后续字段会整体错位
        val raw = PtpBuffer.writer()
            .writeUInt32(15)           // 8 头 + 4 事务 ID + 3 字节不对齐的参数
            .writeUInt16(PtpContainerType.COMMAND)
            .writeUInt16(0x1001)
            .writeUInt32(1)
            .writeUInt8(1)
            .writeUInt8(2)
            .writeUInt8(3)
            .toByteArray()

        try {
            PtpContainerCodec.decode(raw)
            throw AssertionError("参数字节数不对齐应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("参数非法"))
        }
    }

    @Test
    fun `a hostile declared length is rejected before any allocation`() {
        val raw = ByteArray(8).also {
            it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte(); it[3] = 0x7F.toByte()
        }

        try {
            PtpContainerCodec.decode(raw)
            throw AssertionError("超长声明应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("声明长度"))
        }
    }
}