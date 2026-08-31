package com.imagedge.camera.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : UPnP DIDL-Lite 解析单元测试
 *     version: 1.0
 * </pre>
 */
class UpnpClientTest {

    private val client = UpnpClient("127.0.0.1")

    @Test
    fun parseDidlWithItemAndContainer() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
                xmlns:av="urn:schemas-sony-com:av">
                <container id="dir1" parentID="0" restricted="0">
                    <dc:title>DCIM</dc:title>
                    <upnp:class>object.container.storageFolder</upnp:class>
                </container>
                <item id="img1" parentID="dir1" restricted="1">
                    <dc:title>DSC00001</dc:title>
                    <upnp:class>object.item.imageItem</upnp:class>
                    <av:contentType>image/jpeg</av:contentType>
                    <dc:date>2026-08-27T12:00:00</dc:date>
                    <res protocolInfo="http-get:*:image/jpeg:*" size="8000000">http://127.0.0.1:64321/file1.JPG</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()

        val items = client.parseDidl(didl)

        assertEquals(2, items.size)

        val folder = items.first { it.isDirectory }
        assertEquals("DCIM", folder.title)
        assertEquals("dir1", folder.id)

        val image = items.first { !it.isDirectory }
        assertEquals("DSC00001", image.title)
        assertEquals("image/jpeg", image.contentType)
        assertEquals(8_000_000L, image.size)
        assertEquals("http://127.0.0.1:64321/file1.JPG", image.url)
    }

    @Test
    fun parseBrowseResponseExtractsResult() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/">
                <item id="img1" parentID="0" restricted="1">
                    <dc:title>Photo1</dc:title>
                    <res size="100">http://127.0.0.1:64321/p1.jpg</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()

        // DIDL 需要 XML 转义后放进 Result
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val soapResponse = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                        <Result>$escaped</Result>
                        <NumberReturned>1</NumberReturned>
                        <TotalMatches>1</TotalMatches>
                        <UpdateID>0</UpdateID>
                    </u:BrowseResponse>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val result = client.parseBrowseResponse(soapResponse)

        assertEquals(1, result.numberReturned)
        assertEquals(1, result.totalMatches)
        assertEquals(1, result.items.size)
        assertEquals("Photo1", result.items[0].title)
    }

    @Test
    fun parseEmptyDidl() {
        val result = client.parseDidl("<DIDL-Lite></DIDL-Lite>")
        assertTrue(result.isEmpty())
    }
}
