package com.imagedge.camera.motionphoto.internal.parse

import com.imagedge.camera.motionphoto.MetadataSource
import com.imagedge.camera.motionphoto.MotionPhotoParseException
import com.imagedge.camera.motionphoto.XmpSummary
import com.imagedge.camera.motionphoto.internal.format.MotionPhotoMimeSniffer
import com.imagedge.camera.motionphoto.internal.format.indexOfSubarray
import com.imagedge.camera.motionphoto.internal.format.looksLikeIsoBaseMedia
import com.imagedge.camera.motionphoto.internal.format.looksLikeJpeg
import java.nio.charset.StandardCharsets

internal object MotionPhotoSegmentExtractor {
    fun extract(
        sourceBytes: ByteArray,
        xmpSummary: XmpSummary,
    ): Extraction {
        if (xmpSummary.items.size > 1) {
            return extractFromContainerDirectory(sourceBytes, xmpSummary)
        }

        val legacyOffset = xmpSummary.microVideoOffset
        if (legacyOffset != null && legacyOffset > 0) {
            return extractFromLegacyMicroVideoOffset(sourceBytes, xmpSummary, legacyOffset)
        }

        throw MotionPhotoParseException("No usable video location metadata was found in the XMP.")
    }

    private fun extractFromContainerDirectory(
        sourceBytes: ByteArray,
        xmpSummary: XmpSummary,
    ): Extraction {
        val primaryItem = xmpSummary.items.firstOrNull()
            ?: throw MotionPhotoParseException("The Container directory is empty.")
        val primaryEndOffset = calculatePrimaryEndOffset(sourceBytes.size, xmpSummary.items)
        if (primaryEndOffset !in 1 until sourceBytes.size) {
            throw MotionPhotoParseException(
                "The primary image length calculated from the Container directory is invalid.",
            )
        }

        val imageMimeType = primaryItem.mimeType ?: MotionPhotoMimeSniffer.inferPrimaryMime(sourceBytes)
        val image = BinarySegment(
            bytes = sourceBytes.copyOfRange(0, primaryEndOffset),
            mimeType = imageMimeType,
            startOffset = 0,
            endOffset = primaryEndOffset,
        )
        validatePrimaryImage(image)

        val extractedItems = extractSecondaryItems(sourceBytes, xmpSummary.items, primaryEndOffset)
        val rawVideoSegment = extractedItems.lastOrNull {
            it.item.semantic.equals("MotionPhoto", ignoreCase = true)
        }?.segment ?: throw MotionPhotoParseException(
            "No MotionPhoto video item was found in the Container directory.",
        )
        val video = normalizeVideoSegment(rawVideoSegment)
        validateVideoSegment(video)

        val gainMap = extractedItems.firstOrNull {
            it.item.semantic.equals("GainMap", ignoreCase = true)
        }?.segment
        gainMap?.let(::validateGainMapSegment)

        return Extraction(
            image = image,
            video = video,
            gainMap = gainMap,
            metadataSource = MetadataSource.CONTAINER_DIRECTORY,
        )
    }

    private fun extractFromLegacyMicroVideoOffset(
        sourceBytes: ByteArray,
        xmpSummary: XmpSummary,
        legacyOffset: Int,
    ): Extraction {
        val videoStartOffset = sourceBytes.size - legacyOffset
        if (videoStartOffset !in 1 until sourceBytes.size) {
            throw MotionPhotoParseException(
                "The legacy MicroVideoOffset points outside the file bounds.",
            )
        }

        val imageMimeType = xmpSummary.items.firstOrNull()?.mimeType
            ?: MotionPhotoMimeSniffer.inferPrimaryMime(sourceBytes)
        val primaryEndOffset = determinePrimaryEndOffset(sourceBytes, imageMimeType, videoStartOffset)
        val image = BinarySegment(
            bytes = sourceBytes.copyOfRange(0, primaryEndOffset),
            mimeType = imageMimeType,
            startOffset = 0,
            endOffset = primaryEndOffset,
        )
        val video = BinarySegment(
            bytes = sourceBytes.copyOfRange(videoStartOffset, sourceBytes.size),
            mimeType = "video/mp4",
            startOffset = videoStartOffset,
            endOffset = sourceBytes.size,
        )

        validatePrimaryImage(image)
        validateVideoSegment(video)

        return Extraction(
            image = image,
            video = video,
            gainMap = null,
            metadataSource = MetadataSource.LEGACY_MICRO_VIDEO_OFFSET,
        )
    }

    private fun calculatePrimaryEndOffset(
        sourceSize: Int,
        items: List<com.imagedge.camera.motionphoto.ContainerItem>,
    ): Int {
        val secondaryLengthSum = items.drop(1).sumOf { maxOf(it.length ?: 0, 0) }
        val paddingSum = items.sumOf { maxOf(it.padding ?: 0, 0) }
        return sourceSize - secondaryLengthSum - paddingSum
    }

    private fun extractSecondaryItems(
        sourceBytes: ByteArray,
        items: List<com.imagedge.camera.motionphoto.ContainerItem>,
        primaryEndOffset: Int,
    ): List<ExtractedItem> {
        var cursor = primaryEndOffset + (items.firstOrNull()?.padding ?: 0)
        var previousSegment: BinarySegment? = null
        val extractedItems = mutableListOf<ExtractedItem>()

        for (item in items.drop(1)) {
            val length = item.length
                ?: throw MotionPhotoParseException("A secondary item is missing Length.")
            val segment = if (length == 0) {
                previousSegment ?: throw MotionPhotoParseException(
                    "A shared resource with Length=0 has no previous item to reuse.",
                )
            } else {
                // 用 Long 加法：Int 溢出会让畸形的大 Length 绕过边界检查
                val endOffsetLong = cursor.toLong() + length.toLong()
                if (length < 0 || cursor !in 0 until sourceBytes.size || endOffsetLong > sourceBytes.size) {
                    throw MotionPhotoParseException("A secondary item exceeds the file bounds.")
                }
                val endOffset = endOffsetLong.toInt()
                BinarySegment(
                    bytes = sourceBytes.copyOfRange(cursor, endOffset),
                    mimeType = item.mimeType ?: MotionPhotoMimeSniffer.inferSecondaryMime(sourceBytes, cursor),
                    startOffset = cursor,
                    endOffset = endOffset,
                ).also {
                    previousSegment = it
                    cursor = endOffset + (item.padding ?: 0)
                }
            }
            extractedItems += ExtractedItem(item = item, segment = segment)
        }

        return extractedItems
    }

    private fun validatePrimaryImage(segment: BinarySegment) {
        if (segment.mimeType.contains("jpeg", ignoreCase = true) && !looksLikeJpeg(segment.bytes, 0)) {
            throw MotionPhotoParseException("The extracted primary image is not a valid JPEG.")
        }
    }

    private fun validateVideoSegment(segment: BinarySegment) {
        if (!looksLikeIsoBaseMedia(segment.bytes, 0)) {
            throw MotionPhotoParseException(
                "The located video segment is not a recognizable MP4/MOV container.",
            )
        }
    }

    private fun normalizeVideoSegment(segment: BinarySegment): BinarySegment {
        if (looksLikeIsoBaseMedia(segment.bytes, 0)) {
            return segment
        }

        val isoStart = findIsoBaseMediaStart(segment.bytes)
            ?: throw MotionPhotoParseException(
                "No video container start was found inside the MotionPhoto item.",
            )
        val samsungFooterStart = findSamsungFooterStart(segment.bytes)
        val isoEnd = samsungFooterStart?.takeIf { it > isoStart } ?: segment.bytes.size
        if (isoEnd <= isoStart) {
            throw MotionPhotoParseException("The video boundaries inside the MotionPhoto item are invalid.")
        }

        return BinarySegment(
            bytes = segment.bytes.copyOfRange(isoStart, isoEnd),
            mimeType = segment.mimeType,
            startOffset = segment.startOffset + isoStart,
            endOffset = segment.startOffset + isoEnd,
        )
    }

    private fun validateGainMapSegment(segment: BinarySegment) {
        if (segment.mimeType.contains("jpeg", ignoreCase = true) && !looksLikeJpeg(segment.bytes, 0)) {
            throw MotionPhotoParseException("The GainMap segment is not a valid JPEG.")
        }
    }

    private fun determinePrimaryEndOffset(
        sourceBytes: ByteArray,
        imageMimeType: String,
        upperBoundExclusive: Int,
    ): Int {
        if (imageMimeType.contains("jpeg", ignoreCase = true) || looksLikeJpeg(sourceBytes, 0)) {
            return findJpegEndOffset(sourceBytes, 0, upperBoundExclusive) ?: upperBoundExclusive
        }
        return upperBoundExclusive
    }

    private fun findJpegEndOffset(
        sourceBytes: ByteArray,
        startOffset: Int,
        upperBoundExclusive: Int,
    ): Int? {
        // 按 JPEG marker 结构逐段跳转定位顶层 EOI：
        // 裸扫 0xFF 0xD9 会被 EXIF 内嵌缩略图 / ICC 等负载中的字节误伤（主图截断在头部）
        if (startOffset + 2 > upperBoundExclusive) return null
        if (sourceBytes[startOffset] != 0xFF.toByte() || sourceBytes[startOffset + 1] != 0xD8.toByte()) {
            return null
        }
        var offset = startOffset + 2
        while (offset + 1 < upperBoundExclusive) {
            if (sourceBytes[offset] != 0xFF.toByte()) return null
            val marker = sourceBytes[offset + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> offset += 1                    // 填充字节
                marker == 0x00 -> return null                    // 段结构中非法（填充只出现在扫描数据里）
                marker == 0xD9 -> return offset + 2              // 顶层 EOI
                marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7 -> offset += 2  // 独立标记
                marker == 0xDA -> {                              // SOS：长度头 + 熵编码数据
                    if (offset + 4 > upperBoundExclusive) return null
                    val headerLen = ((sourceBytes[offset + 2].toInt() and 0xFF) shl 8) or
                        (sourceBytes[offset + 3].toInt() and 0xFF)
                    var p = offset + 2 + headerLen
                    if (headerLen < 2 || p > upperBoundExclusive) return null
                    var nextMarker = -1
                    while (p + 1 < upperBoundExclusive) {
                        val b = sourceBytes[p].toInt() and 0xFF
                        val n = sourceBytes[p + 1].toInt() and 0xFF
                        // FF00 是字节填充、FFD0-D7 是重启标记：都不是段边界
                        if (b == 0xFF && n != 0x00 && n !in 0xD0..0xD7) {
                            nextMarker = p
                            break
                        }
                        p++
                    }
                    if (nextMarker < 0) return null
                    offset = nextMarker
                }
                else -> {                                        // 长度前缀段：整体跳过
                    if (offset + 4 > upperBoundExclusive) return null
                    val len = ((sourceBytes[offset + 2].toInt() and 0xFF) shl 8) or
                        (sourceBytes[offset + 3].toInt() and 0xFF)
                    if (len < 2) return null
                    offset += 2 + len
                }
            }
        }
        return null
    }

    private fun findIsoBaseMediaStart(sourceBytes: ByteArray): Int? {
        for (offset in 0..<(sourceBytes.size - 8).coerceAtLeast(0)) {
            if (looksLikeIsoBaseMedia(sourceBytes, offset)) {
                return offset
            }
        }
        return null
    }

    private fun findSamsungFooterStart(sourceBytes: ByteArray): Int? {
        return indexOfSubarray(sourceBytes, "SEFH".toByteArray(StandardCharsets.US_ASCII))
    }
}
