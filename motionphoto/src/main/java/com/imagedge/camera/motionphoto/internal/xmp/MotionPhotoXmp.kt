package com.imagedge.camera.motionphoto.internal.xmp

import com.imagedge.camera.motionphoto.ContainerItem
import com.imagedge.camera.motionphoto.XmpSummary
import java.io.StringReader
import java.nio.charset.Charset
import com.imagedge.camera.motionphoto.internal.format.indexOfSubarray
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal const val HDRGM_NAMESPACE = "http://ns.adobe.com/hdr-gain-map/1.0/"

internal data class ContainerXmpSummary(
    val hdrgmVersion: String?,
    val items: List<ContainerItem>,
)

private data class ParsedXmp(
    val container: ContainerXmpSummary,
    val motionPhotoFlag: Int?,
    val motionPhotoVersion: Int?,
    val presentationTimestampUs: Long?,
    val microVideoOffset: Long?,
)

private const val MAX_XMP_CHARS = 1024 * 1024
private const val MAX_XMP_ELEMENTS = 20_000
private const val MAX_XMP_DEPTH = 64
private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

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
    return bytes.toString(charset).replace("\uFEFF", "").replace("\u0000", "")
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
    return parseXmp(xmp).container
}

internal fun parseMotionPhotoXmp(xmp: String): XmpSummary {
    val parsed = parseXmp(xmp)
    return XmpSummary(
        motionPhotoFlag = parsed.motionPhotoFlag,
        motionPhotoVersion = parsed.motionPhotoVersion,
        presentationTimestampUs = parsed.presentationTimestampUs,
        microVideoOffset = parsed.microVideoOffset,
        items = parsed.container.items,
    )
}

private fun parseXmp(xmp: String): ParsedXmp {
    val items = mutableListOf<ContainerItem>()
    var hdrgmVersion: String? = null
    var motionPhotoFlag: Int? = null
    var motionPhotoVersion: Int? = null
    var presentationTimestampUs: Long? = null
    var microVideoOffset: Long? = null

    val elements = parseXmpElements(xmp)
    for (element in elements) {
        val attributes = element.collectAttributes()
        for (index in 0 until element.attributes.length) {
            val attribute = element.attributes.item(index)
            if (
                hdrgmVersion == null &&
                attribute.localName == "Version" &&
                attribute.namespaceURI == HDRGM_NAMESPACE
            ) {
                hdrgmVersion = attribute.nodeValue
            }
        }
        attributes.toContainerItemOrNull()?.let(items::add)
        attributes["MotionPhoto"]?.toIntOrNull()?.let { motionPhotoFlag = it }
        attributes["MotionPhotoVersion"]?.toIntOrNull()?.let { motionPhotoVersion = it }
        attributes["MotionPhotoPresentationTimestampUs"]?.toLongOrNull()?.let {
            presentationTimestampUs = it
        }
        attributes["MicroVideoOffset"]?.toLongOrNull()?.let { microVideoOffset = it }
    }

    return ParsedXmp(
        container = ContainerXmpSummary(hdrgmVersion = hdrgmVersion, items = items),
        motionPhotoFlag = motionPhotoFlag,
        motionPhotoVersion = motionPhotoVersion,
        presentationTimestampUs = presentationTimestampUs,
        microVideoOffset = microVideoOffset,
    )
}

/** Securely parses a bounded XMP packet and exposes only its attribute maps to other parsers. */
internal fun parseXmpAttributeMaps(xmp: String): List<Map<String, String>> =
    parseXmpElements(xmp).map(Element::collectAttributes)

private fun parseXmpElements(xmp: String): List<Element> {
    require(xmp.length <= MAX_XMP_CHARS) { "XMP packet is too large." }
    require(!xmp.contains("<!DOCTYPE", ignoreCase = true) &&
        !xmp.contains("<!ENTITY", ignoreCase = true)
    ) { "XMP document type declarations are not allowed." }

    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(ACCESS_EXTERNAL_DTD, "")
        setAttribute(ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xmp)))
    val nodeList = document.getElementsByTagName("*")
    require(nodeList.length <= MAX_XMP_ELEMENTS) { "XMP element count exceeds the limit." }

    return buildList(nodeList.length) {
        for (index in 0 until nodeList.length) {
            val element = nodeList.item(index) as? Element ?: continue
            var depth = 0
            var ancestor = element.parentNode
            while (ancestor != null) {
                depth++
                require(depth <= MAX_XMP_DEPTH) { "XMP nesting depth exceeds the limit." }
                ancestor = ancestor.parentNode
            }
            add(element)
        }
    }
}

private fun Element.collectAttributes(): Map<String, String> {
    return buildMap {
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val localName = attribute.localName ?: attribute.nodeName.substringAfter(':')
            put(localName, attribute.nodeValue)
        }
    }
}

internal fun Map<String, String>.toContainerItemOrNull(): ContainerItem? {
    val semantic = this["Semantic"] ?: this["ItemSemantic"]
    val mimeType = this["Mime"] ?: this["ItemMime"]
    val length = (this["Length"] ?: this["ItemLength"])?.toLongOrNull()
    val padding = (this["Padding"] ?: this["ItemPadding"])?.toLongOrNull()
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
