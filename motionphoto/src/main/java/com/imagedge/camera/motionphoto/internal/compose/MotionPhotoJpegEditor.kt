package com.imagedge.camera.motionphoto.internal.compose

import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.internal.xmp.extractAllXmpPackets
import com.imagedge.camera.motionphoto.internal.xmp.extractPreferredMotionPhotoXmp
import com.imagedge.camera.motionphoto.internal.xmp.looksLikeMotionPhotoXmp
import com.imagedge.camera.motionphoto.internal.xmp.looksLikeUltraHdrXmp
import com.imagedge.camera.motionphoto.internal.xmp.MotionPhotoVendorXmpBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object MotionPhotoJpegEditor {
    private const val OPLUS_USER_COMMENT = "oplus_10485792"
    private const val XMP_APP1_MARKER = 0xE1
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOI = 0xD8
    private const val JPEG_SOS = 0xDA
    private const val JPEG_EOI = 0xD9
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    // Header rewriting temporarily needs several JPEG-sized buffers. Keep the primary-image cap
    // conservative; the appended video is streamed and is not included in this limit.
    private const val MAX_PRIMARY_JPEG_BYTES = 64L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 256 * 1024

    fun alignVendorCompatibleMotionPhotoXmp(
        motionPhotoFile: File,
        videoLengthBytes: Long,
        videoMimeType: String,
        presentationTimestampUs: Long,
        ultraHdrInfo: UltraHdrInfo?,
    ) {
        rewritePrimaryJpeg(motionPhotoFile, videoLengthBytes) { sourceBytes ->
            val currentMotionPhotoXmp = extractPreferredMotionPhotoXmp(sourceBytes)
                ?: throw MotionPhotoComposeException("No Motion Photo XMP was found in the Media3 output.")
            val alignedXmp = MotionPhotoVendorXmpBuilder.buildAlignedXmp(
                currentXmp = currentMotionPhotoXmp,
                videoLengthBytes = videoLengthBytes,
                videoMimeType = videoMimeType,
                presentationTimestampUs = presentationTimestampUs,
                gainMapLengthBytes = ultraHdrInfo?.gainMapLengthBytes,
                hdrgmVersion = ultraHdrInfo?.hdrgmVersion,
            )
            findStandaloneUltraHdrXmpPacket(sourceBytes)?.let { ultraHdrXmp ->
                removeJpegXmpPacket(
                    // 合并路径只按精确匹配定位 UltraHDR 段，避免先替换错段。
                    replaceJpegXmpPacket(
                        sourceBytes,
                        ultraHdrXmp,
                        alignedXmp,
                        matchMotionPhotoLike = false,
                    ),
                    currentMotionPhotoXmp,
                )
            } ?: replaceJpegXmpPacket(sourceBytes, currentMotionPhotoXmp, alignedXmp)
        }
    }

    fun alignWechatCompatibleJpegHeaders(
        motionPhotoFile: File,
        videoLengthBytes: Long,
    ) {
        rewritePrimaryJpeg(motionPhotoFile, videoLengthBytes) { jpegBytes ->
            val segments = parseJpegSegments(jpegBytes)
            val xmpSegment = segments.firstOrNull { it.kind == JpegSegmentKind.MOTION_PHOTO_XMP }
                ?: throw MotionPhotoComposeException("No Motion Photo XMP segment was found to preserve.")
            val jfifSegment = segments.firstOrNull { it.kind == JpegSegmentKind.JFIF }
            val iccSegments = segments.filter { it.kind == JpegSegmentKind.ICC }
            val passthroughSegments = segments.filter {
                it.kind == JpegSegmentKind.OTHER_APP || it.kind == JpegSegmentKind.STRUCTURAL
            }
            val dimensions = readJpegDimensions(segments)
            val exifSegment = buildWechatLikeExifSegment(dimensions.first, dimensions.second)

            val reorderedSegments = buildList {
                add(exifSegment)
                add(xmpSegment.bytes)
                jfifSegment?.let { add(it.bytes) }
                addAll(iccSegments.map { it.bytes })
                addAll(passthroughSegments.map { it.bytes })
            }
            val sosAndCompressedData = jpegBytes.copyOfRange(segments.last().endOffset, jpegBytes.size)
            val provisionalImageLength = 2 + reorderedSegments.sumOf { it.size } + sosAndCompressedData.size
            // MP Entry 的 Individual Image Size 必须包含 MPF 段本身。
            val mpfSegmentSize = buildWechatLikeMpfSegment(0).size
            val mpfSegment = buildWechatLikeMpfSegment(provisionalImageLength + mpfSegmentSize)
            val finalSegments = buildList {
                add(exifSegment)
                add(xmpSegment.bytes)
                jfifSegment?.let { add(it.bytes) }
                add(mpfSegment)
                addAll(iccSegments.map { it.bytes })
                addAll(passthroughSegments.map { it.bytes })
            }

            val rebuiltImage = ByteArrayOutputStream(provisionalImageLength).apply {
                write(0xFF)
                write(JPEG_SOI)
                finalSegments.forEach(::write)
                write(sosAndCompressedData)
            }.toByteArray()
            rebuiltImage
        }
    }

    fun readPreferredMotionPhotoXmp(motionPhotoFile: File, videoLengthBytes: Long): String? =
        extractPreferredMotionPhotoXmp(readPrimaryJpeg(motionPhotoFile, videoLengthBytes))

    /**
     * 只在内存中编辑 JPEG 主图；后缀 MP4 从原文件流式复制到临时文件。
     * 临时文件完整落盘后再替换，异常时不会留下半个成品。
     */
    private inline fun rewritePrimaryJpeg(
        motionPhotoFile: File,
        videoLengthBytes: Long,
        transform: (ByteArray) -> ByteArray,
    ) {
        val originalLength = motionPhotoFile.length()
        val imageLength = checkedImageLength(originalLength, videoLengthBytes)
        val rewrittenImage = transform(readPrimaryJpeg(motionPhotoFile, videoLengthBytes))
        val temporary = File(
            motionPhotoFile.parentFile,
            ".${motionPhotoFile.name}.${UUID.randomUUID()}.rewrite",
        )
        try {
            FileOutputStream(temporary).use { output ->
                output.write(rewrittenImage)
                FileInputStream(motionPhotoFile).use { input ->
                    input.channel.position(imageLength)
                    copyExactly(input, output, videoLengthBytes)
                }
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    motionPhotoFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    motionPhotoFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readPrimaryJpeg(file: File, videoLengthBytes: Long): ByteArray {
        val imageLength = checkedImageLength(file.length(), videoLengthBytes)
        if (imageLength > MAX_PRIMARY_JPEG_BYTES || imageLength > Int.MAX_VALUE.toLong()) {
            throw MotionPhotoComposeException("The primary JPEG is too large to edit safely.")
        }
        val result = ByteArray(imageLength.toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < result.size) {
                val read = input.read(result, offset, result.size - offset)
                if (read < 0) throw IOException("Unexpected end of Motion Photo JPEG.")
                if (read == 0) continue
                offset += read
            }
        }
        return result
    }

    private fun checkedImageLength(fileLength: Long, videoLengthBytes: Long): Long {
        if (videoLengthBytes < 0L || videoLengthBytes >= fileLength) {
            throw MotionPhotoComposeException(
                "Failed to calculate the primary JPEG range from the video length.",
            )
        }
        return fileLength - videoLengthBytes
    }

    private fun copyExactly(input: FileInputStream, output: FileOutputStream, byteCount: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = byteCount
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("Unexpected end of Motion Photo video.")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun findStandaloneUltraHdrXmpPacket(jpegBytes: ByteArray): String? {
        return extractAllXmpPackets(jpegBytes).firstOrNull { xmp ->
            looksLikeUltraHdrXmp(xmp) && !looksLikeMotionPhotoXmp(xmp)
        }
    }

    private fun replaceJpegXmpPacket(
        jpegBytes: ByteArray,
        currentXmp: String,
        replacementXmp: String,
        matchMotionPhotoLike: Boolean = true,
    ): ByteArray {
        val xmpHeaderBytes = XMP_HEADER.toByteArray(Charsets.US_ASCII)
        val replacementXmpBytes = replacementXmp.toByteArray(Charsets.UTF_8)
        val replacementPayloadSize = xmpHeaderBytes.size + replacementXmpBytes.size
        if (replacementPayloadSize + 2 > 0xFFFF) {
            throw MotionPhotoComposeException(
                "The rewritten XMP is too large to fit into a single JPEG APP1 segment.",
            )
        }

        val normalizedCurrentXmp = normalizeXmpPacket(currentXmp)
        var offset = 0
        while (offset + 1 < jpegBytes.size) {
            val prefix = jpegBytes[offset].toInt() and 0xFF
            val marker = jpegBytes[offset + 1].toInt() and 0xFF
            if (prefix != JPEG_MARKER_PREFIX) {
                offset++
                continue
            }
            if (marker == JPEG_SOI) {
                offset += 2
                continue
            }
            if (marker == JPEG_SOS || marker == JPEG_EOI) {
                break
            }
            if (offset + 3 >= jpegBytes.size) {
                break
            }

            val segmentLength = ((jpegBytes[offset + 2].toInt() and 0xFF) shl 8) or
                (jpegBytes[offset + 3].toInt() and 0xFF)
            if (segmentLength < 2) {
                break
            }
            val segmentEnd = offset + 2 + segmentLength
            if (segmentEnd > jpegBytes.size) {
                break
            }

            val payloadStart = offset + 4
            if (
                marker == XMP_APP1_MARKER &&
                payloadStart + xmpHeaderBytes.size <= segmentEnd &&
                jpegBytes.copyOfRange(payloadStart, payloadStart + xmpHeaderBytes.size)
                    .contentEquals(xmpHeaderBytes)
            ) {
                val currentPayload =
                    jpegBytes.copyOfRange(payloadStart + xmpHeaderBytes.size, segmentEnd)
                val currentPacket = currentPayload.toString(Charsets.UTF_8).trimEnd('\u0000')
                val normalizedPacket = normalizeXmpPacket(currentPacket)
                val isTargetPacket = (matchMotionPhotoLike && looksLikeMotionPhotoXmp(currentPacket)) ||
                    normalizedPacket == normalizedCurrentXmp
                if (!isTargetPacket) {
                    offset = segmentEnd
                    continue
                }

                val replacementLength = replacementPayloadSize + 2
                val output = ByteArrayOutputStream(
                    jpegBytes.size + replacementXmpBytes.size - currentPayload.size,
                )
                output.write(jpegBytes, 0, offset)
                output.write(JPEG_MARKER_PREFIX)
                output.write(XMP_APP1_MARKER)
                output.write((replacementLength shr 8) and 0xFF)
                output.write(replacementLength and 0xFF)
                output.write(xmpHeaderBytes)
                output.write(replacementXmpBytes)
                output.write(jpegBytes, segmentEnd, jpegBytes.size - segmentEnd)
                return output.toByteArray()
            }
            offset = segmentEnd
        }

        throw MotionPhotoComposeException("No writable XMP APP1 segment was found in the JPEG.")
    }

    private fun removeJpegXmpPacket(
        jpegBytes: ByteArray,
        targetXmp: String,
    ): ByteArray {
        val xmpHeaderBytes = XMP_HEADER.toByteArray(Charsets.US_ASCII)
        val normalizedTargetXmp = normalizeXmpPacket(targetXmp)
        var offset = 0
        while (offset + 1 < jpegBytes.size) {
            val prefix = jpegBytes[offset].toInt() and 0xFF
            val marker = jpegBytes[offset + 1].toInt() and 0xFF
            if (prefix != JPEG_MARKER_PREFIX) {
                offset++
                continue
            }
            if (marker == JPEG_SOI) {
                offset += 2
                continue
            }
            if (marker == JPEG_SOS || marker == JPEG_EOI) {
                break
            }
            if (offset + 3 >= jpegBytes.size) {
                break
            }

            val segmentLength = ((jpegBytes[offset + 2].toInt() and 0xFF) shl 8) or
                (jpegBytes[offset + 3].toInt() and 0xFF)
            if (segmentLength < 2) {
                break
            }
            val segmentEnd = offset + 2 + segmentLength
            if (segmentEnd > jpegBytes.size) {
                break
            }

            val payloadStart = offset + 4
            if (
                marker == XMP_APP1_MARKER &&
                payloadStart + xmpHeaderBytes.size <= segmentEnd &&
                jpegBytes.copyOfRange(payloadStart, payloadStart + xmpHeaderBytes.size)
                    .contentEquals(xmpHeaderBytes)
            ) {
                val currentPayload =
                    jpegBytes.copyOfRange(payloadStart + xmpHeaderBytes.size, segmentEnd)
                val currentPacket = currentPayload.toString(Charsets.UTF_8).trimEnd('\u0000')
                if (normalizeXmpPacket(currentPacket) == normalizedTargetXmp) {
                    val output = ByteArrayOutputStream(jpegBytes.size - (segmentEnd - offset))
                    output.write(jpegBytes, 0, offset)
                    output.write(jpegBytes, segmentEnd, jpegBytes.size - segmentEnd)
                    return output.toByteArray()
                }
            }
            offset = segmentEnd
        }

        throw MotionPhotoComposeException("No removable XMP APP1 segment was found in the JPEG.")
    }

    private fun normalizeXmpPacket(xmp: String): String {
        return xmp
            .trim()
            .trimEnd('\u0000')
            .replace(Regex(">\\s+<"), "><")
            .replace(Regex("\\s+"), " ")
    }

    private fun parseJpegSegments(jpegBytes: ByteArray): List<JpegSegment> {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != JPEG_SOI.toByte()) {
            throw MotionPhotoComposeException("The primary image is not a valid JPEG.")
        }

        val segments = mutableListOf<JpegSegment>()
        var offset = 2
        while (offset + 4 <= jpegBytes.size) {
            if (jpegBytes[offset] != JPEG_MARKER_PREFIX.toByte()) {
                throw MotionPhotoComposeException("The JPEG segment structure is invalid.")
            }
            val marker = jpegBytes[offset + 1].toInt() and 0xFF
            if (marker == JPEG_SOS || marker == JPEG_EOI) {
                break
            }
            if (marker == 0x01 || marker == JPEG_SOI || marker in 0xD0..0xD7) {
                // 独立标记（TEM / SOI / RSTn）无长度字段，直接跳过，
                // 否则会把后续字节误当长度解析、读到垃圾值后整体失败
                offset += 2
                continue
            }

            val length = ((jpegBytes[offset + 2].toInt() and 0xFF) shl 8) or
                (jpegBytes[offset + 3].toInt() and 0xFF)
            if (length < 2) {
                throw MotionPhotoComposeException("The JPEG segment length is invalid.")
            }
            val endOffset = offset + 2 + length
            if (endOffset > jpegBytes.size) {
                throw MotionPhotoComposeException("The JPEG segment exceeds the file bounds.")
            }

            val bytes = jpegBytes.copyOfRange(offset, endOffset)
            val payloadStart = offset + 4
            val payload = jpegBytes.copyOfRange(payloadStart, endOffset)
            val kind = classifyJpegSegment(marker, payload)
            segments += JpegSegment(kind, bytes, marker, endOffset)
            offset = endOffset
        }
        return segments
    }

    private fun classifyJpegSegment(
        marker: Int,
        payload: ByteArray,
    ): JpegSegmentKind {
        return when (marker) {
            0xE0 if payload.startsWith("JFIF\u0000".toByteArray(Charsets.US_ASCII)) ->
                JpegSegmentKind.JFIF

            XMP_APP1_MARKER if payload.startsWith(XMP_HEADER.toByteArray(Charsets.US_ASCII)) &&
                    looksLikeMotionPhotoXmp(
                        payload.copyOfRange(XMP_HEADER.length, payload.size).toString(Charsets.UTF_8),
                    ) -> JpegSegmentKind.MOTION_PHOTO_XMP

            XMP_APP1_MARKER if payload.startsWith("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)) ->
                JpegSegmentKind.EXIF

            0xE2 if payload.startsWith("MPF\u0000".toByteArray(Charsets.US_ASCII)) ->
                JpegSegmentKind.MPF

            0xE2 if payload.startsWith("ICC_PROFILE".toByteArray(Charsets.US_ASCII)) ->
                JpegSegmentKind.ICC

            in 0xE0..0xEF -> JpegSegmentKind.OTHER_APP
            else -> JpegSegmentKind.STRUCTURAL
        }
    }

    private fun readJpegDimensions(segments: List<JpegSegment>): Pair<Int, Int> {
        val sof = segments.firstOrNull { it.marker == 0xC0 || it.marker == 0xC2 }
            ?: throw MotionPhotoComposeException(
                "The JPEG is missing an SOF segment, so its dimensions cannot be read.",
            )
        val bytes = sof.bytes
        val height = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val width = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
        return width to height
    }

    private fun buildWechatLikeExifSegment(
        width: Int,
        height: Int,
    ): ByteArray {
        val commentBytes = (OPLUS_USER_COMMENT + '\u0000').toByteArray(Charsets.US_ASCII)
        val tiff = ByteArrayOutputStream().apply {
            writeAscii("MM")
            writeShort(0x002A)
            writeInt(8)

            writeShort(4)
            writeShort(0x0100)
            writeShort(3)
            writeInt(1)
            writeShort(width)
            writeShort(0)

            writeShort(0x0101)
            writeShort(3)
            writeInt(1)
            writeShort(height)
            writeShort(0)

            writeShort(0x8769)
            writeShort(4)
            writeInt(1)
            writeInt(0x3E)

            writeShort(0x0112)
            writeShort(3)
            writeInt(1)
            writeShort(0)
            writeShort(0)

            writeInt(0)

            writeShort(2)
            writeShort(0x9286)
            writeShort(2)
            writeInt(commentBytes.size)
            writeInt(0x5C)

            writeShort(0x9208)
            writeShort(4)
            writeInt(1)
            writeInt(0)

            writeInt(0)
            write(commentBytes)
        }.toByteArray()
        return buildJpegSegment(
            marker = XMP_APP1_MARKER,
            payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff,
        )
    }

    private fun buildWechatLikeMpfSegment(imageLength: Int): ByteArray {
        val tiff = ByteArrayOutputStream().apply {
            writeAscii("MM")
            writeShort(0x002A)
            writeInt(8)

            writeShort(3)
            writeShort(0xB000)
            writeShort(7)
            writeInt(4)
            writeAscii("0100")

            writeShort(0xB001)
            writeShort(4)
            writeInt(1)
            writeInt(1)

            writeShort(0xB002)
            writeShort(7)
            writeInt(16)
            writeInt(0x32)

            writeInt(0)

            writeInt(0x0003_0000)
            writeInt(imageLength)
            writeInt(0)
            writeInt(0)
        }.toByteArray()
        return buildJpegSegment(
            marker = 0xE2,
            payload = "MPF\u0000".toByteArray(Charsets.US_ASCII) + tiff,
        )
    }

    private fun buildJpegSegment(
        marker: Int,
        payload: ByteArray,
    ): ByteArray {
        val length = payload.size + 2
        if (length > 0xFFFF) {
            throw MotionPhotoComposeException("The JPEG segment is too large.")
        }
        return ByteArrayOutputStream(payload.size + 4).apply {
            write(JPEG_MARKER_PREFIX)
            write(marker)
            write((length shr 8) and 0xFF)
            write(length and 0xFF)
            write(payload)
        }.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}

internal data class JpegSegment(
    val kind: JpegSegmentKind,
    val bytes: ByteArray,
    val marker: Int,
    val endOffset: Int,
)

internal enum class JpegSegmentKind {
    EXIF,
    MOTION_PHOTO_XMP,
    JFIF,
    MPF,
    ICC,
    OTHER_APP,
    STRUCTURAL,
}
