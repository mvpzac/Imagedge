package com.imagedge.camera.motionphoto.internal.parse

import androidx.exifinterface.media.ExifInterface
import com.imagedge.camera.motionphoto.GainMapSummary
import com.imagedge.camera.motionphoto.internal.xmp.collectAttributes
import com.imagedge.camera.motionphoto.internal.xmp.decodeXmp
import com.imagedge.camera.motionphoto.internal.xmp.newXmpPullParser
import java.io.ByteArrayInputStream
import org.xmlpull.v1.XmlPullParser

internal object MotionPhotoGainMapMetadataParser {
    fun parse(bytes: ByteArray): GainMapSummary? {
        val gainMapXmp = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeBytes(ExifInterface.TAG_XMP)
            ?.let(::decodeXmp)
            ?: return null

        var version: String? = null
        var baseRenditionIsHDR: String? = null
        var gainMapMin: String? = null
        var gainMapMax: String? = null
        var gamma: String? = null
        var offsetSdr: String? = null
        var offsetHdr: String? = null
        var hdrCapacityMin: String? = null
        var hdrCapacityMax: String? = null

        val parser = newXmpPullParser(gainMapXmp)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val attrs = parser.collectAttributes()
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
            eventType = parser.next()
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
