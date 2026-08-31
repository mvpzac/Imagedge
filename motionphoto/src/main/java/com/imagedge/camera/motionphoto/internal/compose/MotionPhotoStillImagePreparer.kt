package com.imagedge.camera.motionphoto.internal.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import com.imagedge.camera.motionphoto.internal.format.MotionPhotoMimeSniffer
import com.imagedge.camera.motionphoto.internal.format.looksLikeJpeg
import com.imagedge.camera.motionphoto.internal.xmp.extractAllXmpPackets
import com.imagedge.camera.motionphoto.internal.xmp.parseContainerXmp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal object MotionPhotoStillImagePreparer {
    fun prepare(
        context: Context,
        imageUri: Uri,
        outputDir: File,
        exifSourceUri: Uri? = null,
    ): PreparedImage {
        val sourceMimeType = MotionPhotoMimeSniffer.sniffImageMimeType(context, imageUri)
        val imageBytes: ByteArray
        val sourceHasGainMap: Boolean

        if (sourceMimeType == "image/jpeg") {
            imageBytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: throw MotionPhotoComposeException("Failed to read the image file.")
            sourceHasGainMap = extractUltraHdrInfoFromJpeg(imageBytes) != null
        } else {
            val bitmap = decodeBitmapForStillImage(context, imageUri)
            sourceHasGainMap = bitmapHasGainMap(bitmap)
            imageBytes = try {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)) {
                        throw MotionPhotoComposeException("Failed to convert the image to JPEG.")
                    }
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        val ultraHdrInfo = extractUltraHdrInfoFromJpeg(imageBytes)
        val outputFile = File(outputDir, "input_${UUID.randomUUID().toString().take(8)}.jpg").apply {
            writeBytes(imageBytes)
        }
        // EXIF 保留：把源素材（原图片/视频）的拍摄信息注入封面 JPEG，
        // 使成品在系统相册中能显示与源一致的机型/参数/时间（用户需求，见 ExifPreserver）。
        if (exifSourceUri != null) {
            MotionPhotoExifPreserver.injectExifFrom(context, exifSourceUri, outputFile)
        }
        return PreparedImage(
            preparedFile = outputFile,
            sourceMimeType = sourceMimeType,
            sourceHasGainMap = sourceHasGainMap,
            outputHasGainMap = ultraHdrInfo != null,
            ultraHdrInfo = ultraHdrInfo,
            processingDescription = describePreparedImage(
                sourceMimeType = sourceMimeType,
                sourceWasReused = sourceMimeType == "image/jpeg",
                sourceHasGainMap = sourceHasGainMap,
                outputHasGainMap = ultraHdrInfo != null,
            ),
        )
    }

    private fun decodeBitmapForStillImage(
        context: Context,
        imageUri: Uri,
    ): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, imageUri)
        return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun bitmapHasGainMap(bitmap: Bitmap): Boolean {
        return Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()
    }

    private fun describePreparedImage(
        sourceMimeType: String,
        sourceWasReused: Boolean,
        sourceHasGainMap: Boolean,
        outputHasGainMap: Boolean,
    ): String {
        if (sourceWasReused) {
            return if (outputHasGainMap) {
                "Reuse the original JPEG as-is and preserve its Ultra HDR gain map."
            } else {
                "Reuse the original JPEG as-is."
            }
        }

        return when {
            outputHasGainMap ->
                "Decode the source $sourceMimeType and re-encode it as an Ultra HDR JPEG."
            sourceHasGainMap ->
                "Decode the source $sourceMimeType, but the platform re-encode path fell back to a standard JPEG without gain map metadata."
            else ->
                "Decode the source $sourceMimeType and re-encode it as a standard JPEG."
        }
    }

    private fun extractUltraHdrInfoFromJpeg(jpegBytes: ByteArray): UltraHdrInfo? {
        if (!looksLikeJpeg(jpegBytes, 0)) {
            return null
        }
        return extractAllXmpPackets(jpegBytes)
            .asSequence()
            .mapNotNull { xmp ->
                val summary = runCatching { parseContainerXmp(xmp) }.getOrNull() ?: return@mapNotNull null
                val gainMapItem = summary.items.firstOrNull { item ->
                    item.semantic.equals("GainMap", ignoreCase = true)
                } ?: return@mapNotNull null
                val gainMapLength = gainMapItem.length ?: return@mapNotNull null
                UltraHdrInfo(
                    gainMapLengthBytes = gainMapLength,
                    hdrgmVersion = summary.hdrgmVersion,
                )
            }
            .firstOrNull()
    }
}
