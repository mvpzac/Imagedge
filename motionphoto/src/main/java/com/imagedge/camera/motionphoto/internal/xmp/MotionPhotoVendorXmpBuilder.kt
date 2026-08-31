package com.imagedge.camera.motionphoto.internal.xmp

import com.imagedge.camera.motionphoto.MotionPhotoComposeException
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

internal object MotionPhotoVendorXmpBuilder {
    private const val G_CAMERA_NAMESPACE = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NAMESPACE = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NAMESPACE = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val OPLUS_CAMERA_NAMESPACE = "http://ns.oplus.com/photos/1.0/camera/"
    private const val MI_CAMERA_NAMESPACE = "http://ns.xiaomi.com/photos/1.0/camera/"
    private const val XMLNS_NAMESPACE = "http://www.w3.org/2000/xmlns/"
    private const val MI_CAMERA_XMP_META =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"

    fun buildAlignedXmp(
        currentXmp: String,
        videoLengthBytes: Long,
        videoMimeType: String,
        presentationTimestampUs: Long,
        gainMapLengthBytes: Int?,
        hdrgmVersion: String?,
    ): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder()
            .parse(InputSource(StringReader(currentXmp)))
        val descriptions = document.getElementsByTagNameNS(RDF_NAMESPACE, "Description")
        val description = descriptions.item(0)?.let { it as? org.w3c.dom.Element }
            ?: throw MotionPhotoComposeException("The Motion Photo XMP is missing rdf:Description.")

        description.setAttributeNS(XMLNS_NAMESPACE, "xmlns:GCamera", G_CAMERA_NAMESPACE)
        description.setAttributeNS(XMLNS_NAMESPACE, "xmlns:Container", CONTAINER_NAMESPACE)
        description.setAttributeNS(XMLNS_NAMESPACE, "xmlns:Item", ITEM_NAMESPACE)
        description.setAttributeNS(XMLNS_NAMESPACE, "xmlns:OpCamera", OPLUS_CAMERA_NAMESPACE)
        description.setAttributeNS(XMLNS_NAMESPACE, "xmlns:MiCamera", MI_CAMERA_NAMESPACE)
        if (gainMapLengthBytes != null) {
            description.setAttributeNS(XMLNS_NAMESPACE, "xmlns:hdrgm", HDRGM_NAMESPACE)
            description.setAttributeNS(HDRGM_NAMESPACE, "hdrgm:Version", hdrgmVersion ?: "1.0")
        }
        description.setAttributeNS(G_CAMERA_NAMESPACE, "GCamera:MotionPhoto", "1")
        description.setAttributeNS(G_CAMERA_NAMESPACE, "GCamera:MotionPhotoVersion", "1")
        description.setAttributeNS(
            G_CAMERA_NAMESPACE,
            "GCamera:MotionPhotoPresentationTimestampUs",
            presentationTimestampUs.toString(),
        )
        description.setAttributeNS(G_CAMERA_NAMESPACE, "GCamera:MicroVideoVersion", "1")
        description.setAttributeNS(G_CAMERA_NAMESPACE, "GCamera:MicroVideo", "1")
        description.setAttributeNS(
            G_CAMERA_NAMESPACE,
            "GCamera:MicroVideoOffset",
            videoLengthBytes.toString(),
        )
        description.setAttributeNS(
            G_CAMERA_NAMESPACE,
            "GCamera:MicroVideoPresentationTimestampUs",
            presentationTimestampUs.toString(),
        )
        description.setAttributeNS(
            OPLUS_CAMERA_NAMESPACE,
            "OpCamera:MotionPhotoPrimaryPresentationTimestampUs",
            presentationTimestampUs.toString(),
        )
        description.setAttributeNS(OPLUS_CAMERA_NAMESPACE, "OpCamera:MotionPhotoVideoStart", "0")
        description.setAttributeNS(OPLUS_CAMERA_NAMESPACE, "OpCamera:MotionPhotoVideoEnd", "0")
        description.setAttributeNS(OPLUS_CAMERA_NAMESPACE, "OpCamera:MotionPhotoOwner", "oplus")
        description.setAttributeNS(OPLUS_CAMERA_NAMESPACE, "OpCamera:OLivePhotoVersion", "2")
        description.setAttributeNS(OPLUS_CAMERA_NAMESPACE, "OpCamera:VideoLength", videoLengthBytes.toString())
        description.setAttributeNS(MI_CAMERA_NAMESPACE, "MiCamera:XMPMeta", MI_CAMERA_XMP_META)
        rewriteContainerDirectory(
            document = document,
            description = description,
            gainMapLengthBytes = gainMapLengthBytes,
            videoLengthBytes = videoLengthBytes,
            videoMimeType = videoMimeType,
        )

        val writer = StringWriter()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        transformer.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }

    private fun rewriteContainerDirectory(
        document: org.w3c.dom.Document,
        description: org.w3c.dom.Element,
        gainMapLengthBytes: Int?,
        videoLengthBytes: Long,
        videoMimeType: String,
    ) {
        val childrenToRemove = mutableListOf<org.w3c.dom.Node>()
        val childNodes = description.childNodes
        for (index in 0 until childNodes.length) {
            val node = childNodes.item(index)
            if (node is org.w3c.dom.Element && node.localName == "Directory") {
                childrenToRemove += node
            }
        }
        childrenToRemove.forEach(description::removeChild)

        val directory = document.createElementNS(CONTAINER_NAMESPACE, "Container:Directory")
        val sequence = document.createElementNS(RDF_NAMESPACE, "rdf:Seq")
        sequence.appendChild(
            createContainerItemElement(
                document = document,
                semantic = "Primary",
                mimeType = "image/jpeg",
                length = 0L,
                padding = 0L,
            ),
        )
        if (gainMapLengthBytes != null) {
            sequence.appendChild(
                createContainerItemElement(
                    document = document,
                    semantic = "GainMap",
                    mimeType = "image/jpeg",
                    length = gainMapLengthBytes.toLong(),
                    padding = null,
                ),
            )
        }
        sequence.appendChild(
            createContainerItemElement(
                document = document,
                semantic = "MotionPhoto",
                mimeType = videoMimeType,
                length = videoLengthBytes,
                padding = 0L,
            ),
        )
        directory.appendChild(sequence)
        description.appendChild(directory)
    }

    private fun createContainerItemElement(
        document: org.w3c.dom.Document,
        semantic: String,
        mimeType: String,
        length: Long,
        padding: Long?,
    ): org.w3c.dom.Element {
        val listItem = document.createElementNS(RDF_NAMESPACE, "rdf:li")
        listItem.setAttributeNS(RDF_NAMESPACE, "rdf:parseType", "Resource")
        val item = document.createElementNS(CONTAINER_NAMESPACE, "Container:Item")
        item.setAttributeNS(ITEM_NAMESPACE, "Item:Mime", mimeType)
        item.setAttributeNS(ITEM_NAMESPACE, "Item:Semantic", semantic)
        item.setAttributeNS(ITEM_NAMESPACE, "Item:Length", length.toString())
        padding?.let {
            item.setAttributeNS(ITEM_NAMESPACE, "Item:Padding", it.toString())
        }
        listItem.appendChild(item)
        return listItem
    }
}
