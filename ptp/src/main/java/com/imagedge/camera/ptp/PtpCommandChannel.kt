package com.imagedge.camera.ptp

import java.io.InputStream
import java.io.OutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : PTP 命令通道抽象（TCP / USB 两种承载共用一套事务引擎）
 *     version: 1.0
 * </pre>
 */

/**
 * 命令通道：把「一个 PTP 包」变成「一串字节」送出去，再把字节变回包。
 *
 * 抽这一层是为了让事务引擎（会话、事务 ID、数据阶段、响应码判定）只有一份。
 * 两种承载的差别全部止步于此：
 * - TCP：包自带 8 字节 PTP/IP 头，直接按流读；
 * - USB：包要翻译成容器（见 [PtpContainerCodec]），且一次 `bulkTransfer` 可能
 *   只带回半个容器，得循环读到凑齐为止。
 *
 * 事件通道不在这里：TCP 用第二条连接，USB 用中断端点，两者形状差异太大，
 * 硬塞进同一个接口只会得到一堆恒为 false 的方法。
 */
interface PtpCommandChannel {
    val isOpen: Boolean

    fun write(packet: PtpIpPacket)

    /** 读下一个包。传输中断时抛 [PtpIoException]。 */
    fun read(): PtpIpPacket

    fun close()
}

/** TCP 承载：直接在流上收发 PTP/IP 包。 */
class SocketPtpCommandChannel(
    private val input: InputStream,
    private val output: OutputStream,
    /** 底层 socket 是否仍然连着。通道自身无法从流上判断这一点。 */
    private val isAlive: () -> Boolean,
    private val onClose: () -> Unit
) : PtpCommandChannel {

    @Volatile
    private var open = true

    override val isOpen: Boolean get() = open && isAlive()

    override fun write(packet: PtpIpPacket) {
        check(open) { "命令通道已关闭" }
        output.write(packet.serialize())
        output.flush()
    }

    override fun read(): PtpIpPacket {
        check(open) { "命令通道已关闭" }
        return PtpIpPacket.read(input)
    }

    override fun close() {
        if (!open) return
        open = false
        runCatching { output.flush() }
        onClose()
    }
}