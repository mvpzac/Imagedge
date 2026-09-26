package com.imagedge.camera.data.model

import com.imagedge.camera.data.remote.CameraChannel
import com.imagedge.camera.data.remote.ChannelType
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.remote.toTransport
import com.imagedge.camera.ptp.DeviceProperty
import com.imagedge.camera.ptp.SonyDevicePropCode
import com.imagedge.camera.ptp.ValueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : T0 能力模型验收测试——描述符缺失 / 只读 / 换镜头 / 模式变更 / 断线，
 *             以及「禁用项不会发命令」的假通道验证
 * </pre>
 */
class CameraCapabilitiesTest {

    /** PTP 数据类型码 */
    private val typeInt16 = 0x0003
    private val typeUInt8 = 0x0002
    private val typeUInt16 = 0x0004
    private val typeUInt32 = 0x0006

    /** 真机基线：ZV-E10 + 固件 2.03 + PTP/IP + 选片集模式（docs/sony-protocol-notes.md） */
    private val ptpIdentity = CameraIdentity(
        model = "ZV-E10",
        firmware = "2.03",
        transport = CameraTransport.PTP_IP,
        mode = CameraIdentity.MODE_REMOTE_CONTROL
    )

    private fun prop(
        code: Int,
        dataType: Int = typeUInt32,
        getSet: Int = 0x01,
        enabled: Boolean = true,
        value: Long = 0L,
        supported: List<Long> = emptyList(),
        range: ValueRange? = null
    ) = DeviceProperty(code, dataType, getSet, enabled, value, supported, range)

    private fun capabilities(
        identity: CameraIdentity = ptpIdentity,
        props: Map<Int, DeviceProperty>? = null,
        captureSupport: CapabilityState = CapabilityState.WRITABLE
    ) = CameraCapabilities.fromDescriptors(identity, props, captureSupport)

    /** 属性类能力（CAPTURE 由通道声明决定，不走 0x9209 描述符） */
    private val devicePropCapabilities = CameraCapability.entries.filter { it != CameraCapability.CAPTURE }

    // ── 1. 描述符缺失 ────────────────────────────────────────────────

    @Test
    fun `absent descriptor is unsupported and offers no options`() {
        // 0x9209 成功返回，但只包含光圈描述符：其余属性是「相机说不支持」，不是「未知」
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.F_NUMBER to prop(
                    SonyDevicePropCode.F_NUMBER,
                    dataType = typeUInt16,
                    supported = listOf(350L, 560L)
                )
            )
        )

        assertEquals(CapabilityState.UNSUPPORTED, caps.stateOf(CameraCapability.ISO))
        assertEquals(
            CapabilityEvidence.DEVICE_PROP_DESCRIPTOR,
            caps.detail(CameraCapability.ISO).evidence
        )
        assertTrue(caps.optionsFor(CameraCapability.ISO).isEmpty())
        assertFalse(caps.canWrite(CameraCapability.ISO))

        // 有描述符的那一项照常可写，档位与排序都来自相机上报
        assertEquals(CapabilityState.WRITABLE, caps.stateOf(CameraCapability.F_NUMBER))
        assertEquals(
            listOf("3.5" to 350L, "5.6" to 560L),
            caps.optionsFor(CameraCapability.F_NUMBER)
        )
    }

    @Test
    fun `descriptor read failure stays unknown instead of unsupported`() {
        // props = null 表示 0x9209 读取失败（超时/断线）。这是 T0 的核心约束：
        // 请求超时绝不能被永久记成「不支持」，否则用户再也看不到本可用的控件
        val caps = capabilities(props = null)

        assertFalse(caps.descriptorRead)
        assertTrue(caps.stale)
        devicePropCapabilities.forEach { capability ->
            assertEquals("$capability 应为 UNKNOWN", CapabilityState.UNKNOWN, caps.stateOf(capability))
            assertEquals(CapabilityEvidence.NONE, caps.detail(capability).evidence)
            assertFalse(caps.canWrite(capability))
            assertTrue(caps.optionsFor(capability).isEmpty())
        }
        // 遥控拍摄不依赖描述符：通道声明即可用，一次读取超时不该把它一并禁用
        assertTrue(caps.canWrite(CameraCapability.CAPTURE))
    }

    @Test
    fun `writable capability without reported values offers no options`() {
        // 相机说「可写」但既没给枚举表也没给取值范围：没有相机确认过的取值可发，
        // 必须禁用而不是硬凑一份档位表
        val caps = capabilities(props = mapOf(SonyDevicePropCode.ISO to prop(SonyDevicePropCode.ISO)))

        assertEquals(CapabilityState.WRITABLE, caps.stateOf(CameraCapability.ISO))
        assertTrue(caps.optionsFor(CameraCapability.ISO).isEmpty())
    }

    // ── 2. 只读 ──────────────────────────────────────────────────────

    @Test
    fun `read-only descriptor cannot be written`() {
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.F_NUMBER to prop(
                    SonyDevicePropCode.F_NUMBER,
                    dataType = typeUInt16,
                    getSet = 0x00,
                    value = 350L,
                    supported = listOf(350L, 560L)
                )
            )
        )

        val detail = caps.detail(CameraCapability.F_NUMBER)
        assertEquals(CapabilityState.READ_ONLY, detail.state)
        assertTrue(detail.note.contains("GetSet"))
        assertFalse(caps.canWrite(CameraCapability.F_NUMBER))
        // 只读也要给空档位：界面据此渲染成只读文本而不是可点的选择器
        assertTrue(caps.optionsFor(CameraCapability.F_NUMBER).isEmpty())
        assertTrue(caps.decideWrite(CameraCapability.F_NUMBER, 180L) is PropertyWriteDecision.Reject)
    }

    @Test
    fun `disabled descriptor is read-only and explains the mode restriction`() {
        // IsEnabled=0：常见于拍摄模式限制（如 AUTO 下不给改 ISO）
        val caps = capabilities(
            props = mapOf(SonyDevicePropCode.ISO to prop(SonyDevicePropCode.ISO, enabled = false))
        )

        val detail = caps.detail(CameraCapability.ISO)
        assertEquals(CapabilityState.READ_ONLY, detail.state)
        assertTrue(detail.note.contains("IsEnabled=0"))
        assertFalse(caps.canWrite(CameraCapability.ISO))
    }

    @Test
    fun `value width outside the write path is treated as read-only`() {
        // 0x9205 写入路径只支持 2/4 字节；相机上报 UINT8 时不能猜着写
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.WHITE_BALANCE to prop(
                    SonyDevicePropCode.WHITE_BALANCE,
                    dataType = typeUInt8,
                    supported = listOf(3L, 5L)
                )
            )
        )

        val detail = caps.detail(CameraCapability.WHITE_BALANCE)
        assertEquals(CapabilityState.READ_ONLY, detail.state)
        assertEquals(1, detail.valueSize)
        assertTrue(detail.note.contains("值宽度"))
    }

    // ── 3. 换镜头 ────────────────────────────────────────────────────

    @Test
    fun `lens change replaces the option list instead of keeping the old one`() {
        // 套头 SELP1650 是 f/3.5-5.6；定焦 SEL35F18 才有 f/1.8。
        // 档位必须完全跟随相机当次上报，不能沿用上一次会话或任何硬编码表
        val kitLens = capabilities(
            props = mapOf(
                SonyDevicePropCode.F_NUMBER to prop(
                    SonyDevicePropCode.F_NUMBER,
                    dataType = typeUInt16,
                    supported = listOf(350L, 400L, 450L, 500L, 560L)
                )
            )
        )
        val primeLens = capabilities(
            props = mapOf(
                SonyDevicePropCode.F_NUMBER to prop(
                    SonyDevicePropCode.F_NUMBER,
                    dataType = typeUInt16,
                    supported = listOf(180L, 200L, 280L, 400L, 1100L, 1600L, 2200L)
                )
            )
        )

        assertFalse("套头上不该出现 f/1.8", kitLens.optionsFor(CameraCapability.F_NUMBER).any { it.second == 180L })
        assertTrue("定焦上 f/1.8 必须出现", primeLens.optionsFor(CameraCapability.F_NUMBER).any { it.second == 180L })
        assertNotEquals(kitLens.optionsFor(CameraCapability.F_NUMBER), primeLens.optionsFor(CameraCapability.F_NUMBER))
        assertEquals("1.8", primeLens.optionsFor(CameraCapability.F_NUMBER).first().first)
    }

    @Test
    fun `iso options keep auto first and drop the unusable zero entry`() {
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.ISO to prop(
                    SonyDevicePropCode.ISO,
                    supported = listOf(400L, 0x00FFFFFFL, 100L, 0L)
                )
            )
        )

        assertEquals(
            listOf("Auto" to 0x00FFFFFFL, "100" to 100L, "400" to 400L),
            caps.optionsFor(CameraCapability.ISO)
        )
    }

    @Test
    fun `program mode options intersect camera report with the remote whitelist`() {
        // 相机上报的完整枚举表含拨盘位（P_A=32776）与未知值，遥控面板只渲染曝光模式子集
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.EXPOSURE_PROGRAM_MODE to prop(
                    SonyDevicePropCode.EXPOSURE_PROGRAM_MODE,
                    supported = listOf(1L, 65538L, 32776L, 999999L)
                )
            )
        )

        assertEquals(
            listOf("P 程序自动" to 65538L, "M 手动曝光" to 1L),
            caps.optionsFor(CameraCapability.EXPOSURE_PROGRAM_MODE)
        )
    }

    // ── 4. 模式变更 ──────────────────────────────────────────────────

    @Test
    fun `function mode is part of the snapshot key`() {
        val fullCard = ptpIdentity.copy(mode = CameraIdentity.MODE_CONTENTS_TRANSFER)

        assertNotEquals(ptpIdentity.snapshotKey, fullCard.snapshotKey)
        // 换固件同样必须换键：同型号不同固件的属性表可能不同
        assertNotEquals(ptpIdentity.snapshotKey, ptpIdentity.copy(firmware = "2.04").snapshotKey)
        assertNotEquals(ptpIdentity.snapshotKey, ptpIdentity.copy(model = "ZV-E10M2").snapshotKey)
        assertNotEquals(
            ptpIdentity.snapshotKey,
            ptpIdentity.copy(transport = CameraTransport.UPNP).snapshotKey
        )
    }

    @Test
    fun `profile key identifies the physical camera regardless of how it was connected`() {
        // 档案属于「这台 ZV-E10」：换连接方式或切功能模式都还是同一台机器，
        // 档案不该裂成两份；能力快照仍然按 snapshotKey 分档存放
        val upnp = ptpIdentity.copy(transport = CameraTransport.UPNP)
        val fullCard = ptpIdentity.copy(mode = CameraIdentity.MODE_CONTENTS_TRANSFER)

        assertEquals(ptpIdentity.profileKey, upnp.profileKey)
        assertEquals(ptpIdentity.profileKey, fullCard.profileKey)
        assertNotEquals(ptpIdentity.snapshotKey, fullCard.snapshotKey)
        // 刷过固件就是另一次实测事实，必须换档案
        assertNotEquals(ptpIdentity.profileKey, ptpIdentity.copy(firmware = "2.04").profileKey)
        assertEquals("", CameraIdentity.UNKNOWN.profileKey.trim('|'))
    }

    @Test
    fun `mode switch produces a snapshot that must be re-probed`() {
        // 切到整卡后身份变了：旧模式的快照不能直接沿用，新快照在探测前必须是「未探测」态
        val afterSwitch = capabilities(identity = ptpIdentity.copy(mode = CameraIdentity.MODE_CONTENTS_TRANSFER))

        assertEquals(CameraIdentity.MODE_CONTENTS_TRANSFER, afterSwitch.identity.mode)
        assertTrue(afterSwitch.stale)
        devicePropCapabilities.forEach { assertFalse(afterSwitch.canWrite(it)) }
    }

    // ── 5. 断线 ──────────────────────────────────────────────────────

    @Test
    fun `disconnected snapshot disables every capability`() {
        val caps = CameraCapabilities.UNKNOWN

        assertEquals(CameraIdentity.UNKNOWN, caps.identity)
        assertFalse(caps.identity.isKnown)
        assertTrue(caps.stale)
        CameraCapability.entries.forEach { capability ->
            assertEquals(CapabilityState.UNKNOWN, caps.stateOf(capability))
            assertFalse(caps.canWrite(capability))
            assertTrue(caps.optionsFor(capability).isEmpty())
            assertTrue(caps.decideWrite(capability, 1L) is PropertyWriteDecision.Reject)
        }
    }

    @Test
    fun `upnp transport is determinately unsupported rather than unknown`() {
        // UPnP 通道结构性不暴露 DeviceProp：这是确定事实，不需要探测就能给结论
        val caps = capabilities(
            identity = CameraIdentity("Sony Camera", "", CameraTransport.UPNP, CameraIdentity.MODE_UNKNOWN),
            props = null,
            captureSupport = CapabilityState.UNSUPPORTED
        )

        assertFalse(caps.stale)
        devicePropCapabilities.forEach { capability ->
            assertEquals(CapabilityState.UNSUPPORTED, caps.stateOf(capability))
            assertEquals(CapabilityEvidence.TRANSPORT, caps.detail(capability).evidence)
        }
        assertEquals(CapabilityState.UNSUPPORTED, caps.stateOf(CameraCapability.CAPTURE))
        assertFalse(caps.canWrite(CameraCapability.CAPTURE))
    }

    // ── 6. 假通道：禁用项不会发命令 ───────────────────────────────────

    @Test
    fun `disabled capabilities never reach the channel`() {
        val channel = FakeChannel(ChannelType.UPNP, "Sony Camera", "", captureSupport = CapabilityState.UNSUPPORTED)
        val caps = capabilities(
            identity = channel.identity(),
            props = null,
            captureSupport = channel.captureSupport
        )

        CameraCapability.entries.forEach { capability ->
            assertFalse("$capability 不应下发成功", dispatch(channel, caps, capability, 12345L))
        }
        assertTrue("假通道不应收到任何命令，实际=${channel.sentCommands}", channel.sentCommands.isEmpty())
    }

    @Test
    fun `unknown snapshot sends nothing even though the channel supports capture`() {
        val channel = FakeChannel(ChannelType.PTP_IP, "ZV-E10", "2.03", captureSupport = CapabilityState.WRITABLE)
        // 0x9209 读取失败：全部属性未知
        val caps = capabilities(identity = channel.identity(), props = null, captureSupport = CapabilityState.WRITABLE)

        devicePropCapabilities.forEach { capability ->
            assertFalse(dispatch(channel, caps, capability, 200L))
        }
        assertTrue("读取超时不等于可以盲发命令，实际=${channel.sentCommands}", channel.sentCommands.isEmpty())
    }

    @Test
    fun `writable capability sends the code and width reported by the camera`() {
        val channel = FakeChannel(ChannelType.PTP_IP, "ZV-E10", "2.03", captureSupport = CapabilityState.WRITABLE)
        val caps = capabilities(
            identity = channel.identity(),
            props = mapOf(
                // ZV-E10 上报 UINT32（宽度 4）：宽度必须来自描述符而不是按属性码硬编码
                SonyDevicePropCode.ISO to prop(SonyDevicePropCode.ISO, supported = listOf(100L, 200L)),
                // 曝光补偿是 INT16：负值要按宽度回补成无符号位模式
                SonyDevicePropCode.EXPOSURE_BIAS to prop(
                    SonyDevicePropCode.EXPOSURE_BIAS,
                    dataType = typeInt16,
                    range = ValueRange(0xF448L, 0x0BB8L, 1000L)
                )
            ),
            captureSupport = CapabilityState.WRITABLE
        )

        assertTrue(dispatch(channel, caps, CameraCapability.ISO, 200L))
        // -2000 落在相机上报的步进上；-300 不在，会被值校验挡下（见下面专门的用例）
        assertTrue(dispatch(channel, caps, CameraCapability.EXPOSURE_BIAS, -2000L))
        assertEquals(
            listOf(
                Triple(SonyDevicePropCode.ISO, 200L, 4),
                Triple(SonyDevicePropCode.EXPOSURE_BIAS, (-2000L) and 0xFFFFL, 2)
            ),
            channel.sentCommands
        )
    }

    // ── 值校验：能力可写 ≠ 这个值可用 ────────────────────────────────

    @Test
    fun `a value the camera no longer reports is rejected without sending`() {
        val channel = FakeChannel(ChannelType.PTP_IP, "ZV-E10", "2.03", captureSupport = CapabilityState.WRITABLE)
        // 套头 SELP1650 是 f/3.5-5.6；f/1.8 只在另一支镜头那轮上报里出现过
        val kitLens = capabilities(
            identity = channel.identity(),
            props = mapOf(
                SonyDevicePropCode.F_NUMBER to prop(
                    SonyDevicePropCode.F_NUMBER,
                    dataType = typeUInt16,
                    supported = listOf(350L, 400L, 450L, 500L, 560L)
                )
            ),
            captureSupport = CapabilityState.WRITABLE
        )

        assertTrue(kitLens.canWrite(CameraCapability.F_NUMBER))
        assertFalse("套头不该接受 f/1.8", kitLens.accepts(CameraCapability.F_NUMBER, 180L))
        assertTrue(kitLens.accepts(CameraCapability.F_NUMBER, 560L))
        assertFalse(dispatch(channel, kitLens, CameraCapability.F_NUMBER, 180L))
        assertTrue("被拒的项不能留下任何下发痕迹", channel.sentCommands.isEmpty())

        // 拒绝必须说清是「这个值不行」而不是「这项不行」——预设的逐项报告依赖这个区分
        val decision = kitLens.decideWrite(CameraCapability.F_NUMBER, 180L)
        assertTrue(decision is PropertyWriteDecision.Reject)
        decision as PropertyWriteDecision.Reject
        assertEquals(RejectReason.VALUE_NOT_REPORTED, decision.kind)
        assertEquals(CapabilityState.WRITABLE, decision.state)
    }

    @Test
    fun `range values must stay inside the bounds and align to the step`() {
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.EXPOSURE_BIAS to prop(
                    SonyDevicePropCode.EXPOSURE_BIAS,
                    dataType = typeInt16,
                    range = ValueRange(0xF448L, 0x0BB8L, 1000L)
                )
            )
        )

        listOf(-3000L, -2000L, 0L, 2000L, 3000L).forEach {
            assertTrue("$it 在步进上", caps.accepts(CameraCapability.EXPOSURE_BIAS, it))
        }
        listOf(-300L, 500L).forEach {
            assertFalse("$it 不在步进上", caps.accepts(CameraCapability.EXPOSURE_BIAS, it))
        }
        assertFalse(caps.accepts(CameraCapability.EXPOSURE_BIAS, -3001L))
        assertFalse(caps.accepts(CameraCapability.EXPOSURE_BIAS, 3001L))
    }

    @Test
    fun `writable property with no reported value form accepts nothing`() {
        // 相机说可写却既无枚举表也无范围：没有任何依据能声称某个具体值可用
        val caps = capabilities(props = mapOf(SonyDevicePropCode.ISO to prop(SonyDevicePropCode.ISO)))

        assertEquals(CapabilityState.WRITABLE, caps.stateOf(CameraCapability.ISO))
        assertFalse(caps.accepts(CameraCapability.ISO, 200L))
        val decision = caps.decideWrite(CameraCapability.ISO, 200L)
        assertTrue(decision is PropertyWriteDecision.Reject)
        assertEquals(
            RejectReason.VALUE_NOT_REPORTED,
            (decision as PropertyWriteDecision.Reject).kind
        )
    }

    @Test
    fun `exposure bias options come from the reported range with signed labels`() {
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.EXPOSURE_BIAS to prop(
                    SonyDevicePropCode.EXPOSURE_BIAS,
                    dataType = typeInt16,
                    range = ValueRange(0xF448L, 0x0BB8L, 1000L)   // -3000 / +3000 / 1000
                )
            )
        )

        assertEquals(
            listOf(
                "+3.0" to 3000L, "+2.0" to 2000L, "+1.0" to 1000L, "0.0" to 0L,
                "-1.0" to -1000L, "-2.0" to -2000L, "-3.0" to -3000L
            ),
            caps.optionsFor(CameraCapability.EXPOSURE_BIAS)
        )
    }

    @Test
    fun `hostile range does not produce an unbounded option list`() {
        val caps = capabilities(
            props = mapOf(
                SonyDevicePropCode.EXPOSURE_BIAS to prop(
                    SonyDevicePropCode.EXPOSURE_BIAS,
                    range = ValueRange(0L, 1_000_000L, 1L)
                )
            )
        )

        // 相机上报畸形范围时不能生成百万个选项；能力仍可写，但没有档位可选 → 界面禁用
        assertEquals(CapabilityState.WRITABLE, caps.stateOf(CameraCapability.EXPOSURE_BIAS))
        assertTrue(caps.optionsFor(CameraCapability.EXPOSURE_BIAS).isEmpty())
    }

    // ── 假通道 ───────────────────────────────────────────────────────

    private fun FakeChannel.identity(mode: Int = CameraIdentity.MODE_REMOTE_CONTROL) = CameraIdentity(
        model = deviceModel,
        firmware = deviceFirmware,
        transport = channelType.toTransport(),
        mode = mode
    )

    /**
     * 复刻 `CameraRepository.writeProperty` 的下发路径：只有 Send 决策才触达通道。
     * 判定逻辑本身是生产代码（[CameraCapabilities.decideWrite]），这里只验证
     * 「Reject 就不会发命令」这条契约。
     */
    private fun dispatch(
        channel: FakeChannel,
        caps: CameraCapabilities,
        capability: CameraCapability,
        raw: Long
    ): Boolean = when (val decision = caps.decideWrite(capability, raw)) {
        is PropertyWriteDecision.Send ->
            channel.setDeviceProperty(decision.propCode, decision.value, decision.valueSize)

        is PropertyWriteDecision.Reject -> false
    }

    /** 假通道：只记录收到的命令。真实通道一旦收到命令就已经晚了，所以拦截点必须在这之前 */
    private class FakeChannel(
        override val channelType: ChannelType,
        override val deviceModel: String,
        override val deviceFirmware: String,
        override val captureSupport: CapabilityState
    ) : CameraChannel {

        /** 收到的写属性命令：Triple(属性码, 值, 值宽度) */
        val sentCommands = mutableListOf<Triple<Int, Long, Int>>()

        fun setDeviceProperty(propCode: Int, value: Long, valueSize: Int): Boolean {
            sentCommands += Triple(propCode, value, valueSize)
            return true
        }

        override suspend fun connect(host: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun listMedia(): List<MediaItem> = emptyList()
        override suspend fun getThumbnail(item: MediaItem): ByteArray? = null
        override suspend fun download(
            item: MediaItem,
            output: OutputStream,
            onProgress: (Long, Long) -> Unit
        ) = Unit
    }
}
