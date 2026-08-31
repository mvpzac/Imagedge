package com.imagedge.camera.motionphoto.internal.format

import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import java.io.ByteArrayOutputStream

internal object QuickTimeMp4Rewriter {
    private val mp4CompatibleBrands = listOf("isom", "mp41", "mp42")

    fun rebrand(sourceBytes: ByteArray): ByteArray {
        if (sourceBytes.size < 8) {
            throw MotionPhotoComposeException("The QuickTime file is too small to rewrite as MP4.")
        }
        val originalFtypSize = readBoxSize(sourceBytes, 0, sourceBytes.size).toInt()
        val originalType = String(sourceBytes, 4, 4, Charsets.US_ASCII)
        if (originalType != "ftyp" || originalFtypSize <= 8 || originalFtypSize > sourceBytes.size) {
            throw MotionPhotoComposeException(
                "The QuickTime file is missing a valid ftyp box, so it cannot be processed with the WeChat-style path.",
            )
        }

        val replacementFtyp = buildMp4FtypBox()
        val delta = replacementFtyp.size - originalFtypSize
        val output = ByteArrayOutputStream(sourceBytes.size + delta)
        output.write(replacementFtyp)
        output.write(sourceBytes, originalFtypSize, sourceBytes.size - originalFtypSize)
        val rewritten = output.toByteArray()
        if (delta != 0) {
            patchChunkOffsets(rewritten, delta.toLong())
        }
        return rewritten
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

    private fun patchChunkOffsets(
        bytes: ByteArray,
        delta: Long,
    ) {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val size = readBoxSize(bytes, offset, bytes.size)
            if (size < 8 || offset + size > bytes.size) {
                break
            }
            if (readBoxType(bytes, offset) == "moov") {
                patchChunkOffsetsInContainer(bytes, offset + 8, (offset + size).toInt(), delta)
                return
            }
            offset = (offset + size).toInt()
        }
    }

    private fun patchChunkOffsetsInContainer(
        bytes: ByteArray,
        start: Int,
        end: Int,
        delta: Long,
    ) {
        var offset = start
        while (offset + 8 <= end) {
            val size = readBoxSize(bytes, offset, end)
            if (size < 8 || offset + size > end) {
                break
            }
            when (readBoxType(bytes, offset)) {
                "stco" -> patchStcoBox(bytes, offset, size.toInt(), delta)
                "co64" -> patchCo64Box(bytes, offset, size.toInt(), delta)
                "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "tref" ->
                    patchChunkOffsetsInContainer(bytes, offset + 8, (offset + size).toInt(), delta)
                "meta" ->
                    patchChunkOffsetsInContainer(bytes, offset + 12, (offset + size).toInt(), delta)
            }
            offset = (offset + size).toInt()
        }
    }

    private fun patchStcoBox(
        bytes: ByteArray,
        boxOffset: Int,
        boxSize: Int,
        delta: Long,
    ) {
        // 畸形/截断的 box（size 8~15）读不到 entry_count，直接跳过防越界
        if (boxSize < 16 || boxOffset + 16 > bytes.size) return
        val entryCount = readInt(bytes, boxOffset + 12)
        var entryOffset = boxOffset + 16
        repeat(entryCount) {
            if (entryOffset + 4 > boxOffset + boxSize) {
                return
            }
            val value = readUnsignedInt(bytes, entryOffset)
            writeInt(bytes, entryOffset, (value + delta).toInt())
            entryOffset += 4
        }
    }

    private fun patchCo64Box(
        bytes: ByteArray,
        boxOffset: Int,
        boxSize: Int,
        delta: Long,
    ) {
        if (boxSize < 16 || boxOffset + 16 > bytes.size) return
        val entryCount = readInt(bytes, boxOffset + 12)
        var entryOffset = boxOffset + 16
        repeat(entryCount) {
            if (entryOffset + 8 > boxOffset + boxSize) {
                return
            }
            val value = readLong(bytes, entryOffset)
            writeLong(bytes, entryOffset, value + delta)
            entryOffset += 8
        }
    }

    private fun readBoxSize(
        bytes: ByteArray,
        offset: Int,
        limit: Int,
    ): Long {
        if (offset + 8 > limit) {
            return -1
        }
        val size32 = readUnsignedInt(bytes, offset)
        return when {
            size32 == 1L && offset + 16 <= limit -> readLong(bytes, offset + 8)
            size32 == 0L -> (limit - offset).toLong()
            else -> size32
        }
    }

    private fun readBoxType(
        bytes: ByteArray,
        offset: Int,
    ): String {
        return String(bytes, offset + 4, 4, Charsets.US_ASCII)
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readUnsignedInt(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        return readInt(bytes, offset).toLong() and 0xFFFF_FFFFL
    }

    private fun readLong(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var result = 0L
        repeat(8) { index ->
            result = (result shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return result
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

    private fun writeLong(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (index in 7 downTo 0) {
            bytes[offset + (7 - index)] = ((value ushr (index * 8)) and 0xFF).toByte()
        }
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
