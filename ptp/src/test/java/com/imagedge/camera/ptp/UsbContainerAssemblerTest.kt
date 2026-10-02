package com.imagedge.camera.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : USB 批量传输字节流的容器切分
 * </pre>
 *
 * 批量传输不按消息分帧——一次 `bulkTransfer` 可能带来半个容器、一个半容器、
 * 或两个整容器。这三种切法都跑一遍，因为「按一次传输当一个容器」正是想避开的写法。
 */
class UsbContainerAssemblerTest {

    private fun container(operationCode: Int, transactionId: Int): ByteArray =
        PtpContainerCodec.encode(
            OperationRequest(operationCode = operationCode, transactionId = transactionId.toLong())
        )

    @Test
    fun `a whole container arriving in one chunk is returned as-is`() {
        val assembler = UsbContainerAssembler()

        assembler.feed(container(0x1001, 1))

        val out = assertNotNullBytes(assembler.next())
        assertArrayEquals(container(0x1001, 1), out)
        assertNull(assembler.next())
    }

    @Test
    fun `a container split across chunks is reassembled`() {
        val assembler = UsbContainerAssembler()
        val whole = container(0x9205, 7)

        // 头只到一半
        assembler.feed(whole.copyOfRange(0, 3))
        assertNull(assembler.next())
        // 补齐剩余
        assembler.feed(whole.copyOfRange(3, whole.size))

        assertArrayEquals(whole, assertNotNullBytes(assembler.next()))
    }

    @Test
    fun `two containers in one chunk come out one at a time`() {
        val assembler = UsbContainerAssembler()
        val first = container(0x1001, 1)
        val second = container(0x1002, 2)

        assembler.feed(first + second)

        assertArrayEquals(first, assertNotNullBytes(assembler.next()))
        assertArrayEquals(second, assertNotNullBytes(assembler.next()))
        assertNull(assembler.next())
    }

    @Test
    fun `a chunk ending mid-container does not corrupt the next one`() {
        // 「一个半容器」：第一段吞掉第一个容器的大部分和第二个容器的头
        val assembler = UsbContainerAssembler()
        val first = container(0x1001, 1)
        val second = container(0x9207, 2)
        val combined = first + second

        assembler.feed(combined.copyOfRange(0, first.size + 4))

        assertArrayEquals(first, assertNotNullBytes(assembler.next()))
        assertNull(assembler.next())          // 第二个还差 4 字节
        assembler.feed(combined.copyOfRange(first.size + 4, combined.size))
        assertArrayEquals(second, assertNotNullBytes(assembler.next()))
    }

    @Test
    fun `byte by byte delivery still yields the whole container`() {
        val assembler = UsbContainerAssembler()
        val whole = container(0x9205, 9)

        whole.forEach { assembler.feed(byteArrayOf(it)) }

        assertArrayEquals(whole, assertNotNullBytes(assembler.next()))
    }

    @Test
    fun `a short header alone yields nothing until it is complete`() {
        val assembler = UsbContainerAssembler()
        assembler.feed(ByteArray(HEADER_BYTES - 1))

        assertNull(assembler.next())
    }

    @Test
    fun `an impossible declared length is rejected and the buffer is dropped`() {
        val assembler = UsbContainerAssembler()
        // 头必须先到齐才谈得上读长度；不足 8 字节时只能等，这是字节流的固有约束
        assembler.feed(
            ByteArray(HEADER_BYTES).also {
                it[0] = 0xFF.toByte(); it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte(); it[3] = 0x7F.toByte()
            }
        )

        try {
            assembler.next()
            throw AssertionError("超长声明应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("声明长度"))
        }
        // 不清缓冲的话，下一次读会卡在同一个读不动的头上直到超时
        assertEquals(0, assembler.bufferedBytes)
    }

    @Test
    fun `a length smaller than the header is rejected`() {
        val assembler = UsbContainerAssembler()
        assembler.feed(byteArrayOf(2, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0))

        try {
            assembler.next()
            throw AssertionError("比头还短的声明应当抛错")
        } catch (e: PtpMalformedPacketException) {
            assertTrue(e.message!!.contains("声明长度"))
        }
    }

    @Test
    fun `reset drops buffered bytes`() {
        val assembler = UsbContainerAssembler()
        val whole = container(0x1001, 1)
        assembler.feed(whole.copyOfRange(0, 5))

        assembler.reset()

        assertEquals(0, assembler.bufferedBytes)
        assertNull(assembler.next())
    }

    @Test
    fun `feed honours the length argument instead of the array size`() {
        // bulkTransfer 的缓冲区通常是预分配的 16KB，实际只填了前几十字节。
        // 按整个数组喂进去会把后面那一片零当成数据喂进来
        val assembler = UsbContainerAssembler()
        val whole = container(0x1001, 1)
        val oversized = ByteArray(16384)
        System.arraycopy(whole, 0, oversized, 0, whole.size)

        assembler.feed(oversized, whole.size)

        assertArrayEquals(whole, assertNotNullBytes(assembler.next()))
        assertNull(assembler.next())
    }

    @Test
    fun `assembled container round-trips through the codec`() {
        val assembler = UsbContainerAssembler()
        val whole = PtpContainerCodec.encode(
            DataPacket(transactionId = 4, payload = ByteArray(500) { (it % 253).toByte() })
        )

        assembler.feed(whole.copyOfRange(0, 100))
        assembler.feed(whole.copyOfRange(100, whole.size))

        val decoded = PtpContainerCodec.toPacket(
            PtpContainerCodec.decode(assertNotNullBytes(assembler.next()))
        )
        assertTrue(decoded is DataPacket)
        assertEquals(500, (decoded as DataPacket).payload.size)
    }

    private fun assertNotNullBytes(value: ByteArray?): ByteArray =
        value ?: throw AssertionError("期望切出容器，实际为 null")
}