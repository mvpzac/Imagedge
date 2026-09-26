package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.CapabilityDetail
import com.imagedge.camera.data.model.CapabilityEvidence
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.ptp.DeviceProperty
import com.imagedge.camera.ptp.SonyDevicePropCode
import com.imagedge.camera.ptp.ValueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 预设应用规划器验收：换镜头档位过滤、错机型拒绝、成功以读回为准
 * </pre>
 */
class PresetPlannerTest {

    private val typeInt16 = 0x0003
    private val typeUInt16 = 0x0004
    private val typeUInt32 = 0x0006

    private val identity = CameraIdentity(
        model = "ZV-E10",
        firmware = "2.03",
        transport = CameraTransport.PTP_IP,
        mode = CameraIdentity.MODE_REMOTE_CONTROL
    )

    /** 各能力对应的属性码：与仓库下发时用的是同一张表 */
    private val propCode = mapOf(
        CameraCapability.ISO to SonyDevicePropCode.ISO,
        CameraCapability.F_NUMBER to SonyDevicePropCode.F_NUMBER,
        CameraCapability.EXPOSURE_BIAS to SonyDevicePropCode.EXPOSURE_BIAS
    )

    private fun writable(
        capability: CameraCapability,
        dataType: Int,
        value: Long = 0L,
        supported: List<Long> = emptyList(),
        range: ValueRange? = null
    ) = DeviceProperty(
        code = propCode.getValue(capability),
        dataType = dataType,
        getSet = 0x01,
        enabled = true,
        currentValue = value,
        supported = supported,
        range = range
    )

    private fun readOnly(
        capability: CameraCapability,
        dataType: Int,
        value: Long = 0L,
        supported: List<Long> = emptyList()
    ) = DeviceProperty(
        code = propCode.getValue(capability),
        dataType = dataType,
        getSet = 0x00,
        enabled = true,
        currentValue = value,
        supported = supported
    )

    /** 套头（f/3.5-5.6、ISO 只到 400、EV ±3.0 步进 1.0）下的能力快照 */
    private fun kitLens() = CameraCapabilities.fromDescriptors(
        identity = identity,
        props = mapOf(
            propCode.getValue(CameraCapability.F_NUMBER) to writable(
                CameraCapability.F_NUMBER, typeUInt16, value = 560L,
                supported = listOf(350L, 400L, 450L, 500L, 560L)
            ),
            propCode.getValue(CameraCapability.ISO) to writable(
                CameraCapability.ISO, typeUInt32, value = 400L,
                supported = listOf(100L, 200L, 400L)
            ),
            propCode.getValue(CameraCapability.EXPOSURE_BIAS) to writable(
                CameraCapability.EXPOSURE_BIAS, typeInt16,
                range = ValueRange(0xF448L, 0x0BB8L, 1000L)   // -3000 / +3000 / 1000
            )
        ),
        captureSupport = CapabilityState.WRITABLE
    )

    private fun preset(
        profileKey: String = identity.profileKey,
        values: Map<CameraCapability, Long>
    ) = ParameterPreset(
        id = "preset-1",
        profileKey = profileKey,
        name = "街拍",
        values = values,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun writesOf(plan: List<PresetPlanItem>) = plan.filterIsInstance<PresetPlanItem.Write>()
    private fun skipsOf(plan: List<PresetPlanItem>) = plan.filterIsInstance<PresetPlanItem.Skip>()

    // ── 前置闸门 ─────────────────────────────────────────────────────

    @Test
    fun `an empty preset plans nothing`() {
        assertTrue(PresetPlanner.plan(preset(values = emptyMap()), kitLens()).isEmpty())
    }

    @Test
    fun `a preset belonging to another camera rejects every item`() {
        // 错误机型必须整份拒绝：型号相近的两台机器档位表可能完全不同
        val other = preset(
            profileKey = "ZV-E10M2|1.10",
            values = mapOf(CameraCapability.ISO to 400L, CameraCapability.F_NUMBER to 350L)
        )

        val plan = PresetPlanner.plan(other, kitLens())

        assertEquals(2, plan.size)
        assertTrue(writesOf(plan).isEmpty())
        skipsOf(plan).forEach { skip ->
            assertEquals(PresetItemStatus.PROFILE_MISMATCH, skip.status)
            assertTrue(skip.reason.contains("ZV-E10M2"))
        }
        // 就算调用方误报「相机已接受」，被拒的项也不能变成成功
        val outcomes = PresetPlanner.outcomes(
            plan,
            sendResults = mapOf(CameraCapability.ISO to true, CameraCapability.F_NUMBER to true),
            readBack = CameraSettings(isoRaw = 400L, fNumberRaw = 350L)
        )
        assertTrue(outcomes.none { it.status == PresetItemStatus.APPLIED })
    }

    @Test
    fun `a preset cannot be applied on a stale snapshot`() {
        // 档案里存的历史快照永远是 stale：拿「上次相机说可以」代替「现在相机说可以」是预设最危险的用法
        val stale = CameraCapabilities(
            identity = identity,
            items = mapOf(
                CameraCapability.ISO to CapabilityDetail(
                    state = CapabilityState.WRITABLE,
                    evidence = CapabilityEvidence.DEVICE_PROP_DESCRIPTOR,
                    propCode = propCode.getValue(CameraCapability.ISO),
                    valueSize = 4,
                    supportedValues = listOf(100L, 200L, 400L)
                )
            ),
            descriptorRead = true,
            stale = true
        )

        val skip = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.ISO to 400L)), stale
        ).single() as PresetPlanItem.Skip

        assertEquals(PresetItemStatus.NOT_PROBED, skip.status)
    }

    // ── 换镜头档位过滤（验收第 1 条）────────────────────────────────

    @Test
    fun `a lens change rejects only the value the camera no longer offers`() {
        // 这份预设是在 f/1.8 定焦上存的；换回套头后光圈档位里没有 180
        val fromPrimeLens = preset(
            values = mapOf(
                CameraCapability.ISO to 400L,
                CameraCapability.F_NUMBER to 180L,
                CameraCapability.EXPOSURE_BIAS to -2000L
            )
        )

        val plan = PresetPlanner.plan(fromPrimeLens, kitLens())

        assertEquals(
            listOf(CameraCapability.ISO, CameraCapability.EXPOSURE_BIAS),
            writesOf(plan).map { it.capability }
        )
        val rejected = skipsOf(plan).single()
        assertEquals(CameraCapability.F_NUMBER, rejected.capability)
        assertEquals(PresetItemStatus.VALUE_NOT_ALLOWED, rejected.status)
        // 拒绝理由要能指出是哪个属性码没给这个值，排查时不用猜
        assertTrue(rejected.reason.contains("0x5007"))
    }

    @Test
    fun `an exposure bias value off the reported step is rejected`() {
        val skip = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.EXPOSURE_BIAS to -300L)), kitLens()
        ).single() as PresetPlanItem.Skip

        assertEquals(PresetItemStatus.VALUE_NOT_ALLOWED, skip.status)
    }

    @Test
    fun `a read-only capability reports not writable not value not allowed`() {
        val caps = CameraCapabilities.fromDescriptors(
            identity = identity,
            props = mapOf(
                propCode.getValue(CameraCapability.F_NUMBER) to
                    readOnly(CameraCapability.F_NUMBER, typeUInt16, value = 350L, supported = listOf(350L))
            ),
            captureSupport = CapabilityState.WRITABLE
        )
        assertEquals(CapabilityState.READ_ONLY, caps.stateOf(CameraCapability.F_NUMBER))

        val skip = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.F_NUMBER to 350L)), caps
        ).single() as PresetPlanItem.Skip

        assertEquals(PresetItemStatus.CAPABILITY_NOT_WRITABLE, skip.status)
    }

    @Test
    fun `plan carries the prop code and width reported by the camera`() {
        val write = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.ISO to 400L)), kitLens()
        ).single() as PresetPlanItem.Write

        assertEquals(SonyDevicePropCode.ISO, write.propCode)
        // ZV-E10 的 0xD21E 上报 UINT32：宽度必须跟着描述符走，不能按属性码硬编码
        assertEquals(4, write.valueSize)
    }

    // ── 成功以读回为准（验收第 3 条）────────────────────────────────

    @Test
    fun `command acceptance alone is never reported as success`() {
        val plan = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.ISO to 400L, CameraCapability.F_NUMBER to 350L)),
            kitLens()
        )
        assertEquals(2, plan.size)

        val outcomes = PresetPlanner.outcomes(
            plan,
            // ISO：相机回 OK 但实际没改；光圈：相机直接不回 OK
            sendResults = mapOf(CameraCapability.ISO to true, CameraCapability.F_NUMBER to false),
            readBack = CameraSettings(isoRaw = 200L, fNumberRaw = 350L)
        )

        val iso = outcomes.first { it.capability == CameraCapability.ISO }
        assertEquals(PresetItemStatus.READBACK_MISMATCH, iso.status)
        assertEquals(400L, iso.expected)
        assertEquals(200L, iso.actual)

        val fNumber = outcomes.first { it.capability == CameraCapability.F_NUMBER }
        assertEquals(PresetItemStatus.CAMERA_REFUSED, fNumber.status)
    }

    @Test
    fun `an item counts as applied only when the camera accepted and adopted it`() {
        val plan = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.ISO to 400L)), kitLens()
        )

        val outcome = PresetPlanner
            .outcomes(plan, mapOf(CameraCapability.ISO to true), CameraSettings(isoRaw = 400L))
            .single()

        assertEquals(PresetItemStatus.APPLIED, outcome.status)
        assertEquals(400L, outcome.actual)
    }

    @Test
    fun `a missing readback value cannot be mistaken for success`() {
        // 相机回 OK，但重读时该项缺失（属性没回来）：宁可报不一致，也不报成功
        val plan = PresetPlanner.plan(
            preset(values = mapOf(CameraCapability.ISO to 400L)), kitLens()
        )

        val outcome = PresetPlanner
            .outcomes(plan, mapOf(CameraCapability.ISO to true), CameraSettings())
            .single()

        assertEquals(PresetItemStatus.READBACK_MISMATCH, outcome.status)
        assertEquals(null, outcome.actual)
    }
}
