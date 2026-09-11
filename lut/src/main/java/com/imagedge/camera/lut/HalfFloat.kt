package com.imagedge.camera.lut

/**
 * IEEE 754 单精度 → 半精度（16 位）转换。
 *
 * 用途：把 3D LUT 以 `GL_RGB16F` 上传。**为什么用半精度而不是 `GL_RGB32F`**：
 * ES 3.0 只保证半浮点纹理可做线性过滤，32 位浮点纹理的线性过滤依赖
 * `OES_texture_float_linear` 扩展，不保证存在——而 LUT 的插值正是靠纹理过滤完成的。
 * 半精度对 0..1 的 LUT 系数约有 3 位十进制精度，足够（.cube 本身也很少给到更多）。
 *
 * 不直接用 `android.util.Half`：其一，lint 的 `HalfFloat` 检查会对
 * `shorts[i] = Half.toHalf(x)` 这种写法误报；其二，自己实现可以让这段位运算
 * 直接进 JVM 单测（见 `HalfFloatTest`）。
 */
internal object HalfFloat {

    /** @return 半精度的位模式（按 short 返回，写入 Buffer 时按本机字节序上传） */
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xFF
        var mantissa = bits and 0x007FFFFF

        // Inf / NaN
        if (exponent == 0xFF) {
            return (sign or 0x7C00 or (if (mantissa != 0) 0x0200 else 0)).toShort()
        }

        // 单精度偏移 127、半精度偏移 15 → 差值 112
        val e = exponent - 112
        if (e >= 0x1F) return (sign or 0x7C00).toShort() // 上溢 → Inf
        if (e <= 0) {
            if (e < -10) return sign.toShort() // 下溢 → ±0
            // 非规格化：补回隐式前导 1，再右移
            mantissa = mantissa or 0x00800000
            val shift = 14 - e
            var m = mantissa shr shift
            if (((mantissa shr (shift - 1)) and 0x1) != 0) m += 1
            return (sign or m).toShort()
        }

        var result = sign or (e shl 10) or (mantissa shr 13)
        // 就近舍入（余数正好一半时向偶数舍入）
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (result and 1) != 0)) result += 1
        return result.toShort()
    }
}
