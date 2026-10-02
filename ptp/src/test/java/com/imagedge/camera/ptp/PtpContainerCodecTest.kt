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
 * 布局按官方实现核对过：头 12 字节（长度/类型/码/事务 ID），
 * **数据容器的负载就是剩下的全部字节，里面没有长度字段**。
 */
class PtpContainerCodecTest {

    /** 按官方布局拼一个容器 */
    private fun container(type: Int, code: Int, transactionId: Long, payload: ByteArray): ByteArray =
        PtpBuffer.writer()
            .writeUInt32((HEADER_BYTES + payload.size).toLong())
            .writeUInt16(type)
            .writeUInt16(code)
            .writeUInt32(transactionId)
            .writeBytes(payload)
            .toByteArray()

    private fun params(vararg values: Long): ByteArray =
        PtpBuffer.writer().apply { values.forEach { writeUInt32(it) } }.toByteArray()

    @Test
    fun `container header is twelve bytes with the transaction id inside it`() {
        val bytes = PtpContainerCodec.encode(
            OperationRequest(operationCode = 0x9207, transactionId = 7)
        )

        assertEquals(PtpContainerType.COMMAND, (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8))
        // 码必须容得下 16 位操作码：按 1 字节写会把 0x9207 截成 0x07，事务打到别的操作上
        assertEquals(0x9207, (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8))
        assertEquals(7L, readUInt32(bytes, 8))
    }

    @Test
    fun `operation request round-trips`() {
        val request = OperationRequest(
            operationCode = 0x9207,
            transactionId = 7,
            parameters = longArrayOf(0xD2C1)
        )

        val decoded = PtpContainerCodec.toPacket(
            PtpContainerCodec.decode(PtpContainerCodec.encode(request))
        ) as OperationRequest

        assertEquals(0x9207, decoded.operationCode)
        assertEquals(7L, decoded.transactionId)
        assertArrayEquals(longArrayOf(0xD2C1), decoded.parameters)
    }

    @Test
    fun `data container payload is exactly the bytes after the header`() {
        // 数据容器里没有长度字段：官方实现把 payload 原样放进容器，
        // 收方一路累积到响应容器为止。曾经在这里多读 4 字节，
        // 结果是每个数据包都凭空少掉真实负载的头 4 个字节
        val payload = ByteArray(64) { (it % 251).toByte() }

        val decoded = PtpContainerCodec.decode(
            container(PtpContainerType.DATA, 0x9205, 3, payload)
        )

        assertEquals(3L, decoded.transactionId)
        assertEquals(64, decoded.payload.size)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `data container declares a length that is header plus payload only`() {
        val bytes = PtpContainerCodec.encode(DataPacket(transactionId = 4, payload = ByteArray(10)))

        // 12 头 + 10 负载。多一个字节就说明又塞进去了不该有的长度字段
        assertEquals(22L, readUInt32(bytes, 0))
        assertEquals(22, bytes.size)
    }

    @Test
    fun `data packet round-trips through encode and decode`() {
        val payload = ByteArray(300) { (it % 253).toByte() }

        val decoded = PtpContainerCodec.toPacket(
            PtpContainerCodec.decode(
                PtpContainerCodec.encode(DataPacket(transactionId = 4, payload = payload))
            )
        ) as DataPacket

        assertEquals(4L, decoded.transactionId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `response container decodes code transaction id and parameters`() {
        val decoded = PtpContainerCodec.toPacket(
            PtpContainerCodec.decode(
                container(PtpContainerType.RESPONSE, 0x2001, 9, params(0xF10001, 2))
            )
        ) as OperationResponse

        assertEquals(0x2001, decoded.responseCode)
        assertEquals(9L, decoded.transactionId)
        assertArrayEquals(longArrayOf(0xF10001, 2), decoded.parameters)
    }

    @Test
    fun `event container decodes`() {
        val decoded = PtpContainerCodec.toPacket(
            PtpContainerCodec.decode(container(PtpContainerType.EVENT, 0x400A, 11, params(0xABCD)))
        ) as Event

        assertEquals(0x400A, decoded.eventCode)
        assertEquals(11L, decoded.transactionId)
        assertArrayEquals(longArrayOf(0xABCD), decoded.parameters)
    }

    @Test
    fun `empty parameter list round-trips`() {
        val decoded = PtpContainerCodec.toPacket(
            PtpContainerCodec.decode(PtpContainerCodec.encode(OperationRequest(operationCode = 0x1001, transactionId = 1)))
        ) as OperationRequest

        assertTrue(decoded.parameters.isEmpty())
    }

    @Test
    fun `handshake packets are rejected because usb has no such step`() {
        listOf(InitCommandRequest(), InitEventRequest(), ProbeRequest()).forEach { packet ->
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
        try {
            PtpContainerCodec.toPacket(UsbContainer(99, 0, 1, ByteArray(0)))
            throw AssertionError("未知容器类型应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("未知容器类型"))
        }
    }

    @Test
    fun `parameters that are not a whole number of words are rejected`() {
        try {
            PtpContainerCodec.toPacket(
                UsbContainer(PtpContainerType.COMMAND, 0x1001, 1, ByteArray(3))
            )
            throw AssertionError("参数字节数不对齐应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("参数非法"))
        }
    }

    @Test
    fun `a hostile declared length is rejected before any allocation`() {
        val raw = ByteArray(HEADER_BYTES).also {
            it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte(); it[3] = 0x7F.toByte()
        }

        try {
            PtpContainerCodec.decode(raw)
            throw AssertionError("超长声明应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("声明长度"))
        }
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}