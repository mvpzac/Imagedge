package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : 相机侧色彩档案（Picture Profile / 创意风格）的读取
 *     version: 1.0
 * </pre>
 */

/**
 * 相机当前的色彩设置快照。
 *
 * 这些是**拍摄端**的设置，不是照片里已经烘焙好的东西——它们说明「相机当时打算
 * 怎么渲这张片」。对着 S-Log 素材选 LUT 时 knowing 相机用的是哪条曲线，
 * 比让用户凭肉眼猜要准。
 *
 * 每个调节量都可能读不到：不同机型提供的属性集合不同，读不到即为 null，
 * **不能拿 0 顶替**——0 是「居中」，把它当成「没设置」会把中性风格显示成零偏移。
 */
data class CameraColorProfile(
    /** Picture Profile 编号（0xD23F）。两条应用都提供这个属性。 */
    val pictureProfile: Long?,
    /** 创意风格编号（0xD240）。两条应用都提供这个属性。 */
    val creativeLook: Long?,
    val clContrast: Int?,
    val clHighlights: Int?,
    val clShadows: Int?,
    val clFade: Int?,
    val clSaturation: Int?,
    val clSharpness: Int?,
    val clClarity: Int?
) {
    /** 是否有任何创意风格调节量可用（只有编号、读不到参数时为 false） */
    val hasCreativeLookParams: Boolean
        get() = clContrast != null || clHighlights != null || clShadows != null || clSaturation != null

    companion object {
        /** 什么也没读到。全 null，而非常见的全 0。 */
        val EMPTY = CameraColorProfile(null, null, null, null, null, null, null, null, null)

        /**
         * 从属性描述符表里抽出色彩档案。
         *
         * @param props `0x9209` 解析结果；缺失的项自然为 null
         */
        fun from(props: Map<Int, DeviceProperty>): CameraColorProfile = CameraColorProfile(
            pictureProfile = props[SonyDevicePropCode.PICTURE_PROFILE]?.currentValue,
            creativeLook = props[SonyDevicePropCode.CREATIVE_STYLE]?.currentValue,
            clContrast = props[SonyDevicePropCode.CREATIVE_LOOK_CONTRAST]?.currentValue?.toIntOrNull(),
            clHighlights = props[SonyDevicePropCode.CREATIVE_LOOK_HIGHLIGHTS]?.currentValue?.toIntOrNull(),
            clShadows = props[SonyDevicePropCode.CREATIVE_LOOK_SHADOWS]?.currentValue?.toIntOrNull(),
            clFade = props[SonyDevicePropCode.CREATIVE_LOOK_FADE]?.currentValue?.toIntOrNull(),
            clSaturation = props[SonyDevicePropCode.CREATIVE_LOOK_SATURATION]?.currentValue?.toIntOrNull(),
            clSharpness = props[SonyDevicePropCode.CREATIVE_LOOK_SHARPNESS]?.currentValue?.toIntOrNull(),
            clClarity = props[SonyDevicePropCode.CREATIVE_LOOK_CLARITY]?.currentValue?.toIntOrNull()
        )

        private fun Long.toIntOrNull(): Int? =
            if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null
    }
}

/**
 * 创意风格调节量 → 编辑器色彩调整轴。
 *
 * 四个轴（对比度 / 高光 / 阴影 / 饱和度）在两端是**同名同义**，所以可以直接搬；
 * 「褪色」与「清晰度」在本工程的滑条里没有对应轴，故不参与转换——
 * 硬塞进「阴影」或「对比度」会造出一个用户没调过的偏移。
 *
 * 数值按 1:1 搬运：相机侧与编辑器侧都用 ±100 量级的对称刻度，但两者的
 * 视觉响应曲线并不等同，**所以这是一次等比映射而非精确等价**。
 */
fun CameraColorProfile.toCreativeLookAxes(): CreativeLookAxes? {
    if (!hasCreativeLookParams) return null
    return CreativeLookAxes(
        contrast = clContrast,
        highlights = clHighlights,
        shadows = clShadows,
        saturation = clSaturation
    )
}

/** 创意风格里能一对一搬进编辑器的四个轴；null 表示相机没报这一项。 */
data class CreativeLookAxes(
    val contrast: Int?,
    val highlights: Int?,
    val shadows: Int?,
    val saturation: Int?
)