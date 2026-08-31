package com.imagedge.camera.motionphoto.internal.format

import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * QuickTime → MP4 品牌重写器（WeChat 兼容路径）。
 *
 * **流式实现**：全程堆内只有固定缓冲（8KB 拷贝缓冲 + 16 字节头），
 * GB 级 iPhone MOV 不会 OOM。原实现 `rebrand(bytes)` 整读整写，
 * 大视频直接顶爆堆。
 *
 * 步骤：替换 ftyp 盒 → 流式拷贝其余字节 → delta 非 0 时用
 * RandomAccessFile 原地修补 moov 内的 stco/co64 chunk 偏移。
 */
internal object QuickTimeMp4Rewriter {
    private val mp4CompatibleBrands = listOf("isom", "mp41", "mp42")

    private const val COPY_BUFFER_SIZE = 8 * 1024

    fun rebrand(source: File, target: File) {
        val sourceLength = source.length()
        if (sourceLength < 8) {
            throw MotionPhotoComposeException("The QuickTime file is too small to rewrite as MP4.")
        }

        val originalFtypSize: Long
        RandomAccessFile(source, "r").use { raf ->
            val size32 = readUnsignedIntAt(raf, 0)
            val originalType = readBoxTypeAt(raf, 4)
            if (originalType != "ftyp") {
                throw MotionPhotoComposeException(
                    "The QuickTime file is missing a valid ftyp box, so it cannot be processed with the WeChat-style path.",
                )
            }
            originalFtypSize = when {
                size32 == 1L && sourceLength >= 16 -> readLongAt(raf, 8)
                size32 == 0L -> sourceLength
                else -> size32
            }
        }
        if (originalFtypSize <= 8 || originalFtypSize > sourceLength) {
            throw MotionPhotoComposeException(
                "The QuickTime file is missing a valid ftyp box, so it cannot be processed with the WeChat-style path.",
            )
        }

        val replacementFtyp = buildMp4FtypBox()
        val delta = replacementFtyp.size.toLong() - originalFtypSize

        // 流式拷贝：替换 ftyp + 逐块搬运其余字节（堆内固定 8KB 缓冲）
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                output.write(replacementFtyp)
                var skipped = 0L
                while (skipped < originalFtypSize) {
                    val s = input.skip(originalFtypSize - skipped)
                    if (s > 0) {
                        skipped += s
                        continue
                    }
                    if (input.read() == -1) break
                    skipped += 1
                }
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }

        if (delta != 0L) {
            RandomAccessFile(target, "rw").use { raf ->
                patchChunkOffsets(raf, raf.length(), delta)
            }
        }
    }

    private fun buildMp4FtypBox(): ByteArray {
        val compatibleBytes = mp4CompatibleBrands.size * 4
        val size = 16 + compatibleBytes
        val box = ByteArray(size)
        writeInt(box, 0, size)
        writeAscii(box, 4, "ftyp")
        writeAscii(box, 8, "mp42")
        writeInt(box, 12, 1)
        var offset = 16
        mp4CompatibleBrands.forEach { brand ->
            writeAscii(box, offset, brand)
            offset += 4
        }
        return box
    }

    // ── chunk 偏移修补（RandomAccessFile 原地，替代整块载入）──────────

    private fun patchChunkOffsets(
        raf: RandomAccessFile,
        limit: Long,
        delta: Long,
    ) {
        var offset = 0L
        while (offset + 8 <= limit) {
            val size = readBoxSize(raf, offset, limit)
            if (size < 8 || offset + size > limit) {
                break
            }
            if (readBoxTypeAt(raf, offset) == "moov") {
                patchChunkOffsetsInContainer(raf, offset + 8, offset + size, delta)
                return
            }
            offset += size
        }
    }

    private fun patchChunkOffsetsInContainer(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        delta: Long,
    ) {
        var offset = start
        while (offset + 8 <= end) {
            val size = readBoxSize(raf, offset, end)
            if (size < 8 || offset + size > end) {
                break
            }
            when (readBoxTypeAt(raf, offset)) {
                "stco" -> patchStcoBox(raf, offset, size, delta)
                "co64" -> patchCo64Box(raf, offset, size, delta)
                "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "tref" ->
                    patchChunkOffsetsInContainer(raf, offset + 8, offset + size, delta)
                "meta" ->
                    patchChunkOffsetsInContainer(raf, offset + 12, offset + size, delta)
            }
            offset += size
        }
    }

    private fun patchStcoBox(
        raf: RandomAccessFile,
        boxOffset: Long,
        boxSize: Long,
        delta: Long,
    ) {
        // 畸形/截断的 box（size 8~15）读不到 entry_count，直接跳过防越界
        if (boxSize < 16 || boxOffset + 16 > raf.length()) return
        val entryCount = readIntAt(raf, boxOffset + 12)
        var entryOffset = boxOffset + 16
        repeat(entryCount) {
            if (entryOffset + 4 > boxOffset + boxSize) {
                return
            }
            val value = readUnsignedIntAt(raf, entryOffset)
            writeIntAt(raf, entryOffset, (value + delta).toInt())
            entryOffset += 4
        }
    }

    private fun patchCo64Box(
        raf: RandomAccessFile,
        boxOffset: Long,
        boxSize: Long,
        delta: Long,
    ) {
        if (boxSize < 16 || boxOffset + 16 > raf.length()) return
        val entryCount = readIntAt(raf, boxOffset + 12)
        var entryOffset = boxOffset + 16
        repeat(entryCount) {
            if (entryOffset + 8 > boxOffset + boxSize) {
                return
            }
            val value = readLongAt(raf, entryOffset)
            writeLongAt(raf, entryOffset, value + delta)
            entryOffset += 8
        }
    }

    // ── RandomAccessFile 大端读写助手（MP4 为大端）────────────────────

    private fun readBoxSize(
        raf: RandomAccessFile,
        offset: Long,
        limit: Long,
    ): Long {
        if (offset + 8 > limit) {
            return -1
        }
        val size32 = readUnsignedIntAt(raf, offset)
        return when {
            size32 == 1L && offset + 16 <= limit -> readLongAt(raf, offset + 8)
            size32 == 0L -> limit - offset
            else -> size32
        }
    }

    private fun readBoxTypeAt(
        raf: RandomAccessFile,
        offset: Long,
    ): String {
        val type = ByteArray(4)
        raf.seek(offset)
        var read = 0
        while (read < 4) {
            val n = raf.read(type, read, 4 - read)
            if (n == -1) return ""
            read += n
        }
        return String(type, Charsets.US_ASCII)
    }

    private fun readIntAt(
        raf: RandomAccessFile,
        offset: Long,
    ): Int {
        raf.seek(offset)
        return raf.readInt()
    }

    private fun readUnsignedIntAt(
        raf: RandomAccessFile,
        offset: Long,
    ): Long {
        return readIntAt(raf, offset).toLong() and 0xFFFF_FFFFL
    }

    private fun readLongAt(
        raf: RandomAccessFile,
        offset: Long,
    ): Long {
        raf.seek(offset)
        return raf.readLong()
    }

    private fun writeIntAt(
        raf: RandomAccessFile,
        offset: Long,
        value: Int,
    ) {
        raf.seek(offset)
        raf.writeInt(value)
    }

    private fun writeLongAt(
        raf: RandomAccessFile,
        offset: Long,
        value: Long,
    ) {
        raf.seek(offset)
        raf.writeLong(value)
    }

    private fun writeInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeAscii(
        bytes: ByteArray,
        offset: Int,
        value: String,
    ) {
        val ascii = value.toByteArray(Charsets.US_ASCII)
        ascii.copyInto(bytes, destinationOffset = offset)
    }
}
