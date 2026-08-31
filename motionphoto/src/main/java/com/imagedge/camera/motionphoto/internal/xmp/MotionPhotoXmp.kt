package com.imagedge.camera.motionphoto.internal.xmp

import com.imagedge.camera.motionphoto.ContainerItem
import com.imagedge.camera.motionphoto.XmpSummary
import java.nio.charset.Charset
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import com.imagedge.camera.motionphoto.internal.format.indexOfSubarray

internal const val HDRGM_NAMESPACE = "http://ns.adobe.com/hdr-gain-map/1.0/"

internal data class ContainerXmpSummary(
    val hdrgmVersion: String?,
    val items: List<ContainerItem>,
)

internal fun extractPreferredMotionPhotoXmp(bytes: ByteArray): String? {
    val packets = extractAllXmpPackets(bytes)
    return packets.firstOrNull(::looksLikeMotionPhotoXmp) ?: packets.firstOrNull()
}

internal fun decodeXmp(bytes: ByteArray): String {
    val charset = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
        bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte() -> Charset.forName("UTF-16BE")
        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte() -> Charset.forName("UTF-16LE")
        else -> Charsets.UTF_8
    }
    return bytes.toString(charset).replace("\u0000", "")
}

internal fun looksLikeMotionPhotoXmp(xmp: String): Boolean {
    return xmp.contains("GCamera:MotionPhoto") ||
        xmp.contains("Camera:MotionPhoto") ||
        xmp.contains("MicroVideoOffset") ||
        runCatching {
            parseContainerXmp(xmp).items.any { item ->
                item.semantic.equals("MotionPhoto", ignoreCase = true)
            }
        }.getOrDefault(false)
}

internal fun isMotionPhotoXmp(xmp: String): Boolean {
    if (
        motionPhotoFlagPattern.containsMatchIn(xmp) ||
        microVideoOffsetPattern.containsMatchIn(xmp) ||
        motionPhotoItemPattern.containsMatchIn(xmp)
    ) {
        return true
    }

    return runCatching {
        val summary = parseMotionPhotoXmp(xmp)
        summary.motionPhotoFlag == 1 ||
            (summary.microVideoOffset ?: 0) > 0 ||
            summary.items.any { item ->
                item.semantic.equals("MotionPhoto", ignoreCase = true)
            }
    }.getOrDefault(false)
}

private val motionPhotoFlagPattern = Regex(
    pattern = """(?:GCamera:|Camera:)?MotionPhoto\s*=\s*["']1["']""",
)

private val microVideoOffsetPattern = Regex(
    pattern = """(?:GCamera:|Camera:)?MicroVideoOffset\s*=\s*["'][1-9]\d*["']""",
)

private val motionPhotoItemPattern = Regex(
    pattern = """(?:Item:)?(?:Semantic|ItemSemantic)\s*=\s*["']MotionPhoto["']""",
    option = RegexOption.IGNORE_CASE,
)

internal fun looksLikeUltraHdrXmp(xmp: String): Boolean {
    return runCatching {
        val summary = parseContainerXmp(xmp)
        summary.hdrgmVersion != null ||
            summary.items.any { item -> item.semantic.equals("GainMap", ignoreCase = true) }
    }.getOrDefault(false)
}

internal fun parseContainerXmp(xmp: String): ContainerXmpSummary {
    val items = mutableListOf<ContainerItem>()
    var hdrgmVersion: String? = null

    val parser = newXmpPullParser(xmp)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
            for (index in 0 until parser.attributeCount) {
                if (
                    hdrgmVersion == null &&
                    parser.getAttributeLocalName(index) == "Version" &&
                    parser.getAttributeNamespace(index) == HDRGM_NAMESPACE
                ) {
                    hdrgmVersion = parser.getAttributeValue(index)
                }
            }
            parser.collectAttributes().toContainerItemOrNull()?.let(items::add)
        }
        eventType = parser.next()
    }

    return ContainerXmpSummary(
        hdrgmVersion = hdrgmVersion,
        items = items,
    )
}

internal fun parseMotionPhotoXmp(xmp: String): XmpSummary {
    val containerSummary = parseContainerXmp(xmp)
    var motionPhotoFlag: Int? = null
    var motionPhotoVersion: Int? = null
    var presentationTimestampUs: Long? = null
    var microVideoOffset: Int? = null

    val parser = newXmpPullParser(xmp)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
            val attrs = parser.collectAttributes()

            attrs["MotionPhoto"]?.toIntOrNull()?.let { motionPhotoFlag = it }
            attrs["MotionPhotoVersion"]?.toIntOrNull()?.let { motionPhotoVersion = it }
            attrs["MotionPhotoPresentationTimestampUs"]?.toLongOrNull()?.let {
                presentationTimestampUs = it
            }
            attrs["MicroVideoOffset"]?.toIntOrNull()?.let { microVideoOffset = it }
        }
        eventType = parser.next()
    }

    return XmpSummary(
        motionPhotoFlag = motionPhotoFlag,
        motionPhotoVersion = motionPhotoVersion,
        presentationTimestampUs = presentationTimestampUs,
        microVideoOffset = microVideoOffset,
        items = containerSummary.items,
    )
}

internal fun newXmpPullParser(xmp: String): XmlPullParser {
    return XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
    }.newPullParser().apply {
        setInput(xmp.reader())
    }
}

internal fun XmlPullParser.collectAttributes(): Map<String, String> {
    return buildMap {
        for (index in 0 until attributeCount) {
            put(getAttributeLocalName(index), getAttributeValue(index))
        }
    }
}

private fun XmlPullParser.getAttributeLocalName(index: Int): String {
    return getAttributeName(index).substringAfter(':')
}

internal fun Map<String, String>.toContainerItemOrNull(): ContainerItem? {
    val semantic = this["Semantic"] ?: this["ItemSemantic"]
    val mimeType = this["Mime"] ?: this["ItemMime"]
    val length = (this["Length"] ?: this["ItemLength"])?.toIntOrNull()
    val padding = (this["Padding"] ?: this["ItemPadding"])?.toIntOrNull()
    if (semantic == null && mimeType == null && length == null && padding == null) {
        return null
    }
    return ContainerItem(
        semantic = semantic,
        mimeType = mimeType,
        length = length,
        padding = padding,
    )
}

internal fun extractAllXmpPackets(bytes: ByteArray): List<String> {
    val startMarker = "<x:xmpmeta".toByteArray(Charsets.UTF_8)
    val endMarker = "</x:xmpmeta>".toByteArray(Charsets.UTF_8)
    val packets = mutableListOf<String>()
    var searchFrom = 0
    while (searchFrom < bytes.size) {
        val start = indexOfSubarray(bytes, startMarker, searchFrom)
        if (start == -1) {
            break
        }
        val end = indexOfSubarray(bytes, endMarker, start)
        if (end == -1) {
            break
        }

        packets += decodeXmp(bytes.copyOfRange(start, end + endMarker.size))
        searchFrom = end + endMarker.size
    }
    return packets
}
