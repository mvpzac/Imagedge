package com.imagedge.camera.motionphoto

import com.imagedge.camera.motionphoto.internal.format.extensionForMime
import com.imagedge.camera.motionphoto.internal.format.inferIsoBaseMediaMime
import com.imagedge.camera.motionphoto.internal.format.indexOfSubarray
import com.imagedge.camera.motionphoto.internal.format.looksLikeIsoBaseMedia
import com.imagedge.camera.motionphoto.internal.format.looksLikeJpeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryFormatUtilsTest {

    @Test
    fun `indexOfSubarray finds skips and rejects`() {
        val data = byteArrayOf(0, 1, 2, 3, 1, 2, 9)
        assertEquals(1, indexOfSubarray(data, byteArrayOf(1, 2)))
        assertEquals(4, indexOfSubarray(data, byteArrayOf(1, 2), 2))
        assertEquals(-1, indexOfSubarray(data, byteArrayOf(9, 9)))
        assertEquals(-1, indexOfSubarray(data, ByteArray(0)))
        assertEquals(-1, indexOfSubarray(ByteArray(1), byteArrayOf(1, 2)))
    }

    @Test
    fun `signature sniffers`() {
        assertTrue(looksLikeJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 0))
        assertFalse(looksLikeJpeg(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), 0))

        val mp4 = byteArrayOf(
            0, 0, 0, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
        )
        assertTrue(looksLikeIsoBaseMedia(mp4, 0))
        assertEquals("video/mp4", inferIsoBaseMediaMime(mp4))
        assertFalse(looksLikeIsoBaseMedia(mp4.copyOfRange(0, 6), 0))

        val mov = mp4.copyOf()
        mov[8] = 'q'.code.toByte(); mov[9] = 't'.code.toByte()
        mov[10] = ' '.code.toByte(); mov[11] = ' '.code.toByte()
        assertEquals("video/quicktime", inferIsoBaseMediaMime(mov))
    }

    @Test
    fun `extension mapping`() {
        assertEquals("jpg", extensionForMime("image/jpeg"))
        assertEquals("heic", extensionForMime("image/heif"))
        assertEquals("mov", extensionForMime("video/quicktime"))
        assertEquals("mp4", extensionForMime("VIDEO/MP4"))
        assertEquals("bin", extensionForMime("application/octet-stream"))
    }
}
