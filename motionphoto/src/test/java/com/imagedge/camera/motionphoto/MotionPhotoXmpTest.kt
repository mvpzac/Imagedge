package com.imagedge.camera.motionphoto

import com.imagedge.camera.motionphoto.internal.xmp.decodeXmp
import com.imagedge.camera.motionphoto.internal.xmp.extractAllXmpPackets
import com.imagedge.camera.motionphoto.internal.xmp.extractPreferredMotionPhotoXmp
import com.imagedge.camera.motionphoto.internal.xmp.looksLikeMotionPhotoXmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoXmpTest {

    private fun packet(body: String): String =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">$body</x:xmpmeta>"

    @Test
    fun `extracts single and multiple packets`() {
        val motion = packet("<rdf:A>GCamera:MotionPhoto</rdf:A>")
        val other = packet("<rdf:B/>")
        val packets = extractAllXmpPackets("junk$motion middle $other tail".toByteArray())
        assertEquals(2, packets.size)
        assertTrue(packets[0].contains("GCamera:MotionPhoto"))
    }

    @Test
    fun `unterminated packet yields nothing`() {
        val half = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:A>"
        assertTrue(extractAllXmpPackets(half.toByteArray()).isEmpty())
        assertNull(extractPreferredMotionPhotoXmp("no xmp here".toByteArray()))
    }

    @Test
    fun `preferred packet favors motion photo`() {
        val plain = packet("<rdf:P/>")
        val motion = packet("<rdf:M GCamera:MotionPhoto=\"1\"/>")
        // 即使普通包在前，也应优先返回 Motion Photo 包
        val preferred = extractPreferredMotionPhotoXmp("$plain$motion".toByteArray())
        assertTrue(preferred!!.contains("GCamera:MotionPhoto"))
    }

    @Test
    fun `looks like motion photo via fast string paths`() {
        assertTrue(looksLikeMotionPhotoXmp("GCamera:MotionPhoto=\"1\""))
        assertTrue(looksLikeMotionPhotoXmp("Camera:MotionPhoto=\"1\""))
        assertTrue(looksLikeMotionPhotoXmp("GCamera:MicroVideoOffset=\"1234\""))
    }

    @Test
    fun `decodeXmp handles bom and null bytes`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("abc", decodeXmp(utf8Bom))
        assertEquals("ab", decodeXmp("a\u0000b".toByteArray()))
        assertEquals("xyz", decodeXmp("xyz".toByteArray()))
    }
}
