package com.imagedge.camera.ptp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP 字节缓冲区（小端序），支持读/写/流式写出（大文件防 OOM）
 *             参考 ISO 15740 与 libptp 的字节布局，Kotlin 独立实现
 *     version: 1.0
 * </pre>
 */

/**
 * 读写两用字节缓冲区（小端序）
 *
 * 写模式：通过 [writeUInt8] 等追加写入，[toByteArray] 取回完整数据。
 * 读模式：由 [ByteArray] 或 [fill] 构造，通过 [readUInt8] 等顺序读取。
 * 流式：调用 [enableStreaming] 后，[writeBytes] 直接写出到目标流，不再缓存。
 */
class PtpBuffer private constructor() {

    private var writeStream: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var readStream: ByteArrayInputStream? = null
    private var streaming: OutputStream? = null
    private var streamedBytes: Long = 0

    /** 剩余可读字节数 */
    val remaining: Int
        get() = readStream?.available() ?: 0

    /** 已流式写出的字节数 */
    val streamedCount: Long
        get() = streamedBytes

    // ── 构造 ────────────────────────────────────────────────────────

    companion object {
        /** 写模式构造 */
        fun writer(): PtpBuffer = PtpBuffer()

        /** 读模式构造 */
        fun reader(data: ByteArray): PtpBuffer = PtpBuffer().apply {
            readStream = ByteArrayInputStream(data)
            writeStream = null
        }
    }

    /**
     * 从输入流读取 [length] 字节填充为读缓冲区
     *
     * 防御：[length] 为负会让 ByteArray(负数) 抛 NegativeArraySizeException，
     * 过大则直接 OOM。调用方（PtpIpPacket.read）已按 [MAX_PTP_PAYLOAD_BYTES] 做上限校验，
     * 这里是第二道防线，保证任何路径都拿不到「按外部数据裸分配」的数组。
     */
    fun fill(input: InputStream, length: Int, onProgress: (Int) -> Unit = {}): PtpBuffer {
        require(length >= 0) { "填充长度不能为负：$length" }
        require(length <= MAX_PTP_PAYLOAD_BYTES) {
            "填充长度超出上限：$length（上限 $MAX_PTP_PAYLOAD_BYTES）"
        }
        val buffer = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val read = input.read(buffer, pos, length - pos)
            if (read == -1) throw PtpIoException("输入流意外关闭")
            pos += read
            if (pos < length) onProgress(pos)
        }
        readStream = ByteArrayInputStream(buffer)
        writeStream = null
        return this
    }

    /** 启用流式写出：后续 [writeBytes] 直接写目标流 */
    fun enableStreaming(output: OutputStream) {
        streaming = output
        streamedBytes = 0
    }

    /** 取回缓冲区完整字节 */
    fun toByteArray(): ByteArray {
        readStream?.let { return it.readAllBytes() }
        return writeStream?.toByteArray() ?: ByteArray(0)
    }

    // ── 写 ──────────────────────────────────────────────────────────

    private fun ensureWrite() {
        if (writeStream == null) {
            writeStream = ByteArrayOutputStream()
            readStream = null
        }
    }

    fun writeUInt8(value: Int): PtpBuffer {
        ensureWrite()
        writeStream!!.write(value and 0xFF)
        return this
    }

    fun writeUInt16(value: Int): PtpBuffer {
        ensureWrite()
        writeStream!!.write(value and 0xFF)
        writeStream!!.write((value shr 8) and 0xFF)
        return this
    }

    fun writeUInt32(value: Long): PtpBuffer {
        ensureWrite()
        writeUInt16((value and 0xFFFF).toInt())
        writeUInt16(((value shr 16) and 0xFFFF).toInt())
        return this
    }

    fun writeUInt64(value: Long): PtpBuffer {
        ensureWrite()
        writeUInt32(value and 0xFFFFFFFFL)
        writeUInt32((value ushr 32) and 0xFFFFFFFFL)
        return this
    }

    /** PTP 字符串：长度(u8, 含终止符) + UTF-16LE 字符 + null(u16) */
    fun writePtpString(value: String): PtpBuffer {
        ensureWrite()
        if (value.isEmpty()) {
            writeUInt8(0)
            return this
        }
        writeUInt8(value.length + 1)
        for (ch in value) writeUInt16(ch.code)
        writeUInt16(0)
        return this
    }

    /** UTF-16LE 字符串（null 结尾，无长度前缀，用于 PTP/IP 握手包） */
    fun writeUtf16String(value: String): PtpBuffer {
        ensureWrite()
        for (ch in value) writeUInt16(ch.code)
        writeUInt16(0)
        return this
    }

    /** 写入字节数组（流式模式下直接写出） */
    fun writeBytes(data: ByteArray): PtpBuffer {
        val output = streaming
        if (output != null) {
            output.write(data, 0, data.size)
            streamedBytes += data.size
            return this
        }
        ensureWrite()
        writeStream!!.write(data, 0, data.size)
        return this
    }

    // ── 读 ──────────────────────────────────────────────────────────

    private fun ensureRead(need: Int) {
        val stream = readStream
            ?: throw PtpMalformedPacketException("缓冲区未处于读模式")
        if (stream.available() < need) {
            throw PtpMalformedPacketException("数据不足：需要 $need 字节，剩 ${stream.available()}")
        }
    }

    fun readUInt8(): Int {
        ensureRead(1)
        return readStream!!.read()
    }

    fun readUInt16(): Int {
        ensureRead(2)
        val first = readStream!!.read()
        val second = readStream!!.read()
        return first or (second shl 8)
    }

    fun readUInt32(): Long {
        ensureRead(4)
        val low = readUInt16().toLong() and 0xFFFF
        val high = readUInt16().toLong() and 0xFFFF
        return low or (high shl 16)
    }

    fun readUInt64(): Long {
        ensureRead(8)
        val low = readUInt32()
        val high = readUInt32()
        return low or (high shl 32)
    }

    /** 读取 PTP 字符串（长度前缀 + UTF-16LE + null） */
    fun readPtpString(): String {
        val length = readUInt8()
        if (length == 0) return ""
        val chars = StringBuilder(length - 1)
        for (i in 0 until length - 1) {
            val code = readUInt16()
            if (code == 0) throw PtpMalformedPacketException("PtpString 提前遇到 null 终止")
            chars.append(code.toChar())
        }
        if (readUInt16() != 0) throw PtpMalformedPacketException("PtpString 未按声明长度 null 终止")
        return chars.toString()
    }

    /** 读取 UTF-16LE 字符串（null 结尾） */
    fun readUtf16String(): String {
        val chars = StringBuilder()
        while (true) {
            ensureRead(2)
            val code = readUInt16()
            if (code == 0) break
            chars.append(code.toChar())
        }
        return chars.toString()
    }

    /** 读取剩余所有字节 */
    fun readRemaining(): ByteArray {
        val stream = readStream ?: return ByteArray(0)
        return stream.readAllBytes()
    }

    /** 读取指定长度字节 */
    fun readBytes(length: Int): ByteArray {
        ensureRead(length)
        val data = ByteArray(length)
        readStream!!.read(data)
        return data
    }
}
