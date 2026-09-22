package com.imagedge.camera.motionphoto.internal.parse

import com.imagedge.camera.motionphoto.ContainerItem
import com.imagedge.camera.motionphoto.MetadataSource
import com.imagedge.camera.motionphoto.MotionPhotoParseException
import com.imagedge.camera.motionphoto.XmpSummary
import com.imagedge.camera.motionphoto.internal.format.MotionPhotoMimeSniffer
import com.imagedge.camera.motionphoto.internal.format.inferIsoBaseMediaMime
import com.imagedge.camera.motionphoto.internal.format.looksLikeIsoBaseMedia
import com.imagedge.camera.motionphoto.internal.format.looksLikeJpeg
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * 只计算文件范围，不把 Motion Photo 整体或视频段读入堆内存。
 */
internal object MotionPhotoSegmentExtractor {
    fun extract(sourceFile: File, xmpSummary: XmpSummary): Extraction {
        val sourceSize = sourceFile.length()
        if (sourceSize <= 0L) throw MotionPhotoParseException("The selected file is empty.")
        return when {
            xmpSummary.items.size > 1 -> extractFromContainerDirectory(sourceFile, sourceSize, xmpSummary)
            (xmpSummary.microVideoOffset ?: 0L) > 0L ->
                extractFromLegacyOffset(sourceFile, sourceSize, xmpSummary, xmpSummary.microVideoOffset!!)
            else -> throw MotionPhotoParseException("No usable video location metadata was found in the XMP.")
        }
    }

    private fun extractFromContainerDirectory(
        sourceFile: File,
        sourceSize: Long,
        xmpSummary: XmpSummary,
    ): Extraction {
        val primary = xmpSummary.items.firstOrNull()
            ?: throw MotionPhotoParseException("The Container directory is empty.")
        val primaryEnd = calculatePrimaryEndOffset(sourceSize, xmpSummary.items)
        if (primaryEnd <= 0L || primaryEnd >= sourceSize) {
            throw MotionPhotoParseException("The primary image length calculated from the Container directory is invalid.")
        }
        val image = BinarySegment(
            sourceFile = sourceFile,
            mimeType = primary.mimeType ?: inferPrimaryMime(sourceFile),
            startOffset = 0L,
            endOffset = primaryEnd,
        )
        validatePrimaryImage(image)

        val secondary = extractSecondaryItems(sourceFile, sourceSize, xmpSummary.items, primaryEnd)
        val rawVideo = secondary.lastOrNull {
            it.item.semantic.equals("MotionPhoto", ignoreCase = true)
        }?.segment ?: throw MotionPhotoParseException(
            "No MotionPhoto video item was found in the Container directory.",
        )
        val video = normalizeVideoSegment(rawVideo)
        validateVideoSegment(video)
        val gainMap = secondary.firstOrNull {
            it.item.semantic.equals("GainMap", ignoreCase = true)
        }?.segment
        gainMap?.let(::validateGainMapSegment)
        return Extraction(image, video, gainMap, MetadataSource.CONTAINER_DIRECTORY)
    }

    private fun extractFromLegacyOffset(
        sourceFile: File,
        sourceSize: Long,
        xmpSummary: XmpSummary,
        legacyOffset: Long,
    ): Extraction {
        val videoStart = sourceSize - legacyOffset
        if (videoStart <= 0L || videoStart >= sourceSize) {
            throw MotionPhotoParseException("The legacy MicroVideoOffset points outside the file bounds.")
        }
        val imageMime = xmpSummary.items.firstOrNull()?.mimeType ?: inferPrimaryMime(sourceFile)
        val primaryEnd = determinePrimaryEndOffset(sourceFile, imageMime, videoStart)
        val image = BinarySegment(sourceFile, imageMime, 0L, primaryEnd)
        val video = BinarySegment(sourceFile, "video/mp4", videoStart, sourceSize)
        validatePrimaryImage(image)
        validateVideoSegment(video)
        return Extraction(image, video, null, MetadataSource.LEGACY_MICRO_VIDEO_OFFSET)
    }

    private fun calculatePrimaryEndOffset(sourceSize: Long, items: List<ContainerItem>): Long {
        val secondaryLength = checkedSum(items.drop(1).map { nonNegative(it.length, "Length") })
        val padding = checkedSum(items.map { nonNegative(it.padding, "Padding") })
        return try {
            Math.subtractExact(Math.subtractExact(sourceSize, secondaryLength), padding)
        } catch (_: ArithmeticException) {
            throw MotionPhotoParseException("The Container directory size fields overflow.")
        }
    }

    private fun extractSecondaryItems(
        sourceFile: File,
        sourceSize: Long,
        items: List<ContainerItem>,
        primaryEnd: Long,
    ): List<ExtractedItem> {
        var cursor = checkedAdd(primaryEnd, nonNegative(items.firstOrNull()?.padding, "Padding"))
        var previous: BinarySegment? = null
        return buildList {
            for (item in items.drop(1)) {
                val length = item.length
                    ?: throw MotionPhotoParseException("A secondary item is missing Length.")
                val segment = if (length == 0L) {
                    previous ?: throw MotionPhotoParseException(
                        "A shared resource with Length=0 has no previous item to reuse.",
                    )
                } else {
                    if (length < 0L || cursor < 0L || cursor >= sourceSize) {
                        throw MotionPhotoParseException("A secondary item exceeds the file bounds.")
                    }
                    val end = checkedAdd(cursor, length)
                    if (end > sourceSize) {
                        throw MotionPhotoParseException("A secondary item exceeds the file bounds.")
                    }
                    BinarySegment(
                        sourceFile = sourceFile,
                        mimeType = item.mimeType ?: inferMimeAt(sourceFile, cursor),
                        startOffset = cursor,
                        endOffset = end,
                    ).also {
                        previous = it
                        cursor = checkedAdd(end, nonNegative(item.padding, "Padding"))
                        if (cursor > sourceSize) {
                            throw MotionPhotoParseException("A secondary item padding exceeds the file bounds.")
                        }
                    }
                }
                add(ExtractedItem(item, segment))
            }
        }
    }

    private fun normalizeVideoSegment(segment: BinarySegment): BinarySegment {
        if (looksLikeIsoAt(segment.sourceFile, segment.startOffset)) return segment
        val ftyp = findPattern(
            segment.sourceFile,
            segment.startOffset,
            segment.endOffset,
            "ftyp".toByteArray(StandardCharsets.US_ASCII),
        ) ?: throw MotionPhotoParseException("No video container start was found inside the MotionPhoto item.")
        val isoStart = ftyp - 4L
        if (isoStart < segment.startOffset || !looksLikeIsoAt(segment.sourceFile, isoStart)) {
            throw MotionPhotoParseException("The video boundaries inside the MotionPhoto item are invalid.")
        }
        val footer = findPattern(
            segment.sourceFile,
            isoStart,
            segment.endOffset,
            "SEFH".toByteArray(StandardCharsets.US_ASCII),
        )
        val isoEnd = footer?.takeIf { it > isoStart } ?: segment.endOffset
        return segment.copy(startOffset = isoStart, endOffset = isoEnd)
    }

    private fun validatePrimaryImage(segment: BinarySegment) {
        if (segment.mimeType.contains("jpeg", true) && !looksLikeJpegAt(segment.sourceFile, segment.startOffset)) {
            throw MotionPhotoParseException("The extracted primary image is not a valid JPEG.")
        }
    }

    private fun validateVideoSegment(segment: BinarySegment) {
        if (!looksLikeIsoAt(segment.sourceFile, segment.startOffset)) {
            throw MotionPhotoParseException("The located video segment is not a recognizable MP4/MOV container.")
        }
    }

    private fun validateGainMapSegment(segment: BinarySegment) {
        if (segment.mimeType.contains("jpeg", true) && !looksLikeJpegAt(segment.sourceFile, segment.startOffset)) {
            throw MotionPhotoParseException("The GainMap segment is not a valid JPEG.")
        }
    }

    private fun determinePrimaryEndOffset(file: File, mime: String, upperBound: Long): Long {
        if (mime.contains("jpeg", true) || looksLikeJpegAt(file, 0L)) {
            return findJpegEndOffset(file, upperBound) ?: upperBound
        }
        return upperBound
    }

    /** JPEG marker 结构流式扫描；不会把压缩像素数据放入 ByteArray。 */
    private fun findJpegEndOffset(file: File, upperBound: Long): Long? =
        RandomAccessFile(file, "r").use { raf ->
            if (upperBound < 2L || raf.readUnsignedByte() != 0xFF || raf.readUnsignedByte() != 0xD8) {
                return@use null
            }
            var offset = 2L
            while (offset + 1L < upperBound) {
                raf.seek(offset)
                if (raf.readUnsignedByte() != 0xFF) return@use null
                val marker = raf.readUnsignedByte()
                when {
                    marker == 0xFF -> offset += 1L
                    marker == 0x00 -> return@use null
                    marker == 0xD9 -> return@use offset + 2L
                    marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7 -> offset += 2L
                    marker == 0xDA -> {
                        if (offset + 4L > upperBound) return@use null
                        val length = raf.readUnsignedShort()
                        if (length < 2) return@use null
                        var cursor = offset + 2L + length
                        if (cursor > upperBound) return@use null
                        var previousWasFf = false
                        var found = -1L
                        raf.seek(cursor)
                        while (cursor < upperBound) {
                            val current = raf.readUnsignedByte()
                            if (previousWasFf && current != 0x00 && current !in 0xD0..0xD7) {
                                found = cursor - 1L
                                break
                            }
                            previousWasFf = current == 0xFF
                            cursor++
                        }
                        if (found < 0L) return@use null
                        offset = found
                    }
                    else -> {
                        if (offset + 4L > upperBound) return@use null
                        val length = raf.readUnsignedShort()
                        if (length < 2) return@use null
                        offset = checkedAdd(offset + 2L, length.toLong())
                        if (offset > upperBound) return@use null
                    }
                }
            }
            null
        }

    private fun inferPrimaryMime(file: File): String =
        MotionPhotoMimeSniffer.inferPrimaryMime(readAt(file, 0L, 16))

    private fun inferMimeAt(file: File, offset: Long): String {
        val header = readAt(file, offset, 16)
        return when {
            looksLikeJpeg(header, 0) -> "image/jpeg"
            looksLikeIsoBaseMedia(header, 0) -> inferIsoBaseMediaMime(header)
            else -> "application/octet-stream"
        }
    }

    private fun looksLikeJpegAt(file: File, offset: Long): Boolean =
        looksLikeJpeg(readAt(file, offset, 2), 0)

    private fun looksLikeIsoAt(file: File, offset: Long): Boolean =
        looksLikeIsoBaseMedia(readAt(file, offset, 12), 0)

    private fun readAt(file: File, offset: Long, count: Int): ByteArray =
        RandomAccessFile(file, "r").use { raf ->
            if (offset < 0L || offset >= raf.length()) return@use ByteArray(0)
            raf.seek(offset)
            val result = ByteArray(minOf(count.toLong(), raf.length() - offset).toInt())
            raf.readFully(result)
            result
        }

    private fun findPattern(file: File, start: Long, end: Long, pattern: ByteArray): Long? {
        if (pattern.isEmpty() || start < 0L || end <= start) return null
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var absolute = start
            var matched = 0
            while (absolute < end) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), end - absolute).toInt())
                if (read <= 0) break
                for (index in 0 until read) {
                    val byte = buffer[index]
                    if (byte == pattern[matched]) {
                        matched++
                        if (matched == pattern.size) {
                            return absolute + index - pattern.size + 1L
                        }
                    } else {
                        matched = if (byte == pattern[0]) 1 else 0
                    }
                }
                absolute += read
            }
        }
        return null
    }

    private fun nonNegative(value: Long?, field: String): Long {
        val actual = value ?: 0L
        if (actual < 0L) throw MotionPhotoParseException("A Container $field value is negative.")
        return actual
    }

    private fun checkedSum(values: List<Long>): Long = values.fold(0L, ::checkedAdd)

    private fun checkedAdd(left: Long, right: Long): Long = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw MotionPhotoParseException("The Container directory size fields overflow.")
    }
}
