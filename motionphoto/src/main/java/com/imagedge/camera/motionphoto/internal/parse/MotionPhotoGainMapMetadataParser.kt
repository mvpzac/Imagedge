package com.imagedge.camera.motionphoto.internal.parse

import androidx.exifinterface.media.ExifInterface
import com.imagedge.camera.motionphoto.GainMapSummary
import com.imagedge.camera.motionphoto.internal.xmp.decodeXmp
import com.imagedge.camera.motionphoto.internal.xmp.parseXmpAttributeMaps
import java.io.ByteArrayInputStream
import java.io.File

internal object MotionPhotoGainMapMetadataParser {
    fun parse(file: File): GainMapSummary? {
        val gainMapXmp = ExifInterface(file.absolutePath)
            .getAttributeBytes(ExifInterface.TAG_XMP)
            ?.let(::decodeXmp)
            ?: return null
        return parseXmp(gainMapXmp)
    }

    fun parse(bytes: ByteArray): GainMapSummary? {
        val gainMapXmp = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeBytes(ExifInterface.TAG_XMP)
            ?.let(::decodeXmp)
            ?: return null

        return parseXmp(gainMapXmp)
    }

    private fun parseXmp(gainMapXmp: String): GainMapSummary {
        var version: String? = null
        var baseRenditionIsHDR: String? = null
        var gainMapMin: String? = null
        var gainMapMax: String? = null
        var gamma: String? = null
        var offsetSdr: String? = null
        var offsetHdr: String? = null
        var hdrCapacityMin: String? = null
        var hdrCapacityMax: String? = null

        for (attrs in parseXmpAttributeMaps(gainMapXmp)) {
            version = version ?: attrs["Version"]
            baseRenditionIsHDR = baseRenditionIsHDR ?: attrs["BaseRenditionIsHDR"]
            gainMapMin = gainMapMin ?: attrs["GainMapMin"]
            gainMapMax = gainMapMax ?: attrs["GainMapMax"]
            gamma = gamma ?: attrs["Gamma"]
            offsetSdr = offsetSdr ?: attrs["OffsetSDR"]
            offsetHdr = offsetHdr ?: attrs["OffsetHDR"]
            hdrCapacityMin = hdrCapacityMin ?: attrs["HDRCapacityMin"]
            hdrCapacityMax = hdrCapacityMax ?: attrs["HDRCapacityMax"]
        }

        return GainMapSummary(
            version = version,
            baseRenditionIsHDR = baseRenditionIsHDR,
            gainMapMin = gainMapMin,
            gainMapMax = gainMapMax,
            gamma = gamma,
            offsetSdr = offsetSdr,
            offsetHdr = offsetHdr,
            hdrCapacityMin = hdrCapacityMin,
            hdrCapacityMax = hdrCapacityMax,
        )
    }
}
