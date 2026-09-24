package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport
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
 *     desc   : 预设导出文档验收：往返一致、凭据不落地、校验和与越界整份拒绝
 * </pre>
 */
class PresetDocumentTest {

    private val identity = CameraIdentity(
        model = "ZV-E10",
        firmware = "2.03",
        transport = CameraTransport.PTP_IP,
        mode = CameraIdentity.MODE_REMOTE_CONTROL
    )

    private fun preset(
        name: String,
        id: String = "id-$name",
        values: Map<CameraCapability, Long> = mapOf(
            CameraCapability.ISO to 400L,
            CameraCapability.F_NUMBER to 350L,
            CameraCapability.EXPOSURE_BIAS to -2000L
        )
    ) = ParameterPreset(
        id = id,
        profileKey = identity.profileKey,
        name = name,
        values = values,
        createdAt = 1000L,
        updatedAt = 2000L
    )

    private fun roundTrip(list: List<ParameterPreset>) =
        PresetDocument.decode(PresetDocument.encode(list, identity, 123L), identity.profileKey)

    // ── 往返 ─────────────────────────────────────────────────────────

    @Test
    fun `encode then decode preserves names profile key and every raw value`() {
        val source = listOf(preset("街拍"), preset("夜景", "id-night"))

        val result = roundTrip(source)

        assertNull(result.failure)
        assertTrue(result.rejected.isEmpty())
        assertTrue(result.isUsable)
        assertEquals(source.map { it.name }, result.presets.map { it.name })
        assertEquals(source.map { it.values }, result.presets.map { it.values })
        assertEquals(source.map { it.id }, result.presets.map { it.id })
        assertEquals(source.map { it.createdAt }, result.presets.map { it.createdAt })
        result.presets.forEach { assertEquals(identity.profileKey, it.profileKey) }
    }

    @Test
    fun `a negative exposure bias survives the round trip`() {
        // 曝光补偿是有符号值：一旦某处按无符号处理，-2000 会变成 63536 并被值校验挡下
        val result = roundTrip(
            listOf(preset("暗光", values = mapOf(CameraCapability.EXPOSURE_BIAS to -2000L)))
        )

        assertEquals(-2000L, result.presets.single().values.getValue(CameraCapability.EXPOSURE_BIAS))
    }

    @Test
    fun `the export file never carries a credential shaped field`() {
        // 「凭据不进导出」由类型结构保证：档案键只有型号 + 固件，条目只有能力与值。
        // 这条测试是防回归的围栏，不是实现手段
        val text = PresetDocument.encode(listOf(preset("街拍")), identity, 123L)

        listOf("password", "passphrase", "ssid", "bssid", "wpa", "networkkey", "preshared")
            .forEach { token ->
                assertFalse("导出内容出现了 $token", text.contains(token, ignoreCase = true))
            }
    }

    // ── 校验和与损坏 ─────────────────────────────────────────────────

    @Test
    fun `a tampered payload is rejected as a whole`() {
        val text = PresetDocument.encode(listOf(preset("街拍")), identity, 123L)
            .replace("街拍", "白天")

        val result = PresetDocument.decode(text, identity.profileKey)

        assertNotNull(result.failure)
        assertTrue(result.failure!!.contains("校验和"))
        assertTrue("被改过的文件不能有部分被接受", result.presets.isEmpty())
        assertFalse(result.isUsable)
    }

    @Test
    fun `null empty and malformed files fail with a reason rather than throwing`() {
        listOf(null, "", "not json at all", "{", "[]").forEach { raw ->
            val result = PresetDocument.decode(raw, identity.profileKey)
            assertNotNull("$raw 应被拒绝", result.failure)
            assertTrue(result.presets.isEmpty())
        }
    }

    @Test
    fun `an unknown extra field is refused rather than tolerated`() {
        // 严格解析：文件来自外部存储，宽容对待未知键会把被扩写的结构当成合法的，
        // 也会让新版本写出的文件被旧版本半懂不懂地接受
        val withExtra = PresetDocument.encode(listOf(preset("街拍")), identity, 123L)
            .removeSuffix("}") + ",\"someoneElsesField\":1}"

        assertNotNull(PresetDocument.decode(withExtra, identity.profileKey).failure)
    }

    @Test
    fun `an oversized file is refused before parsing`() {
        val huge = "x".repeat(PresetDocument.MAX_FILE_CHARS + 1)

        val result = PresetDocument.decode(huge, identity.profileKey)

        assertNotNull(result.failure)
        assertTrue(result.failure!!.contains("文件过大"))
    }

    // ── 条目与字段上限 ───────────────────────────────────────────────

    @Test
    fun `a preset with no parameter items is rejected on its own`() {
        // 我们自己的编码器会原样写出这种预设（例如手工存的空预设），解码端必须拒收：
        // 一个不含任何参数的「预设」应用时什么都不会发生，却会在界面上显示为已导入
        val result = roundTrip(listOf(preset("空预设", values = emptyMap())))

        assertTrue(result.presets.isEmpty())
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().contains("参数项数量异常"))
    }

    @Test
    fun `non parameter capabilities never make it into the file`() {
        // CAPTURE 是动作不是参数，混进预设会在应用时被当成参数项
        val result = roundTrip(
            listOf(preset("混入", values = mapOf(CameraCapability.ISO to 200L, CameraCapability.CAPTURE to 1L)))
        )

        val values = result.presets.single().values
        assertEquals(mapOf(CameraCapability.ISO to 200L), values)
    }

    @Test
    fun `encoding clamps the preset name to the storage limit`() {
        val longName = "名".repeat(CameraProfileStore.MAX_PRESET_NAME_CHARS + 5)

        val result = roundTrip(listOf(preset(longName)))

        assertEquals(CameraProfileStore.MAX_PRESET_NAME_CHARS, result.presets.single().name.length)
    }

    @Test
    fun `encoding never writes more presets than the format allows`() {
        val many = (1..PresetDocument.MAX_PRESETS + 5).map { preset("p$it", "id-$it") }

        val result = roundTrip(many)

        assertNull(result.failure)
        assertEquals(PresetDocument.MAX_PRESETS, result.presets.size)
    }

    // ── 档案不匹配 ───────────────────────────────────────────────────

    @Test
    fun `presets for another camera still import but are flagged as not applicable`() {
        // 「错误机型被拒绝」这条验收落在**应用**环节（PresetPlanner 整份拒绝）；
        // 导入本身允许存档——带着预设去下次连那台机器是合理需求，但必须当场说清楚
        val result = PresetDocument.decode(
            PresetDocument.encode(listOf(preset("街拍")), identity, 123L),
            connectedProfileKey = "ILCE-7RM5|3.10"
        )

        assertEquals(1, result.presets.size)
        assertTrue(result.rejected.any { it.contains("当前相机不同") })
    }

    @Test
    fun `importing with no camera connected claims nothing about ownership`() {
        // 没连相机时没有对照物。此时报「与当前相机不同」是**猜**出来的结论，
        // 而导入本来就不授权任何下发——应用时刻还会再判一次归属
        val result = PresetDocument.decode(
            PresetDocument.encode(listOf(preset("街拍")), identity, 123L),
            connectedProfileKey = null
        )

        assertNull(result.failure)
        assertEquals(1, result.presets.size)
        assertTrue(result.rejected.isEmpty())
        // 归属信息仍随预设保存，等连上那台机器再判定
        assertEquals(identity.profileKey, result.presets.single().profileKey)
    }
}
