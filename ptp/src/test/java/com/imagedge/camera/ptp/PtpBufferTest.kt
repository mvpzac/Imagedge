package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PtpBuffer 编解码单元测试
 *     version: 1.0
 * </pre>
 */
class PtpBufferTest {

    @Test
    fun uintRoundtrip() {
        val writer = PtpBuffer.writer()
            .writeUInt8(0xAB)
            .writeUInt16(0x1234)
            .writeUInt32(0x89ABCDEFL)
            .writeUInt64(0x0123456789ABCDEFL)

        val reader = PtpBuffer.reader(writer.toByteArray())
        assertEquals(0xAB, reader.readUInt8())
        assertEquals(0x1234, reader.readUInt16())
        assertEquals(0x89ABCDEFL, reader.readUInt32())
        assertEquals(0x0123456789ABCDEFL, reader.readUInt64())
    }

    @Test
    fun littleEndianByteOrder() {
        val bytes = PtpBuffer.writer().writeUInt32(0x00000001L).toByteArray()
        // 小端：最低有效字节在前
        assertEquals(0x01, bytes[0].toInt() and 0xFF)
        assertEquals(0x00, bytes[1].toInt() and 0xFF)
        assertEquals(0x00, bytes[2].toInt() and 0xFF)
        assertEquals(0x00, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun ptpStringRoundtrip() {
        val writer = PtpBuffer.writer().writePtpString("DSC00001.ARW")
        val reader = PtpBuffer.reader(writer.toByteArray())
        assertEquals("DSC00001.ARW", reader.readPtpString())
    }

    @Test
    fun emptyPtpStringRoundtrip() {
        val writer = PtpBuffer.writer().writePtpString("")
        val reader = PtpBuffer.reader(writer.toByteArray())
        assertEquals("", reader.readPtpString())
    }

    @Test
    fun utf16StringRoundtrip() {
        val writer = PtpBuffer.writer().writeUtf16String("Imagedge")
        val reader = PtpBuffer.reader(writer.toByteArray())
        assertEquals("Imagedge", reader.readUtf16String())
    }

    @Test
    fun streamingWritesDirectly() {
        val output = ByteArrayOutputStream()
        val buffer = PtpBuffer.writer()
        buffer.enableStreaming(output)
        buffer.writeBytes(ByteArray(1024) { 1 })

        assertEquals(1024L, buffer.streamedCount)
        assertEquals(1024, output.size())
    }
}
