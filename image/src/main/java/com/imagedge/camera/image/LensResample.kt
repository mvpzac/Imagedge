package com.imagedge.camera.image

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 镜头校正的重采样：把 [LensCorrection] 算出的取样坐标真正落到像素上。
 *
 * ## 为什么它不在 `:lut` 那一趟里
 *
 * 因为 **CPU 的双线性与 GPU 的硬件双线性对不到 1 LSB**。`:lut` 的 CPU/GPU 同值是这个仓库
 * 最贵的一条不变量（见 `SelectiveParityTest` 那 ≤1 LSB 的容差），把重采样塞进去会直接把它打破，
 * 而打破它的表现是「同一张照片在有 GPU 与没 GPU 的机器上边缘差半个像素」——极难察觉、
 * 极难查。所以这一层是**位图前处理**，走 CPU，产物再交给 `:lut`。
 *
 * 代价是每张校正过的照片多一份全分辨率缓冲、以及一次 8 位重采样。后者是可以接受的：
 * `:lut` 本来就是从 8 位解码到线性光再编码回去，中间那次往返并不凭空补回精度，
 * 而在 float 上重采样要四倍的内存。
 *
 * ## 两条形状上的约定
 *
 * - **色差是逐通道取样的**：R 与 B 各用自己的坐标，绿色用畸变那一组。四个通道权重分别算，
 *   取一次就复用等于没有色差。
 * - **越界钳到边缘**。取到画面外要么变黑（凭空一块黑洞），要么环绕（把边缘糊到对边）；
 *   钳边的表现是边缘被轻微拉伸——那是唯一不引入新东西的失败方式。
 */
object LensResample {

    private const val R = 0
    private const val G = 1
    private const val B = 2
    private const val A = 3

    /**
     * @param src RGBA8 字节，长度必须是 `width * height * 4`。
     * @return 新的 RGBA8 字节；**零系数时返回入参本身**（同一实例，不是副本）——
     *   调方据此跳过整趟重采样。复制一份 12MP 的缓冲只为「保持契约对称」不划算，
     *   所以要改原图的话先自己拷。
     */
    fun correctRgba(
        src: ByteArray,
        width: Int,
        height: Int,
        params: LensCorrectionParams,
    ): ByteArray {
        // 这一句是**纯性能**：拿掉它输出照样逐位相同（零系数时取样坐标正好是整数，
        // 双线性退化成恒等），所以没有测试能看见它。留着是因为它省掉一整趟 12MP 的重采样。
        if (params.isIdentity) return src
        if (width <= 0 || height <= 0) return src
        if (src.size < width * height * 4) return src

        val out = ByteArray(src.size)
        val lastX = (width - 1).toFloat()
        val lastY = (height - 1).toFloat()

        var p = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = x.toFloat()
                val fy = y.toFloat()
                // 绿通道 = 畸变本身；R 与 B 在它之后再各自按半径缩放
                val gx = LensCorrection.sourceX(fx, fy, width, height, params, channel = G)
                val gy = LensCorrection.sourceY(fx, fy, width, height, params, channel = G)
                val rx = LensCorrection.sourceX(fx, fy, width, height, params, channel = R)
                val ry = LensCorrection.sourceY(fx, fy, width, height, params, channel = R)
                val bx = LensCorrection.sourceX(fx, fy, width, height, params, channel = B)
                val by = LensCorrection.sourceY(fx, fy, width, height, params, channel = B)

                out[p] = sample(src, width, height, rx, ry, R, lastX, lastY)
                out[p + 1] = sample(src, width, height, gx, gy, G, lastX, lastY)
                out[p + 2] = sample(src, width, height, bx, by, B, lastX, lastY)
                // alpha 逐位透传：校正动的是取样位置，不是不透明度，
                // 而任何一次重采样都会让半透明边缘的 alpha 变浑
                out[p + 3] = src[p + 3]
                p += 4
            }
        }
        return out
    }

    /** 双线性取样，越界钳到边缘。返回 0..255。 */
    private fun sample(
        src: ByteArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
        channel: Int,
        lastX: Float,
        lastY: Float,
    ): Byte {
        if (fx.isNaN() || fy.isNaN()) return 0.toByte()
        val cx = fx.coerceIn(0f, lastX)
        val cy = fy.coerceIn(0f, lastY)
        val x0 = floor(cx).toInt()
        val y0 = floor(cy).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val tx = cx - x0
        val ty = cy - y0

        val i00 = (y0 * width + x0) * 4 + channel
        val i10 = (y0 * width + x1) * 4 + channel
        val i01 = (y1 * width + x0) * 4 + channel
        val i11 = (y1 * width + x1) * 4 + channel

        val c00 = src[i00].toInt() and 0xFF
        val c10 = src[i10].toInt() and 0xFF
        val c01 = src[i01].toInt() and 0xFF
        val c11 = src[i11].toInt() and 0xFF

        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        val v = top + (bottom - top) * ty
        return v.roundToInt().coerceIn(0, 255).toByte()
    }
}
