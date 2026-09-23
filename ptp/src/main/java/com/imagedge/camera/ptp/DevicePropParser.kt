package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 索尼 0x9209（GetAllExtDevicePropInfo）返回的 bulk 数据解析
 *             描述符布局（Camera Control PTP 3 Reference）：
 *               PropertyCode(2) + DataType(2) + GetSet(1) + IsEnabled(1)
 *               + Reserved(N) + CurrentValue(N) + FormFlag(1) [+ form data]
 *             N = 数据类型字节宽。
 *     version: 1.0
 * </pre>
 */

/** FormFlag=0x01（Range）上报的取值范围；min/max/step 均为原始值 */
data class ValueRange(
    val min: Long,
    val max: Long,
    val step: Long
)

/** 单个设备属性解析结果 */
data class DeviceProperty(
    val code: Int,
    val dataType: Int,
    /** 0x01 = 可读写，其余 = 只读 */
    val getSet: Int,
    val enabled: Boolean,
    val currentValue: Long,
    /** 枚举值表（0x9209 描述符 FormFlag=0x02 时相机上报的合法取值列表） */
    val supported: List<Long> = emptyList(),
    /** 取值范围（FormFlag=0x01）；null = 相机未以 Range 形式上报 */
    val range: ValueRange? = null
) {
    /** 相机是否允许经 0x9205 设置该属性 */
    val settable: Boolean get() = getSet == 0x01

    /** 值字节宽（由 dataType 推出）；未知类型为 0 */
    val valueSize: Int get() = DevicePropParser.typeSizeOf(dataType) ?: 0

    /** 是否为有符号数据类型（INT8/16/32/64） */
    val isSigned: Boolean get() = dataType in DevicePropParser.SIGNED_DATA_TYPES

    /**
     * 按 dataType 宽度做符号扩展（INT16 的 0xFFFF → -1）；无符号或宽度未知时原样返回。
     *
     * 解析层保留协议原始值，符号语义由调用方按需解释——曝光补偿（0x5010 INT16 EV×1000）
     * 的负档位必须扩展后才能正确排序与格式化。
     */
    fun signed(raw: Long): Long {
        val size = valueSize
        if (!isSigned || size < 1 || size > 7) return raw
        val shift = 64 - size * 8
        return (raw shl shift) shr shift
    }
}

/**
 * 从 0x9209 bulk 数据中按「2 字节属性码小端搜索」提取指定属性的当前值。
 * 逐个属性码搜索比顺序解析更稳健——bulk 数据有 8 字节头 + 未知描述符，
 * 顺序解析易因对齐/未知类型失步（sony-alpha-python 同此策略）。
 */
object DevicePropParser {

    /** 数据类型 → 字节宽 */
    private val typeSize = mapOf(
        0x0001 to 1, 0x0002 to 1,   // INT8 / UINT8
        0x0003 to 2, 0x0004 to 2,   // INT16 / UINT16
        0x0005 to 4, 0x0006 to 4,   // INT32 / UINT32
        0x0007 to 8, 0x0008 to 8    // INT64 / UINT64
    )

    /** 有符号数据类型（INT8/16/32/64），符号扩展时用 */
    val SIGNED_DATA_TYPES = setOf(0x0001, 0x0003, 0x0005, 0x0007)

    /** 数据类型字节宽；未知类型返回 null */
    fun typeSizeOf(dataType: Int): Int? = typeSize[dataType]

    /** 提取指定属性码的当前值 */
    fun parse(data: ByteArray, targetCodes: List<Int>): Map<Int, DeviceProperty> {
        val result = mutableMapOf<Int, DeviceProperty>()
        for (code in targetCodes) {
            findProperty(data, code)?.let { result[code] = it }
        }
        return result
    }

    /** 提取单个属性码（可能返回 null） */
    fun findProperty(data: ByteArray, code: Int): DeviceProperty? {
        val needle = byteArrayOf((code and 0xFF).toByte(), ((code shr 8) and 0xFF).toByte())
        var idx = indexOf(data, needle, 0)
        while (idx >= 0) {
            if (idx + 6 > data.size) return null
            val dataType = (data[idx + 2].toInt() and 0xFF) or ((data[idx + 3].toInt() and 0xFF) shl 8)
            val valSize = typeSize[dataType]
            if (valSize == null) {
                // 未知类型：跳到下一个可能的匹配位置继续搜
                idx = indexOf(data, needle, idx + 2)
                continue
            }
            val getSet = data[idx + 4].toInt() and 0xFF
            val enabled = (data[idx + 5].toInt() and 0xFF) == 1
            val cvOffset = idx + 6 + valSize
            if (cvOffset + valSize > data.size) return null
            val value = readLittleEndian(data, cvOffset, valSize)

            // FormFlag + 取值形式（Camera Control PTP 3 Reference）：
            //   0x01 = Range       → minValue(N) + maxValue(N) + stepSize(N)
            //   0x02 = Enumeration → NumberOfValues(2) + values[N]
            var supported = emptyList<Long>()
            var range: ValueRange? = null
            val formOffset = cvOffset + valSize
            if (formOffset < data.size) {
                when (data[formOffset].toInt() and 0xFF) {
                    0x01 -> {
                        val ro = formOffset + 1
                        if (ro + valSize * 3 <= data.size) {
                            range = ValueRange(
                                min = readLittleEndian(data, ro, valSize),
                                max = readLittleEndian(data, ro + valSize, valSize),
                                step = readLittleEndian(data, ro + valSize * 2, valSize)
                            )
                        }
                    }
                    0x02 -> {
                        val values = mutableListOf<Long>()
                        var eo = formOffset + 1
                        if (eo + 2 <= data.size) {
                            val numValues = readLittleEndian(data, eo, 2).toInt()
                            eo += 2
                            if (numValues in 1..200) {
                                repeat(numValues) {
                                    if (eo + valSize <= data.size) {
                                        values.add(readLittleEndian(data, eo, valSize))
                                        eo += valSize
                                    }
                                }
                            }
                        }
                        supported = values
                    }
                }
            }
            return DeviceProperty(code, dataType, getSet, enabled, value, supported, range)
        }
        return null
    }

    private fun readLittleEndian(data: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        for (i in 0 until size) {
            value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
