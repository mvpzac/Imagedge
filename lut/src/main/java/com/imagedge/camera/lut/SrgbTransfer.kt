package com.imagedge.camera.lut

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : sRGB 传输函数（编解码）与线性光下的调色常量
 * </pre>
 */

/**
 * sRGB ↔ 线性光的转换，**CPU 与 GPU 着色器必须用同一套**。
 *
 * 存在的理由是一个具体的错误：曝光是对**光量**的乘性操作，
 * 在 gamma 编码空间里做同样的乘法，一档曝光会把 128 这样的中间调直接顶到 255
 * （线性下应是 176）——暗部和中间调全被压死，亮部早早削平。
 *
 * 只在 3D LUT **之外**使用。look LUT（Adobe/Instagram 那一类）的输入域
 * 定义在 sRGB 编码值上，调色进线性、LUT 留 gamma 是正确分工，两边都动不得。
 */
internal object SrgbTransfer {

    /** 输入恒为 8 位，预先算好 256 级解码表：精确，且省掉每像素的 pow */
    private val DECODE: FloatArray = FloatArray(256) { v ->
        val c = v / 255.0
        if (c <= 0.04045) (c / 12.92).toFloat()
        else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    fun decode(v: Int): Float = DECODE[v and 0xFF]

    fun decode(v: Float): Float {
        return if (v <= 0.04045f) v / 12.92f
        else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    fun encode(linear: Float): Float {
        if (linear <= 0.0031308f) return linear * 12.92f
        return (1.055f * Math.pow(linear.toDouble(), 1.0 / 2.4).toFloat()) - 0.055f
    }

    /**
     * 线性光下的 Rec.709 亮度系数。
     *
     * 此前用 Rec.601 权重（0.299/0.587/0.114）算 gamma 编码值的加权和——
     * 那既不是亮度也不是任何标准定义的亮度。去色应当落在人眼亮度上。
     */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** 对比度支点：18% 灰（线性）。编码空间里它约等于 118，不是 128。 */
    const val CONTRAST_PIVOT = 0.18f

    /** 阴影提升幅度：+100 大约把深暗部抬到 2 倍以上亮度 */
    const val SHADOW_GAIN = 1.2f

    /** 高光恢复幅度：-100 大约把亮部压掉两成 */
    const val HIGHLIGHT_GAIN = 0.8f

    /**
     * 分区影调恢复，**在线性光下**作用于单个通道的线性值。
     *
     * 权重取 (1-v)⁴ 与 v⁴：四次方而不是平方，是为了让两端几乎不互相污染——
     * 平方权重下「抬阴影」仍会把高光推近 9 个级，那样阴影与曝光就分不开了。
     * 四次方下高光只动约 1 级，滑杆才各自有意义。
     *
     * 必须是线性值：编码空间里的 (1-v) 不是「离纯白还有多少光」，暗部会被系统性高估。
     */
    fun recoverTone(linear: Float, shadows: Float, highlights: Float): Float {
        if (shadows == 0f && highlights == 0f) return linear
        val clamped = linear.coerceAtLeast(0f)
        val below = (1f - clamped).coerceAtLeast(0f)
        val sw = below * below
        val hw = clamped * clamped
        return clamped *
            (1f + shadows * SHADOW_GAIN * sw * sw) *
            (1f + highlights * HIGHLIGHT_GAIN * hw * hw)
    }
}
