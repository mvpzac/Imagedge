package com.imagedge.camera.data.lut

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : LUT 适用类型——决定滤镜在编辑页里归入哪一排。
 *              三类输入曲线互不相通，套错会让画面发灰或过冲，必须分开呈现。
 *     version: 1.0
 * </pre>
 */
enum class LutType {
    /** 适用于普通照片 / 已还原的画面（sRGB、Rec.709），直接套用即可 */
    CREATIVE,

    /** 适用于索尼 S-Log2 拍摄的 Log 灰片（先还原色彩再上风格） */
    SLOG2,

    /** 适用于索尼 S-Log3 拍摄的 Log 灰片（先还原色彩再上风格） */
    SLOG3;

    companion object {
        /**
         * 按文件名推断类型。内置 LUT 采用 SLog2_ / SLog3_ 前缀命名；
         * 用户导入的文件以此作为默认值，导入后可再声明修正。
         */
        fun fromFileName(name: String): LutType {
            val lower = name.lowercase()
            return when {
                lower.startsWith("slog3") -> SLOG3
                lower.startsWith("slog2") -> SLOG2
                else -> CREATIVE
            }
        }
    }
}
