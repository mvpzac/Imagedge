package com.imagedge.camera.raw

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class EmbeddedJpegDecoderTest {

    private val decoder = EmbeddedJpegDecoder()

    private fun u16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    /** 小端 IFD 条目：tag(2) type(2) count(4) value/offset(4) */
    private fun entry(tag: Int, type: Int, count: Int, value: Int): ByteArray =
        u16(tag) + u16(type) + u32(count) + u32(value)

    /** 构造 FFD8...FFD9 的合成 JPEG（中间填充 0 避免误匹配） */
    private fun fakeJpeg(size: Int): ByteArray {
        require(size >= 4)
        val out = ByteArray(size)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte(); out[2] = 0xFF.toByte()
        out[size - 2] = 0xFF.toByte(); out[size - 1] = 0xD9.toByte()
        return out
    }

    /**
     * 合成 ARW（小端 TIFF 容器）：
     * 头（II/42/IFD0 偏移）→ IFD0（SubIFDs 0x014A）→ SubIFD（Compression=7 / 0x0201 / 0x0202）→ JPEG 数据
     */
    private fun fakeArw(jpeg: ByteArray): ByteArray {
        val ifd0Offset = 8
        val subIfdOffset = ifd0Offset + 2 + 12 + 4
        val jpegOffset = subIfdOffset + 2 + 3 * 12 + 4
        return ByteArrayOutputStream().apply {
            write("II".toByteArray())
            write(u16(42))
            write(u32(ifd0Offset))
            write(u16(1))
            write(entry(0x014A, 4, 1, subIfdOffset))
            write(u32(0))
            write(u16(3))
            write(entry(0x0103, 3, 1, 7))           // Compression = 7（JPEG）
            write(entry(0x0201, 4, 1, jpegOffset))  // StripOffsets
            write(entry(0x0202, 4, 1, jpeg.size))   // StripByteCounts
            write(u32(0))
            write(jpeg)
        }.toByteArray()
    }

    @Test
    fun `extracts embedded jpeg from tiff container`() = runBlocking {
        val jpeg = fakeJpeg(2048)
        val result = decoder.decodeEmbeddedJpeg(fakeArw(jpeg))
        assertNotNull(result)
        assertArrayEquals(jpeg, result)
    }

    @Test
    fun `falls back to largest jpeg scan for non-tiff input`() = runBlocking {
        // 兜底扫描只认 >1MB 的候选（过滤缩略图假阳性）
        val big = fakeJpeg(1_500_000)
        val result = decoder.decodeEmbeddedJpeg(big)
        assertNotNull(result)
        assertEquals(big.size, result!!.size)
    }

    @Test
    fun `returns null for garbage input`() = runBlocking {
        assertNull(decoder.decodeEmbeddedJpeg(ByteArray(16) { 0x11 }))
        assertNull(decoder.decodeEmbeddedJpeg(ByteArray(2)))
    }
}
