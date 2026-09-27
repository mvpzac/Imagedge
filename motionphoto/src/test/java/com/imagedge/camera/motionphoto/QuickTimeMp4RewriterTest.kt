package com.imagedge.camera.motionphoto

import com.imagedge.camera.motionphoto.internal.format.QuickTimeMp4Rewriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : QuickTime → MP4 改写器：chunk 偏移修补必须拒绝下溢，而不是绕回一个巨大偏移
 * </pre>
 */
class QuickTimeMp4RewriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        writeInt(out, 0, size)
        type.forEachIndexed { i, c -> out[4 + i] = c.code.toByte() }
        payload.copyInto(out, 8)
        return out
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    /** stco：version/flags(4) + entry_count(4) + 每个 entry 4 字节无符号 */
    private fun stco(entries: IntArray): ByteArray {
        val payload = ByteArray(8 + entries.size * 4)
        writeInt(payload, 4, entries.size)
        entries.forEachIndexed { i, v -> writeInt(payload, 8 + i * 4, v) }
        return box("stco", payload)
    }

    /** 造一个「ftyp + moov(stco)」的最小 MOV；fttypSize 决定改写时的 delta */
    private fun movie(ftypSize: Int, entries: IntArray): File {
        val brands = ByteArray(ftypSize - 8).also { it[0] = 'q'.code.toByte() }
        val file = folder.newFile("src.mov")
        file.outputStream().use {
            it.write(box("ftyp", brands))
            it.write(box("moov", stco(entries)))
        }
        return file
    }

    private fun ByteArray.indexOfAscii(marker: String): Int {
        val needle = marker.map { it.code.toByte() }.toByteArray()
        for (i in 0..size - needle.size) {
            if (i + needle.size <= size && needle.indices.all { this[i + it] == needle[it] }) return i
        }
        return -1
    }

    /** stcoAt 指向 "stco" 四字节本身：+0 type、+4 version/flags、+8 entry_count、+12 首个 entry */
    private fun u32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun stcoEntries(target: File): IntArray {
        val bytes = target.readBytes()
        val stcoAt = bytes.indexOfAscii("stco")
        val count = u32(bytes, stcoAt + 8)
        return IntArray(count) { i -> u32(bytes, stcoAt + 12 + i * 4) }
    }

    @Test
    fun `chunk offsets shift by the ftyp size difference`() {
        // 正常路径：替换的 ftyp 比原来小 12 字节 → 所有 chunk 偏移都要前移 12
        val source = movie(ftypSize = 40, entries = intArrayOf(1000, 2000, 3000))
        val target = folder.newFile("out.mp4")

        QuickTimeMp4Rewriter.rebrand(source, target)

        assertEquals(1000 - 12, stcoEntries(target)[0])
        assertEquals(2000 - 12, stcoEntries(target)[1])
        assertEquals(3000 - 12, stcoEntries(target)[2])
    }

    @Test
    fun `a chunk offset that would go below zero is rejected instead of wrapping`() {
        // stco 条目是 32 位**无符号**。偏移 4 减去 12 得到 -8，
        // 直接 toInt() 写回去会被读成 4294967288 —— 导出一个永远播不动的文件，
        // 而且界面上看不出任何异常。这里必须报错。
        val source = movie(ftypSize = 40, entries = intArrayOf(4))
        val target = folder.newFile("out.mp4")

        try {
            QuickTimeMp4Rewriter.rebrand(source, target)
            fail("下溢的 chunk 偏移必须被拒绝，而不是写回一个绕回的巨大偏移")
        } catch (expected: MotionPhotoComposeException) {
            assertTrue(
                "错误信息要说清是偏移下溢：${expected.message}",
                expected.message!!.contains("offset") || expected.message!!.contains("偏移")
            )
        }
    }

    @Test
    fun `co64 chunk offsets shift too`() {
        // co64 是 64 位版本，走的是另一条修补分支；>2GiB 的视频才用得上它
        val source = folder.newFile("src.mov")
        val brands = ByteArray(32).also { it[0] = 'q'.code.toByte() }
        val co64Payload = ByteArray(8 + 2 * 8)
        writeInt(co64Payload, 4, 2)
        listOf(3_000_000_000L, 4_000_000_000L).forEachIndexed { i, v ->
            val at = 8 + i * 8
            for (b in 0 until 8) co64Payload[at + b] = (v ushr (56 - b * 8)).toByte()
        }
        source.outputStream().use {
            it.write(box("ftyp", brands))
            it.write(box("moov", box("co64", co64Payload)))
        }
        val target = folder.newFile("out.mp4")

        QuickTimeMp4Rewriter.rebrand(source, target)

        val bytes = target.readBytes()
        val co64At = bytes.indexOfAscii("co64")
        assertTrue("co64 box 应在改写后的文件里", co64At > 0)
        listOf(3_000_000_000L - 12, 4_000_000_000L - 12).forEachIndexed { i, want ->
            var got = 0L
            for (b in 0 until 8) {
                got = (got shl 8) or (bytes[co64At + 12 + i * 8 + b].toLong() and 0xFF)
            }
            assertEquals(want, got)
        }
    }

    @Test
    fun `a rejected rewrite leaves no half written target behind`() {
        val source = movie(ftypSize = 40, entries = intArrayOf(4))
        val target = folder.newFile("out.mp4")

        runCatching { QuickTimeMp4Rewriter.rebrand(source, target) }

        // 整份几百 MB 的拷贝已经落盘，只是偏移没修好——留着就是纯浪费
        assertFalse("失败时不该把半成品留在磁盘上", target.exists())
    }
}
