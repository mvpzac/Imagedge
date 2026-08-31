package com.imagedge.camera.core.stream

import java.io.InputStream
import java.io.OutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 流工具（规范 10.17：使用带缓冲的输入输出流进行 IO 操作）
 *     version: 1.0
 * </pre>
 */
object StreamUtils {

    /** 默认缓冲大小（PTP/IP 大文件流式传输分块大小） */
    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    /**
     * 带进度回调的流拷贝
     * @param input 输入流（调用方负责关闭）
     * @param output 输出流（调用方负责关闭）
     * @param expected 期望总字节数（用于进度；0 表示未知）
     * @param bufferSize 缓冲大小
     * @param onProgress 进度回调（bytesRead, totalBytes）
     * @return 实际拷贝字节数
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        expected: Long = 0L,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Long {
        val buffer = ByteArray(bufferSize)
        var total: Long = 0
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
            onProgress(total, expected)
        }
        output.flush()
        return total
    }
}
