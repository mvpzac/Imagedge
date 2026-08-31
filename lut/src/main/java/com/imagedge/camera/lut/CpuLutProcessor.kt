package com.imagedge.camera.lut

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : LUT CPU 处理器（M3 先行版：纯 Kotlin 三线性插值）。
 *              1080p 图约 1~2s；后续 VulkanLutProcessor（NDK 计算着色器）为 GPU 路径，
 *              处理器选择策略届时按图像规模/设备能力智能选择（参考 Lut2Photo）。
 *     version: 1.0
 * </pre>
 */
class CpuLutProcessor : LutProcessor {

    override suspend fun apply(
        pixels: ByteArray,
        width: Int,
        height: Int,
        lutData: FloatArray,
        lutSize: Int,
        strength: Int
    ): ByteArray {
        val out = ByteArray(pixels.size)

        // 退化 LUT（少于 2 个采样点）无法插值，原样返回。
        // 同时避免 maxIndex = 0 时 `coerceAtMost(maxIndex - 1)` 得到 -1
        // 造成 lutData 负索引越界崩溃。
        if (lutSize < 2) {
            System.arraycopy(pixels, 0, out, 0, pixels.size)
            return out
        }

        val maxIndex = lutSize - 1
        val strengthF = strength.coerceIn(0, 100) / 100f
        val n = lutSize
        val n2 = n * n

        var p = 0
        while (p < pixels.size) {
            // RGBA8 → 归一化 RGB
            val r = (pixels[p].toInt() and 0xFF) / 255f
            val g = (pixels[p + 1].toInt() and 0xFF) / 255f
            val b = (pixels[p + 2].toInt() and 0xFF) / 255f

            // 三线性插值：8 个角点
            val fx = r * maxIndex
            val fy = g * maxIndex
            val fz = b * maxIndex
            val x0 = fx.toInt().coerceIn(0, maxIndex - 1)
            val y0 = fy.toInt().coerceIn(0, maxIndex - 1)
            val z0 = fz.toInt().coerceIn(0, maxIndex - 1)
            val tx = fx - x0
            val ty = fy - y0
            val tz = fz - z0

            // 8 个角点在 lutData 中的基址（每点 3 个连续 float = RGB）。
            // 原先这里在**每像素**的循环体内声明一个 Function4 lambda `idx` 并调用 24 次：
            // 1080p 意味着 200 万个临时 lambda 对象 + 约 5000 万次装箱虚调用，
            // 光 GC 抖动就让处理时间从"1~2 秒"膨胀到数十秒。改为预先算基址 + 直接数组访问。
            val i000 = (x0 + y0 * n + z0 * n2) * 3
            val i100 = (x0 + 1 + y0 * n + z0 * n2) * 3
            val i010 = (x0 + (y0 + 1) * n + z0 * n2) * 3
            val i110 = (x0 + 1 + (y0 + 1) * n + z0 * n2) * 3
            val i001 = (x0 + y0 * n + (z0 + 1) * n2) * 3
            val i101 = (x0 + 1 + y0 * n + (z0 + 1) * n2) * 3
            val i011 = (x0 + (y0 + 1) * n + (z0 + 1) * n2) * 3
            val i111 = (x0 + 1 + (y0 + 1) * n + (z0 + 1) * n2) * 3

            // 三通道（R/G/B）逐个插值，结果直接写回 out，省去中间变量
            var c = 0
            while (c < 3) {
                val c000 = lutData[i000 + c]; val c100 = lutData[i100 + c]
                val c010 = lutData[i010 + c]; val c110 = lutData[i110 + c]
                val c001 = lutData[i001 + c]; val c101 = lutData[i101 + c]
                val c011 = lutData[i011 + c]; val c111 = lutData[i111 + c]

                val c00 = c000 + (c100 - c000) * tx
                val c10 = c010 + (c110 - c010) * tx
                val c01 = c001 + (c101 - c001) * tx
                val c11 = c011 + (c111 - c011) * tx
                val c0 = c00 + (c10 - c00) * ty
                val c1 = c01 + (c11 - c01) * ty
                val v = c0 + (c1 - c0) * tz

                // 强度混合：strength=0 输出原图
                out[p + c] = mix(pixels[p + c], v * 255f, strengthF)
                c++
            }
            out[p + 3] = pixels[p + 3]
            p += 4
        }
        return out
    }

    private fun mix(original: Byte, transformed: Float, strength: Float): Byte {
        val o = original.toInt() and 0xFF
        val v = o + (transformed - o) * strength
        return v.coerceIn(0f, 255f).toInt().toByte()
    }
}
