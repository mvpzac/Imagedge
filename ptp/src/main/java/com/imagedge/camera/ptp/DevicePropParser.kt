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

/** 单个设备属性解析结果 */
data class DeviceProperty(
    val code: Int,
    val dataType: Int,
    /** 0x01 = 可读写，其余 = 只读 */
    val getSet: Int,
    val enabled: Boolean,
    val currentValue: Long,
    /** 枚举值表（0x9209 描述符 FormFlag=0x02 时相机上报的合法取值列表） */
    val supported: List<Long> = emptyList()
) {
    /** 相机是否允许经 0x9205 设置该属性 */
    val settable: Boolean get() = getSet == 0x01
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

            // FormFlag + 枚举表（Camera Control PTP 3 Reference：
            // FormFlag(1)，0x02=Enumeration → NumberOfValues(2) + values[N]）
            val supported = mutableListOf<Long>()
            val formOffset = cvOffset + valSize
            if (formOffset < data.size && (data[formOffset].toInt() and 0xFF) == 0x02) {
                var eo = formOffset + 1
                if (eo + 2 <= data.size) {
                    val numValues = readLittleEndian(data, eo, 2).toInt()
                    eo += 2
                    if (numValues in 1..200) {
                        repeat(numValues) {
                            if (eo + valSize <= data.size) {
                                supported.add(readLittleEndian(data, eo, valSize))
                                eo += valSize
                            }
                        }
                    }
                }
            }
            return DeviceProperty(code, dataType, getSet, enabled, value, supported)
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
