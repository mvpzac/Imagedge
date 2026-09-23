package com.imagedge.camera.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 0x9209 描述符解析（枚举表 / 取值范围 / 符号扩展 / 畸形输入）
 * </pre>
 */
class DevicePropParserTest {

    private val typeInt16 = 0x0003
    private val typeUInt16 = 0x0004
    private val typeUInt32 = 0x0006

    /**
     * 按 Camera Control PTP 3 描述符布局拼装单个属性：
     * Code(2) + DataType(2) + GetSet(1) + IsEnabled(1) + Reserved(N) + CurrentValue(N) + FormFlag(1) + form data
     */
    private fun descriptor(
        code: Int,
        dataType: Int,
        getSet: Int,
        enabled: Boolean,
        value: Long,
        valueSize: Int = DevicePropParser.typeSizeOf(dataType) ?: 1,
        formFlag: Int? = null,
        enumeration: List<Long> = emptyList(),
        range: Triple<Long, Long, Long>? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeLe(code, 2)
        out.writeLe(dataType, 2)
        out.write(getSet)
        out.write(if (enabled) 1 else 0)
        out.write(ByteArray(valueSize))
        out.writeLe(value, valueSize)
        if (formFlag != null) {
            out.write(formFlag)
            when (formFlag) {
                0x01 -> {
                    val (min, max, step) = requireNotNull(range) { "Range 形式必须给 range" }
                    out.writeLe(min, valueSize)
                    out.writeLe(max, valueSize)
                    out.writeLe(step, valueSize)
                }
                0x02 -> {
                    out.writeLe(enumeration.size, 2)
                    enumeration.forEach { out.writeLe(it, valueSize) }
                }
            }
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe(value: Long, size: Int) {
        for (i in 0 until size) write(((value shr (8 * i)) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeLe(value: Int, size: Int) = writeLe(value.toLong(), size)

    /** 8 字节 bulk 头（解析器按属性码搜索，头内容不影响结果） */
    private fun bulkHeader() = ByteArray(8)

    @Test
    fun `enumeration form reports supported values and settable flag`() {
        val data = bulkHeader() + descriptor(
            code = SonyDevicePropCode.F_NUMBER,
            dataType = typeUInt16,
            getSet = 0x01,
            enabled = true,
            value = 350L,
            formFlag = 0x02,
            enumeration = listOf(350L, 400L, 560L)
        )

        val prop = DevicePropParser.findProperty(data, SonyDevicePropCode.F_NUMBER)

        requireNotNull(prop)
        assertTrue(prop.settable)
        assertTrue(prop.enabled)
        assertEquals(350L, prop.currentValue)
        assertEquals(listOf(350L, 400L, 560L), prop.supported)
        assertNull(prop.range)
        assertEquals(2, prop.valueSize)
    }

    @Test
    fun `range form reports min max step`() {
        val data = bulkHeader() + descriptor(
            code = SonyDevicePropCode.EXPOSURE_BIAS,
            dataType = typeInt16,
            getSet = 0x01,
            enabled = true,
            value = 0L,
            formFlag = 0x01,
            range = Triple(0xF448L, 0x0BB8L, 333L)   // -3000 / +3000 / 333（原始补码）
        )

        val prop = DevicePropParser.findProperty(data, SonyDevicePropCode.EXPOSURE_BIAS)

        requireNotNull(prop)
        val range = requireNotNull(prop.range)
        // 解析层保留协议原始值，不做符号解释
        assertEquals(0xF448L, range.min)
        assertEquals(0x0BB8L, range.max)
        assertEquals(333L, range.step)
        // 符号语义由调用方按需解释
        assertEquals(-3000L, prop.signed(range.min))
        assertEquals(3000L, prop.signed(range.max))
        assertTrue(prop.isSigned)
    }

    @Test
    fun `signed helper sign-extends only signed data types`() {
        val signed = DevicePropParser.findProperty(
            bulkHeader() + descriptor(SonyDevicePropCode.EXPOSURE_BIAS, typeInt16, 0x01, true, 0xFFFFL),
            SonyDevicePropCode.EXPOSURE_BIAS
        )
        requireNotNull(signed)
        assertTrue(signed.isSigned)
        assertEquals(0xFFFFL, signed.currentValue)
        assertEquals(-1L, signed.signed(0xFFFFL))

        val unsigned = DevicePropParser.findProperty(
            bulkHeader() + descriptor(SonyDevicePropCode.WHITE_BALANCE, typeUInt16, 0x01, true, 0xFFFFL),
            SonyDevicePropCode.WHITE_BALANCE
        )
        requireNotNull(unsigned)
        assertFalse(unsigned.isSigned)
        assertEquals(0xFFFFL, unsigned.signed(0xFFFFL))
    }

    @Test
    fun `read-only descriptor is not settable`() {
        val data = bulkHeader() + descriptor(
            code = SonyDevicePropCode.SHUTTER_SPEED_HIGH,
            dataType = typeUInt32,
            getSet = 0x00,
            enabled = true,
            value = 1L
        )

        val prop = DevicePropParser.findProperty(data, SonyDevicePropCode.SHUTTER_SPEED_HIGH)

        requireNotNull(prop)
        assertFalse(prop.settable)
    }

    @Test
    fun `disabled descriptor stays distinguishable from read-only`() {
        val data = bulkHeader() + descriptor(
            code = SonyDevicePropCode.ISO,
            dataType = typeUInt32,
            getSet = 0x01,
            enabled = false,
            value = 200L
        )

        val prop = DevicePropParser.findProperty(data, SonyDevicePropCode.ISO)

        requireNotNull(prop)
        assertTrue(prop.settable)
        assertFalse(prop.enabled)
    }

    @Test
    fun `absent property returns null instead of a fabricated value`() {
        val data = bulkHeader() + descriptor(
            code = SonyDevicePropCode.ISO,
            dataType = typeUInt32,
            getSet = 0x01,
            enabled = true,
            value = 200L
        )

        assertNull(DevicePropParser.findProperty(data, SonyDevicePropCode.WHITE_BALANCE))
        assertTrue(DevicePropParser.parse(data, listOf(SonyDevicePropCode.WHITE_BALANCE)).isEmpty())
    }

    @Test
    fun `truncated descriptor never reads out of bounds`() {
        val full = bulkHeader() + descriptor(
            code = SonyDevicePropCode.F_NUMBER,
            dataType = typeUInt16,
            getSet = 0x01,
            enabled = true,
            value = 350L,
            formFlag = 0x02,
            enumeration = listOf(350L, 400L)
        )
        // 砍到 CurrentValue 中间：既不能崩，也不能给出半个属性
        assertNull(DevicePropParser.findProperty(full.copyOf(14), SonyDevicePropCode.F_NUMBER))
        // 砍到枚举表中间：只返回能完整读出的那一项
        val prop = DevicePropParser.findProperty(full.copyOf(full.size - 2), SonyDevicePropCode.F_NUMBER)
        requireNotNull(prop)
        assertEquals(listOf(350L), prop.supported)
    }

    @Test
    fun `unknown data type is skipped without desync`() {
        val data = bulkHeader() +
            // 0x4001 不是已知数据类型，解析器必须跳过这个描述符而不是错位读取
            descriptor(code = SonyDevicePropCode.FOCUS_MODE, dataType = 0x4001, getSet = 0x01, enabled = true, value = 0L) +
            descriptor(
                code = SonyDevicePropCode.ISO,
                dataType = typeUInt32,
                getSet = 0x01,
                enabled = true,
                value = 400L
            )

        assertNull(DevicePropParser.findProperty(data, SonyDevicePropCode.FOCUS_MODE))
        val prop = DevicePropParser.findProperty(data, SonyDevicePropCode.ISO)
        requireNotNull(prop)
        assertEquals(400L, prop.currentValue)
    }

    @Test
    fun `hostile enumeration count does not allocate an unbounded list`() {
        val data = bulkHeader() + descriptor(
            code = SonyDevicePropCode.ISO,
            dataType = typeUInt32,
            getSet = 0x01,
            enabled = true,
            value = 200L,
            formFlag = 0x02,
            // 声明 65535 个取值但一个都没跟上：超出 200 上限必须整表丢弃
            enumeration = emptyList()
        ).let { bytes ->
            bytes.copyOf(bytes.size - 2) + byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        }

        val prop = DevicePropParser.findProperty(data, SonyDevicePropCode.ISO)

        requireNotNull(prop)
        assertEquals(200L, prop.currentValue)
        assertTrue(prop.supported.isEmpty())
    }
}
