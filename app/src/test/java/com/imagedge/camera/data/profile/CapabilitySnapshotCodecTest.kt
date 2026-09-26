package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.CapabilityEvidence
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.model.PropertyWriteDecision
import com.imagedge.camera.ptp.DeviceProperty
import com.imagedge.camera.ptp.SonyDevicePropCode
import com.imagedge.camera.ptp.ValueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 能力快照持久化编解码验收——往返一致，且读回来永远不可下发
 * </pre>
 */
class CapabilitySnapshotCodecTest {

    private val typeInt16 = 0x0003
    private val typeUInt16 = 0x0004
    private val typeUInt32 = 0x0006

    private val identity = CameraIdentity(
        model = "ZV-E10",
        firmware = "2.03",
        transport = CameraTransport.PTP_IP,
        mode = CameraIdentity.MODE_REMOTE_CONTROL
    )

    private fun probed(): CameraCapabilities = CameraCapabilities.fromDescriptors(
        identity = identity,
        props = mapOf(
            SonyDevicePropCode.F_NUMBER to DeviceProperty(
                code = SonyDevicePropCode.F_NUMBER,
                dataType = typeUInt16,
                getSet = 0x01,
                enabled = true,
                currentValue = 350L,
                supported = listOf(350L, 400L, 560L)
            ),
            SonyDevicePropCode.SHUTTER_SPEED to DeviceProperty(
                code = SonyDevicePropCode.SHUTTER_SPEED,
                dataType = typeUInt32,
                getSet = 0x00,                 // 只读，验证状态能穿过存储层
                enabled = true,
                currentValue = 0x0001_0C80L
            ),
            SonyDevicePropCode.EXPOSURE_BIAS to DeviceProperty(
                code = SonyDevicePropCode.EXPOSURE_BIAS,
                dataType = typeInt16,          // INT16：Range 才会做符号扩展
                getSet = 0x01,
                enabled = true,
                currentValue = 0L,
                range = ValueRange(0xF448L, 0x0BB8L, 333L)
            )
        ),
        captureSupport = CapabilityState.WRITABLE
    )

    @Test
    fun `a round trip keeps state evidence prop code width and reported values`() {
        val source = probed()

        val restored = CapabilitySnapshotCodec.decode(
            CapabilitySnapshotCodec.encode(source, probedAt = 555L)
        )

        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals(source.identity, restored.identity)
        assertEquals(
            listOf(CapabilityState.WRITABLE, CapabilityState.READ_ONLY, CapabilityState.WRITABLE),
            listOf(
                restored.stateOf(CameraCapability.F_NUMBER),
                restored.stateOf(CameraCapability.SHUTTER_SPEED),
                restored.stateOf(CameraCapability.EXPOSURE_BIAS)
            )
        )
        val fNumber = restored.detail(CameraCapability.F_NUMBER)
        assertEquals(SonyDevicePropCode.F_NUMBER, fNumber.propCode)
        assertEquals(2, fNumber.valueSize)
        assertEquals(CapabilityEvidence.DEVICE_PROP_DESCRIPTOR, fNumber.evidence)
        assertEquals(listOf(350L, 400L, 560L), fNumber.supportedValues)
        // Range 存的要能被还原，否则重启后 EV 控件会从「可写有档位」退化成「可写无档位」
        val biasRange = requireNotNull(restored.detail(CameraCapability.EXPOSURE_BIAS).range)
        assertEquals(-3000L, biasRange.min)
        assertEquals(3000L, biasRange.max)
        assertEquals(333L, biasRange.step)
        // 通道自我声明的能力**不**进档案：「此刻连着哪条通道」不是可持久化的事实，
        // 存一圈回来若照单恢复，就等于让一份历史档案凭空授权一次遥控拍摄
        assertFalse(CameraCapability.CAPTURE in restored.items.keys)
        assertEquals(CapabilityState.UNKNOWN, restored.stateOf(CameraCapability.CAPTURE))
    }

    @Test
    fun `a persisted snapshot always comes back stale and can never authorize a write`() {
        // 这是 T6 最重要的一条：档案里存的是「上次实测」，换镜头/刷固件/改模式都可能让它过时。
        // 拿历史快照去写就违反了 T0 的 stale 约束
        val restored = requireNotNull(
            CapabilitySnapshotCodec.decode(CapabilitySnapshotCodec.encode(probed(), 1L))
        )

        assertTrue("历史快照必须标为陈旧", restored.stale)
        assertFalse("历史快照不能算本轮探测成功", restored.descriptorRead)
        CameraCapability.entries.forEach { capability ->
            assertFalse(
                "$capability 不得因历史快照而可写",
                restored.canWrite(capability)
            )
            val decision = restored.decideWrite(capability, 350L)
            assertTrue(
                "$capability 不得因历史快照而产出下发决策",
                decision is PropertyWriteDecision.Reject
            )
            assertTrue(restored.optionsFor(capability).isEmpty())
        }
    }

    @Test
    fun `untrusted text degrades to null instead of throwing`() {
        assertNull(CapabilitySnapshotCodec.decode(null))
        assertNull(CapabilitySnapshotCodec.decode(""))
        assertNull(CapabilitySnapshotCodec.decode("   "))
        assertNull(CapabilitySnapshotCodec.decode("{not json"))
        assertNull(
            CapabilitySnapshotCodec.decode("x".repeat(CapabilitySnapshotCodec.MAX_JSON_BYTES + 1))
        )
    }

    @Test
    fun `an unknown enum value degrades one item instead of losing the whole snapshot`() {
        // 旧版本存下的状态名在新版本里不存在时，不能因此丢掉整台相机的档案；
        // 但也不能猜成 WRITABLE——降级到 UNKNOWN（界面显示「未知」，可重试）
        val withFutureState = CapabilitySnapshotCodec.encode(probed(), 1L)
            .replace("WRITABLE", "SOME_DAY_STATE")

        val restored = CapabilitySnapshotCodec.decode(withFutureState)

        requireNotNull(restored)
        assertEquals(CapabilityState.UNKNOWN, restored.stateOf(CameraCapability.F_NUMBER))
        // 其余项不受影响
        assertEquals(CapabilityState.READ_ONLY, restored.stateOf(CameraCapability.SHUTTER_SPEED))
    }

    @Test
    fun `unknown capability names are dropped from the restored snapshot`() {
        val withAlien = CapabilitySnapshotCodec.encode(probed(), 1L)
            .replace("\"F_NUMBER\"", "\"SOME_NEW_PROPERTY\"")

        val restored = CapabilitySnapshotCodec.decode(withAlien)

        requireNotNull(restored)
        assertFalse(CameraCapability.F_NUMBER in restored.items.keys)
        assertTrue(restored.items.containsKey(CameraCapability.SHUTTER_SPEED))
    }

    @Test
    fun `probe timestamp survives for display and absent text reports no timestamp`() {
        val text = CapabilitySnapshotCodec.encode(probed(), probedAt = 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, CapabilitySnapshotCodec.probedAtOf(text))
        assertNull(CapabilitySnapshotCodec.probedAtOf(null))
        assertNull(CapabilitySnapshotCodec.probedAtOf("garbage"))
    }
}
