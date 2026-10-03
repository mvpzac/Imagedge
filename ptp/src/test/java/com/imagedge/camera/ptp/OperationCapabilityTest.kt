package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/03
 *     desc   : 可选操作码的能力门（GetDeviceInfo 的 operationsSupported）
 * </pre>
 *
 * 通过 [PtpCommandChannel] 注入假通道来跑——这条通路本来就不需要相机，
 * 而它正是「想知道相机支不支持某能力」这件事唯一能不发报文就回答的地方。
 */
class OperationCapabilityTest {

    /** 记录发出过的包，并按操作码回放相应负载的假通道 */
    private class FakeChannel(
        private val responses: Map<Int, ByteArray> = emptyMap()
    ) : PtpCommandChannel {
        val sent = mutableListOf<PtpIpPacket>()
        private val pending = ArrayDeque<PtpIpPacket>()
        private var currentTransactionId = 0L

        override val isOpen: Boolean get() = true

        override fun write(packet: PtpIpPacket) {
            sent += packet
            val request = packet as? OperationRequest ?: return
            val tid = request.transactionId
            currentTransactionId = tid
            // 只有带响应的操作码才有数据阶段；数据OUT 型（控制通道、设属性）
            // 由发起方自己发 StartData/Data/EndData，随后直接就是 OperationResponse
            responses[request.operationCode]?.let { payload ->
                pending.add(StartData(tid, payload.size.toLong()))
                pending.add(DataPacket(tid, payload))
                pending.add(EndData(tid))
            }
            pending.add(OperationResponse(PtpResponseCode.OK, tid))
        }

        override fun read(): PtpIpPacket = pending.removeFirstOrNull()
            ?: throw AssertionError("没有更多待读包了")

        override fun close() = Unit

        /** 已发出的、指定操作码的请求个数 */
        fun sentCountOf(operationCode: Int): Int =
            sent.count { it is OperationRequest && it.operationCode == operationCode }
    }

    private fun deviceInfoPayload(operations: IntArray): ByteArray =
        PtpBuffer.writer()
            .writeUInt16(100)               // standardVersion
            .writeUInt32(6)                  // vendorExtensionId
            .writeUInt16(100)                // vendorExtensionVersion
            .writePtpString("")              // vendorExtensionDesc
            .writeUInt16(0)                  // functionalMode
            .apply {
                writeUInt32(operations.size.toLong())
                operations.forEach { writeUInt16(it) }
                writeUInt32(0)               // events
                writeUInt32(0)               // properties
                writeUInt32(0)               // capture formats
                writeUInt32(0)               // image formats
                writePtpString("Sony")       // manufacturer
                writePtpString("ZV-E10")     // model
                writePtpString("2.03")       // deviceVersion
                writePtpString("SN1")        // serialNumber
            }
            .toByteArray()

    private fun clientReporting(operations: IntArray): Pair<PtpIpClient, FakeChannel> {
        val channel = FakeChannel(
            mapOf(PtpOperationCode.GET_DEVICE_INFO to deviceInfoPayload(operations))
        )
        val client = PtpIpClient()
        client.connectVia(channel)
        client.getDeviceInfo()
        return client to channel
    }

    @Test
    fun `unknown before device info is reported as unknown rather than unsupported`() {
        val client = PtpIpClient()

        // 「判定不了」与「明确不支持」必须分开：把前者当成后者，
        // 会让首次连接前的每一次探测都静默失效
        assertNull(client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
    }

    @Test
    fun `an operation the camera reports is supported`() {
        val (client, _) = clientReporting(intArrayOf(0x1001, 0x9207, 0x9805))

        assertEquals(true, client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
        assertEquals(true, client.supportsOperation(SonyObjectPropOperationCode.GET_OBJECT_PROP_LIST))
    }

    @Test
    fun `an operation the camera omits is reported unsupported`() {
        val (client, _) = clientReporting(intArrayOf(0x1001, 0x1002))

        assertEquals(false, client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
        assertEquals(false, client.supportsOperation(SonyObjectPropOperationCode.GET_OBJECT_PROP_LIST))
    }

    @Test
    fun `a control channel missing from the list is still sent, on purpose`() {
        // 刻意不拦截：快门是本应用最要紧的动作，而索尼固件是否把 SDIO 扩展列进
        // OperationsSupported 无依据可查（官方 App 自己从不查这份清单）。
        // 误判为不支持的代价是快门被静默关掉，而放行的代价只是一次注定失败的往返，
        // 它本来就会返回 false——两者不等价
        val (client, channel) = clientReporting(intArrayOf(PtpOperationCode.GET_DEVICE_INFO))

        assertEquals(false, client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
        assertTrue("缺失时仍应照常下发", client.pressShutter())
        assertEquals(1, channel.sentCountOf(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
    }

    @Test
    fun `a supported control channel is used as before`() {
        val (client, channel) = clientReporting(
            intArrayOf(PtpOperationCode.GET_DEVICE_INFO, SonySdioOperationCode.SDIO_CONTROL_DEVICE)
        )

        assertTrue(client.pressShutter())
        assertEquals(1, channel.sentCountOf(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
    }

    @Test
    fun `an unsupported object property list yields an empty map without a round trip`() {
        val (client, channel) = clientReporting(intArrayOf(PtpOperationCode.GET_DEVICE_INFO))

        val props = client.getObjectProps(listOf(1L to SonyObjectPropCode.WIDTH))

        assertTrue(props.handles.isEmpty())
        assertEquals(0, channel.sentCountOf(SonyObjectPropOperationCode.GET_OBJECT_PROP_LIST))
    }

    @Test
    fun `the object property gate short-circuits only on a known-unsupported operation`() {
        val (client, channel) = clientReporting(intArrayOf(PtpOperationCode.GET_DEVICE_INFO))
        val before = channel.sent.size

        client.getObjectProps(listOf(1L to SonyObjectPropCode.WIDTH))

        // 对象属性这条路失败时本来就返回空映射，跳过只省一次往返，行为不变——
        // 所以它可以放心拦截，与快门那条不同
        assertEquals(before, channel.sent.size)
    }

    @Test
    fun `unknown support still falls through to sending`() {
        // 没有设备信息时行为必须与从前一致——能力门是优化与判定依据，
        // 不是把未知一律挡在门外
        val channel = FakeChannel()
        val client = PtpIpClient()
        client.connectVia(channel)

        assertTrue(client.pressShutter())
        assertEquals(1, channel.sentCountOf(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
    }

    @Test
    fun `the capability list does not survive a disconnect`() {
        val (client, _) = clientReporting(intArrayOf(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
        assertEquals(true, client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))

        client.forceClose()

        // 留着上一台相机的答案，会让下一次连接问出属于别的相机的结论
        assertNull(client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
    }

    @Test
    fun `an empty reported list means nothing is supported`() {
        val (client, channel) = clientReporting(intArrayOf())
        val before = channel.sent.size   // 装置阶段自己发过一次 getDeviceInfo，不计入

        assertEquals(false, client.supportsOperation(SonySdioOperationCode.SDIO_CONTROL_DEVICE))
        client.getObjectProps(listOf(1L to SonyObjectPropCode.WIDTH))
        assertEquals("对象属性路应当被能力门拦下", before, channel.sent.size)
    }
}