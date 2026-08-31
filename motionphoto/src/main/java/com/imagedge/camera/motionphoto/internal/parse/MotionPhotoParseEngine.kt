package com.imagedge.camera.motionphoto.internal.parse

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.imagedge.camera.motionphoto.MotionPhotoParseException
import com.imagedge.camera.motionphoto.MotionPhotoParseResult
import com.imagedge.camera.motionphoto.internal.format.extensionForMime
import com.imagedge.camera.motionphoto.internal.io.MotionPhotoTempFiles
import com.imagedge.camera.motionphoto.internal.xmp.decodeXmp
import com.imagedge.camera.motionphoto.internal.xmp.extractPreferredMotionPhotoXmp
import com.imagedge.camera.motionphoto.internal.xmp.parseMotionPhotoXmp
import java.io.ByteArrayInputStream
import java.io.File

internal object MotionPhotoParseEngine {
    fun parse(
        context: Context,
        sourceUri: Uri,
    ): MotionPhotoParseResult {
        val sourceBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: throw MotionPhotoParseException("Failed to read the selected file.")

        val xmp = readMotionPhotoXmp(sourceBytes)
        val xmpSummary = parseMotionPhotoXmp(xmp)
        val extraction = MotionPhotoSegmentExtractor.extract(sourceBytes, xmpSummary)

        val outputDir = MotionPhotoTempFiles.resetCacheDirectory(context.cacheDir, "motion-photo-parse")
        val fileId = MotionPhotoTempFiles.newExtractionFileId()
        val imageFile = writeSegment(
            outputDir = outputDir,
            fileName = "$fileId.${extensionForMime(extraction.image.mimeType)}",
            segment = extraction.image,
        )
        val videoFile = writeSegment(
            outputDir = outputDir,
            fileName = "$fileId-motion.${extensionForMime(extraction.video.mimeType)}",
            segment = extraction.video,
        )
        val gainMapFile = extraction.gainMap?.let { gainMap ->
            writeSegment(
                outputDir = outputDir,
                fileName = "$fileId-gainmap.${extensionForMime(gainMap.mimeType)}",
                segment = gainMap,
            )
        }
        val gainMapSummary = extraction.gainMap?.let { MotionPhotoGainMapMetadataParser.parse(it.bytes) }

        return MotionPhotoParseResult(
            imageFile = imageFile,
            videoFile = videoFile,
            gainMapFile = gainMapFile,
            sourceBytes = sourceBytes.size,
            imageBytes = extraction.image.bytes.size,
            videoBytes = extraction.video.bytes.size,
            gainMapBytes = extraction.gainMap?.bytes?.size,
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
    }

    private fun readMotionPhotoXmp(sourceBytes: ByteArray): String {
        return extractPreferredMotionPhotoXmp(sourceBytes)
            ?: ExifInterface(ByteArrayInputStream(sourceBytes))
                .getAttributeBytes(ExifInterface.TAG_XMP)
                ?.let(::decodeXmp)
            ?: throw MotionPhotoParseException(
                "No XMP metadata was found, so the video cannot be located using Motion Photo rules.",
            )
    }

    private fun writeSegment(
        outputDir: File,
        fileName: String,
        segment: BinarySegment,
    ): File {
        return File(outputDir, fileName).apply {
            writeBytes(segment.bytes)
        }
    }
}
