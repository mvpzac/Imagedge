package com.imagedge.camera.motionphoto

import com.imagedge.camera.motionphoto.internal.parse.MotionPhotoSegmentExtractor
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoSegmentExtractorTest {

    @Test
    fun `keeps offsets as long for sparse files larger than two gigabytes`() {
        val source = File.createTempFile("motion-photo-long-offset", ".jpg")
        try {
            val video = byteArrayOf(
                0, 0, 0, 12,
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            )
            val fileLength = Int.MAX_VALUE.toLong() + 4_096L
            RandomAccessFile(source, "rw").use { file ->
                file.setLength(fileLength)
                file.seek(0L)
                file.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                file.seek(fileLength - video.size)
                file.write(video)
            }

            val extraction = MotionPhotoSegmentExtractor.extract(
                source,
                XmpSummary(
                    motionPhotoFlag = 1,
                    motionPhotoVersion = 1,
                    presentationTimestampUs = 0L,
                    microVideoOffset = video.size.toLong(),
                    items = emptyList(),
                ),
            )

            assertTrue(extraction.video.startOffset > Int.MAX_VALUE.toLong())
            assertEquals(fileLength - video.size, extraction.video.startOffset)
            assertEquals(video.size.toLong(), extraction.video.length)
            assertEquals(4L, extraction.image.endOffset)
        } finally {
            source.delete()
        }
    }
}
