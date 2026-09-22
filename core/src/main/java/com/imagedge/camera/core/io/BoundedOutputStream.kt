package com.imagedge.camera.core.io

import java.io.IOException
import java.io.OutputStream

/**
 * Counts the bytes actually written and fails before [maxBytes] can be exceeded.
 *
 * The limit is enforced on the stream rather than on untrusted metadata. This is important for
 * camera protocols: a peer may announce a small object and then continue streaming indefinitely.
 */
class BoundedOutputStream(
    private val delegate: OutputStream,
    val maxBytes: Long,
) : OutputStream() {

    init {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
    }

    var bytesWritten: Long = 0
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
        bytesWritten++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkBounds(buffer, offset, length)
        ensureCapacity(length)
        delegate.write(buffer, offset, length)
        bytesWritten += length.toLong()
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun ensureCapacity(additionalBytes: Int) {
        if (additionalBytes < 0 || additionalBytes.toLong() > maxBytes - bytesWritten) {
            throw IOException(
                "Output exceeds byte limit: attempted ${bytesWritten + additionalBytes.toLong()}, max $maxBytes"
            )
        }
    }

    private fun checkBounds(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException(
                "buffer=${buffer.size}, offset=$offset, length=$length"
            )
        }
    }
}
