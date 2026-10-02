package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : USB 批量传输的字节流 → 容器切分（纯逻辑，可脱离相机验证）
 *     version: 1.0
 * </pre>
 */

/**
 * 把 USB 批量传输收回来的字节流切成完整容器。
 *
 * 存在的理由：批量传输**不是按消息分帧的**。一次 `bulkTransfer` 可能只带回半个容器，
 * 也可能一次带回「一个半容器」。TCP 那边不需要这一层是因为 socket 自带字节流保证，
 * 而 USB 这边若按「一次传输 = 一个容器」来读，会在两种情况下都错。
 *
 * 刻意做成纯函数式的累积器：不碰 USB、不碰 Android，因此这段最容易出错的逻辑
 * 可以在 JVM 上把「半个」「一又半个」「两个整的」逐个跑一遍。
 */
class UsbContainerAssembler(private val maxContainerBytes: Int = DEFAULT_MAX_CONTAINER_BYTES) {

    private var pending = ByteArray(0)

    /** 累积字节数。诊断用。 */
    val bufferedBytes: Int get() = pending.size

    /** 喂入一段从 USB 读回的字节。 */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        require(length >= 0 && length <= bytes.size) { "写入长度越界：$length/${bytes.size}" }
        pending = pending.copyOf(pending.size + length)
        System.arraycopy(bytes, 0, pending, pending.size - length, length)
    }

    /**
     * 取出一个完整容器。
     *
     * @return 容器字节；当前累积不足一个容器时返回 null（继续等）
     * @throws PtpMalformedPacketException 声明长度非法
     */
    fun next(): ByteArray? {
        if (pending.size < HEADER_BYTES) return null
        val declared = readInt(pending, 0)
        if (declared < HEADER_BYTES || declared > maxContainerBytes) {
            // 长度不可信时清空缓冲：留着一个读不动的头会让后面每次读都卡在同一处
            pending = ByteArray(0)
            throw PtpMalformedPacketException("USB 容器声明长度非法：$declared")
        }
        if (pending.size < declared) return null
        val container = pending.copyOf(declared)
        pending = pending.copyOfRange(declared, pending.size)
        return container
    }

    /** 断开或出错后清空，避免下一次连接带着旧字节。 */
    fun reset() {
        pending = ByteArray(0)
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((data[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    companion object {
        const val DEFAULT_MAX_CONTAINER_BYTES = 64 * 1024 * 1024
    }
}