package com.imagedge.camera.motionphoto.internal.parse

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.imagedge.camera.motionphoto.MotionPhotoParseException
import com.imagedge.camera.motionphoto.MotionPhotoParseResult
import com.imagedge.camera.motionphoto.internal.format.extensionForMime
import com.imagedge.camera.motionphoto.internal.xmp.decodeXmp
import com.imagedge.camera.motionphoto.internal.xmp.extractPreferredMotionPhotoXmp
import com.imagedge.camera.motionphoto.internal.xmp.parseMotionPhotoXmp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal object MotionPhotoParseEngine {
    private const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L * 1024L
    private const val MAX_XMP_SCAN_BYTES = 32 * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val MAX_PARSE_SESSIONS = 8

    fun parse(context: Context, sourceUri: Uri): MotionPhotoParseResult {
        val outputDir = createSessionDirectory(context.cacheDir)
        return try {
            val sourceFile = materializeSource(context, sourceUri, outputDir)
            val sourceLength = sourceFile.length()
            if (sourceLength <= 0L || sourceLength > MAX_SOURCE_BYTES) {
                throw MotionPhotoParseException("The selected file size is outside the supported range.")
            }

            val xmp = readMotionPhotoXmp(sourceFile)
            val xmpSummary = parseMotionPhotoXmp(xmp)
            val extraction = MotionPhotoSegmentExtractor.extract(sourceFile, xmpSummary)
            val fileId = UUID.randomUUID().toString()
            val imageFile = writeSegment(
                outputDir,
                "$fileId.${extensionForMime(extraction.image.mimeType)}",
                extraction.image,
            )
            val videoFile = writeSegment(
                outputDir,
                "$fileId-motion.${extensionForMime(extraction.video.mimeType)}",
                extraction.video,
            )
            val gainMapFile = extraction.gainMap?.let {
                writeSegment(
                    outputDir,
                    "$fileId-gainmap.${extensionForMime(it.mimeType)}",
                    it,
                )
            }
            val gainMapSummary = gainMapFile?.let(MotionPhotoGainMapMetadataParser::parse)

            MotionPhotoParseResult(
                imageFile = imageFile,
                videoFile = videoFile,
                gainMapFile = gainMapFile,
                sourceBytes = sourceLength,
                imageBytes = extraction.image.length,
                videoBytes = extraction.video.length,
                gainMapBytes = extraction.gainMap?.length,
                imageMimeType = extraction.image.mimeType,
                videoMimeType = extraction.video.mimeType,
                gainMapMimeType = extraction.gainMap?.mimeType,
                metadataSource = extraction.metadataSource,
                xmpSummary = xmpSummary,
                gainMapSummary = gainMapSummary,
                imageEndOffset = extraction.image.endOffset,
                videoStartOffset = extraction.video.startOffset,
                gainMapStartOffset = extraction.gainMap?.startOffset,
            )
        } catch (error: Throwable) {
            outputDir.deleteRecursively()
            throw error
        }
    }

    private fun createSessionDirectory(cacheDir: File): File {
        val root = File(cacheDir, "motion-photo-parse").apply { mkdirs() }
        root.listFiles()
            ?.filter(File::isDirectory)
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_PARSE_SESSIONS - 1)
            ?.forEach(File::deleteRecursively)
        return File(root, UUID.randomUUID().toString()).apply {
            if (!mkdirs()) throw MotionPhotoParseException("Failed to create the extraction cache directory.")
        }
    }

    /** content:// 先流式落到私有缓存，以便按 Long 偏移随机访问；全程不构造整文件 ByteArray。 */
    private fun materializeSource(context: Context, sourceUri: Uri, outputDir: File): File {
        if (sourceUri.scheme == "file") {
            val direct = sourceUri.path?.let(::File)
            if (direct?.isFile == true) return direct
        }
        val target = File(outputDir, "source.motion")
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw MotionPhotoParseException("Failed to open the selected file.")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total = try {
                        Math.addExact(total, read.toLong())
                    } catch (_: ArithmeticException) {
                        throw MotionPhotoParseException("The selected file is too large.")
                    }
                    if (total > MAX_SOURCE_BYTES) {
                        throw MotionPhotoParseException("The selected file exceeds the supported size limit.")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        return target
    }

    private fun readMotionPhotoXmp(sourceFile: File): String {
        val prefixSize = minOf(sourceFile.length(), MAX_XMP_SCAN_BYTES.toLong()).toInt()
        val prefix = ByteArray(prefixSize)
        FileInputStream(sourceFile).use { input ->
            var offset = 0
            while (offset < prefix.size) {
                val read = input.read(prefix, offset, prefix.size - offset)
                if (read < 0) break
                if (read == 0) continue
                offset += read
            }
            if (offset != prefix.size) throw IOException("Unexpected end of Motion Photo source.")
        }
        return extractPreferredMotionPhotoXmp(prefix)
            ?: ExifInterface(sourceFile.absolutePath)
                .getAttributeBytes(ExifInterface.TAG_XMP)
                ?.let(::decodeXmp)
            ?: throw MotionPhotoParseException(
                "No XMP metadata was found, so the video cannot be located using Motion Photo rules.",
            )
    }

    private fun writeSegment(outputDir: File, fileName: String, segment: BinarySegment): File {
        val target = File(outputDir, fileName)
        FileInputStream(segment.sourceFile).use { input ->
            input.channel.position(segment.startOffset)
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var remaining = segment.length
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Unexpected end of Motion Photo segment.")
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        }
        return target
    }
}
