package com.imagedge.camera.lut

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 3D LUT 上传前的打包（半精度位模式），从 GL 调用中拆出来以便 JVM 单测
 * </pre>
 */

/**
 * 把 LUT 系数打成半精度位模式，供 `GL_RGB16F` 的 3D 纹理上传。
 *
 * **刻意不做 0..1 钳制**：胶片模拟类 `.cube` 常规用 >1 的值表达高光滚降、
 * <0 的值表达暗部密度，钳掉等于把这些滚降整段抹平。越界值要一路带到着色器里，
 * 由写 RGBA8 目标这一步自然截断——[CpuLutProcessor] 走的正是同一条路
 * （`mixFloat` 只在最后 `coerceIn(0f, 255f)`），两条路径必须对同一份 LUT 得出同一张图。
 */
internal fun packLutForTexture(data: FloatArray, size: Int): ShortArray {
    val texelCount = size * size * size
    val packed = ShortArray(texelCount * 3)
    for (i in packed.indices) {
        // 钳到半精度的有限范围，而不是 0..1：越界值要留着，但 HalfFloat 把 >65504
        // 映射成 +Inf，着色器里 mix(c, Inf, 0) = NaN，整张图会黑掉。
        // 病态 .cube 才写得出这种数，但它不该换来一张坏图。
        packed[i] = HalfFloat.fromFloat(data[i].coerceIn(-65504f, 65504f))
    }
    return packed
}
