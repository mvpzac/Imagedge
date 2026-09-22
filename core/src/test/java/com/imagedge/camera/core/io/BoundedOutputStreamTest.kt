package com.imagedge.camera.core.io

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedOutputStreamTest {

    @Test
    fun `writes up to the exact limit`() {
        val sink = ByteArrayOutputStream()
        val output = BoundedOutputStream(sink, 4)

        output.write(byteArrayOf(1, 2, 3))
        output.write(4)

        assertEquals(4, output.bytesWritten)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), sink.toByteArray())
    }

    @Test(expected = IOException::class)
    fun `rejects a bulk write before writing past the limit`() {
        val sink = ByteArrayOutputStream()
        val output = BoundedOutputStream(sink, 3)

        output.write(byteArrayOf(1, 2))
        try {
            output.write(byteArrayOf(3, 4))
        } finally {
            assertArrayEquals(byteArrayOf(1, 2), sink.toByteArray())
            assertEquals(2, output.bytesWritten)
        }
    }
}
