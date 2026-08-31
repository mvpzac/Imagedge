package com.imagedge.camera.raw

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : RAW 内嵌 JPEG 预览解码器（M1，纯 Kotlin）——
 *              解析 ARW（TIFF 容器）的 SubIFD 预览条带，秒开大图；
 *              结构化解析失败时兜底扫描 JPEG 边界。libraw 真解码见 M2。
 *     version: 1.0
 * </pre>
 */
class EmbeddedJpegDecoder : RawDecoder {

    override suspend fun decodeEmbeddedJpeg(bytes: ByteArray): ByteArray? {
        return parseTiffPreview(bytes) ?: scanLargestJpeg(bytes)
    }

    /** M2 接入 libraw 前返回 null（调用方回退内嵌预览） */
    override suspend fun decodeFull(bytes: ByteArray): DecodedImage? = null

    // ── TIFF 结构化解析 ──────────────────────────────────────────────

    /** ARW = TIFF 容器：IFD0 的 SubIFD 里含 Compression=6/7 的 JPEG 预览条带 */
    private fun parseTiffPreview(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8) return null
        val littleEndian = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (u16(bytes, 2, littleEndian) != 42) return null
        val ifd0Offset = u32(bytes, 4, littleEndian)

        val subIfdOffsets = readSubIfdOffsets(bytes, ifd0Offset, littleEndian)
        var best: ByteArray? = null
        for (offset in subIfdOffsets) {
            val preview = readJpegStrip(bytes, offset, littleEndian) ?: continue
            if (best == null || preview.size > best.size) best = preview
        }
        return best
    }

    /** IFD0 → 0x014A SubIFDs 偏移列表 */
    private fun readSubIfdOffsets(bytes: ByteArray, ifdOffset: Int, le: Boolean): List<Int> {
        val offsets = mutableListOf<Int>()
        try {
            val entryCount = u16(bytes, ifdOffset, le)
            for (i in 0 until entryCount) {
                val entry = ifdOffset + 2 + i * 12
                if (entry + 12 > bytes.size) return offsets
                when (u16(bytes, entry, le)) {
                    0x014A -> offsets.addAll(readLongValues(bytes, entry, le).map { it.toInt() })
                }
            }
        } catch (_: Exception) {
            // 容器异常走兜底扫描
        }
        return offsets
    }

    /** SubIFD → Compression=6/7 的 JPEG 条带字节（0x0201 偏移 + 0x0202 计数） */
    private fun readJpegStrip(bytes: ByteArray, ifdOffset: Int, le: Boolean): ByteArray? {
        try {
            val entryCount = u16(bytes, ifdOffset, le)
            var compression = -1
            var stripOffsets: List<Long> = emptyList()
            var stripCounts: List<Long> = emptyList()
            for (i in 0 until entryCount) {
                val entry = ifdOffset + 2 + i * 12
                if (entry + 12 > bytes.size) return null
                when (u16(bytes, entry, le)) {
                    0x0103 -> compression = readShortValue(bytes, entry, le)
                    0x0201 -> stripOffsets = readLongValues(bytes, entry, le)
                    0x0202 -> stripCounts = readLongValues(bytes, entry, le)
                }
            }
            if (compression !in intArrayOf(6, 7)) return null
            if (stripOffsets.isEmpty() || stripCounts.isEmpty()) return null

            // 单条带直接取；多条带按连续区间取（预览条带通常连续）
            val start = stripOffsets.min().toInt()
            val end = (stripOffsets.zip(stripCounts).maxOf { it.first + it.second }).toInt()
            if (start < 0 || end > bytes.size || end <= start) return null
            // 边界内对齐真实 JPEG 边界（防 TIFF 头/填充混入）
            return trimToJpegBounds(bytes, start, end)
        } catch (_: Exception) {
            return null
        }
    }

    /** 在 [start, end) 内裁出 FFD8..FFD9 的完整 JPEG */
    private fun trimToJpegBounds(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var soi = -1
        var i = start
        while (i < end - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()) { soi = i; break }
            i++
        }
        if (soi < 0) return null
        var j = end - 2
        while (j > soi) {
            if (bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte()) {
                return bytes.copyOfRange(soi, j + 2)
            }
            j--
        }
        return null
    }

    // ── 兜底：全量扫描最大的 JPEG 流 ─────────────────────────────────

    private fun scanLargestJpeg(bytes: ByteArray): ByteArray? {
        var best: ByteArray? = null
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() &&
                bytes[i + 2] == 0xFF.toByte()
            ) {
                // 找到 SOI，向后找 EOI
                var j = i + 2
                while (j < bytes.size - 1) {
                    if (bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte()) {
                        val size = j + 2 - i
                        // 过滤明显的假阳性（缩略图很小，全尺寸预览通常 > 1MB）
                        if (size > 1_000_000 && (best == null || size > best.size)) {
                            best = bytes.copyOfRange(i, j + 2)
                        }
                        i = j + 2
                        break
                    }
                    j++
                }
            }
            i++
        }
        return best
    }

    // ── TIFF 基础读取 ───────────────────────────────────────────────

    private fun u16(b: ByteArray, off: Int, le: Boolean): Int =
        if (le) (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        else ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int, le: Boolean): Int =
        if (le) (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        else ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** IFD 条目值：count*size ≤ 4 内联，否则为偏移；返回 LONG 列表（兼容 SHORT/IFD/LONG8） */
    private fun readLongValues(b: ByteArray, entry: Int, le: Boolean): List<Long> {
        val type = u16(b, entry + 2, le)
        val count = u32(b, entry + 4, le)
        val typeSize = when (type) {
            1 -> 1; 3 -> 2; 4 -> 4; 13 -> 4; 16 -> 8; 17 -> 8; else -> return emptyList()
        }
        val total = typeSize * count
        val valueBase = if (total <= 4) entry + 8 else u32(b, entry + 8, le)
        if (valueBase < 0 || valueBase + total > b.size) return emptyList()
        return when (type) {
            3 -> (0 until count).map { u16(b, valueBase + it * 2, le).toLong() }
            4, 13 -> (0 until count).map { u32(b, valueBase + it * 4, le).toLong() and 0xFFFFFFFFL }
            16, 17 -> (0 until count).map { i ->
                var v = 0L
                if (le) for (k in 7 downTo 0) v = (v shl 8) or (b[valueBase + i * 8 + k].toLong() and 0xFF)
                else for (k in 0..7) v = (v shl 8) or (b[valueBase + i * 8 + k].toLong() and 0xFF)
                v
            }
            else -> emptyList()
        }
    }

    private fun readShortValue(b: ByteArray, entry: Int, le: Boolean): Int =
        u16(b, if (u32(b, entry + 4, le) <= 2) entry + 8 else u32(b, entry + 8, le), le)
}
