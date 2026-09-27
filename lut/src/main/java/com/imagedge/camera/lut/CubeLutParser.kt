package com.imagedge.camera.lut

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : .cube LUT 解析器（Adobe Cube LUT 规范子集：LUT_3D_SIZE 3D 表；
 *              1D LUT 暂不支持）。纯 Kotlin，lut 模块保持无 Android/DI 依赖。
 *     version: 1.0
 * </pre>
 */

/** 3D LUT 数据：size³ 个 RGB 三元组（0..1），索引 = r + g*size + b*size*size */
data class CubeLut(val size: Int, val data: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is CubeLut && other.size == size && other.data.contentEquals(data)
    override fun hashCode(): Int = size * 31 + data.contentHashCode()
}

object CubeLutParser {

    /**
     * 解析 .cube 文本内容
     * @return 解析失败（缺 LUT_3D_SIZE 或数据不足）返回 null
     */
    fun parse(content: String): CubeLut? {
        var size = -1
        var domainMin: FloatArray? = null
        var domainMax: FloatArray? = null
        val values = mutableListOf<Float>()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith("TITLE", ignoreCase = true) -> Unit
                line.startsWith("DOMAIN_MIN", ignoreCase = true) ->
                    domainMin = parseDomain(line) ?: return null
                line.startsWith("DOMAIN_MAX", ignoreCase = true) ->
                    domainMax = parseDomain(line) ?: return null
                line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> return null // 1D 不支持
                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    size = line.split(Regex("\\s+")).lastOrNull()?.toIntOrNull() ?: -1
                    if (size !in 2..65) return null
                }
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull() ?: continue
                        val g = parts[1].toFloatOrNull() ?: continue
                        val b = parts[2].toFloatOrNull() ?: continue
                        values.add(r); values.add(g); values.add(b)
                    }
                }
            }
        }
        // 输入域不是 0..1 就**拒收**。照 0..1 解释会画出错色，而用户没有任何线索；
        // darktable 同样选择报错。这里也接受按 1D 退化写法只给一个标量的情形。
        if (!isUnitDomain(domainMin, 0f) || !isUnitDomain(domainMax, 1f)) return null
        if (size <= 0) return null
        val expected = size * size * size * 3
        if (values.size < expected) return null
        return CubeLut(size, values.toFloatArray().copyOf(expected))
    }

    /** 解析 `DOMAIN_MIN 0 0 0` / `DOMAIN_MAX 1 1 1`；只给一个标量时三通道取同一值。 */
    private fun parseDomain(line: String): FloatArray? {
        val nums = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toFloatOrNull() }
        return when (nums.size) {
            1 -> floatArrayOf(nums[0], nums[0], nums[0])
            3 -> floatArrayOf(nums[0], nums[1], nums[2])
            else -> null
        }
    }

    private fun isUnitDomain(domain: FloatArray?, expected: Float): Boolean {
        val d = domain ?: return true
        return d.all { kotlin.math.abs(it - expected) < 1e-6f }
    }

    /**
     * 由映射函数生成内置 LUT（暖阳/冷调/黑白/胶片等程序化预设）
     * @param transform 输入输出均为 0..1 的 RGB
     */
    fun generate(size: Int = 8, transform: (r: Float, g: Float, b: Float) -> Triple<Float, Float, Float>): CubeLut {
        val data = FloatArray(size * size * size * 3)
        var i = 0
        val step = 1f / (size - 1)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val (ro, go, bo) = transform(r * step, g * step, b * step)
                    data[i++] = ro.coerceIn(0f, 1f)
                    data[i++] = go.coerceIn(0f, 1f)
                    data[i++] = bo.coerceIn(0f, 1f)
                }
            }
        }
        return CubeLut(size, data)
    }
}
