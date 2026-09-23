package com.imagedge.camera.data.model

import java.util.Locale

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-28
 *     desc   : 相机当前拍摄参数（PTP DeviceProp 读取结果，用于遥控面板回显）
 *     version: 2.0 —— 只保留「当前值」；supported/settable 等能力信息迁往 CameraCapabilities
 * </pre>
 */

/**
 * 相机当前参数（null = 相机未返回该属性）。
 *
 * 本类只描述**值**，不描述**能力**。某项参数是否可写、有哪些合法档位，一律由
 * [CameraCapabilities] 依据相机 0x9209 描述符判定——把两者混在一个类里，
 * 正是「相机没上报就回退到硬编码档位表」这类 bug 的温床。
 *
 * 所有字段均为相机原始值（raw）；显示格式化见伴生对象的 `formatXxx`。
 */
data class CameraSettings(
    /** ISO（索尼 0xD21E：低 24 位 = ISO 值，0x00FFFFFF = Auto） */
    val isoRaw: Long? = null,
    /** 光圈（0x5007：f 值 ×100） */
    val fNumberRaw: Long? = null,
    /** 快门（索尼 0xD20D：高 16 分子 / 低 16 分母） */
    val shutterRaw: Long? = null,
    /** 照相模式（0x500E ExposureProgramMode） */
    val exposureProgramMode: Long? = null,
    /** 白平衡（0x5005） */
    val whiteBalance: Long? = null,
    /** 曝光补偿（0x5010，INT16 EV×1000，**已符号扩展**：-3.0EV → -3000） */
    val exposureBias: Long? = null
) {
    companion object {
        /**
         * 曝光补偿「未定义」哨兵。
         *
         * 相机以 INT16 0xFFFF 上报未定义，符号扩展后即 -1；EV 步进为 1/3（±333 的整数倍），
         * 正常档位取不到 -1，因此可以安全地把它当作哨兵。
         */
        const val EXPOSURE_BIAS_UNDEFINED = -1L

        /** 照相模式官方称呼（ExposureProgramMode 索尼值表，PlayMemories EnumExposureProgramMode） */
        fun formatProgramMode(raw: Long): String = when (raw) {
            1L -> "M 手动曝光"
            65538L -> "P 程序自动"
            131075L -> "A 光圈优先"
            196612L -> "S 快门优先"
            294912L -> "AUTO 自动"
            294913L -> "AUTO+ 增强自动"
            32776L -> "P_A"
            32777L -> "P_S"
            688264L -> "MOVIE 视频拍摄"
            688265L -> "STILL 静态影像"
            else -> "0x" + raw.toString(16)
        }

        /**
         * 照相模式遥控可选档位（官方 APP 同款筛选）。
         * 相机 0x9209 上报的是协议完整枚举表（25 项，含场景模式/拨盘位/程序偏移态），
         * 官方 APP 只渲染遥控有意义的曝光模式子集——本表即该子集（固定展示顺序），
         * 与相机上报值取交集：既滤掉不该出现的项，又保证只显示相机真实支持的档位。
         */
        val SELECTABLE_PROGRAM_MODES = listOf(
            294912L,    // AUTO 自动
            65538L,     // P 程序自动
            131075L,    // A 光圈优先
            196612L,    // S 快门优先
            1L,         // M 手动曝光
            294913L     // AUTO+ 增强自动
        )

        /** 从相机上报的枚举表中筛出遥控可选档位（按固定顺序） */
        fun selectableProgramModes(supported: List<Long>): List<Long> =
            SELECTABLE_PROGRAM_MODES.filter { it in supported }

        /** 白平衡官方称呼（0x5005 索尼值表，PlayMemories EnumWhiteBalanceMode） */
        fun formatWhiteBalance(raw: Long): String = when (raw) {
            2L -> "手动白平衡"
            3L -> "自动"
            4L -> "一键白平衡"
            5L -> "日光"
            6L -> "荧光灯"
            7L -> "白炽灯"
            8L -> "闪光灯"
            9L -> "暖白荧光灯"
            10L -> "冷白荧光灯"
            11L -> "日光白荧光灯"
            12L -> "日光荧光灯"
            13L -> "阴天"
            14L -> "阴影"
            15L -> "色温/滤光片"
            16L -> "自定义 1"
            17L -> "自定义 2"
            18L -> "自定义 3"
            20L -> "水下自动"
            else -> "0x" + raw.toString(16)
        }

        /**
         * 曝光补偿格式化（**已符号扩展**的 INT16 EV×1000）→ "+0.3"/"0.0"/"-1.0"
         * @see EXPOSURE_BIAS_UNDEFINED
         */
        fun formatExposureBias(signedRaw: Long): String {
            if (signedRaw == EXPOSURE_BIAS_UNDEFINED) return "--"
            if (signedRaw == 0L) return "0.0"
            val ev = signedRaw / 1000.0
            return (if (ev > 0) "+" else "") + String.format(Locale.US, "%.1f", ev)
        }

        /** 光圈原始值（×100）→ "1.8"/"2.0"… */
        fun formatFNumber(raw: Long): String {
            if (raw == 0L) return "--"
            return String.format(Locale.US, "%.1f", raw / 100.0)
        }

        /** ISO 原始值（低 24 位为值）→ "200"/"Auto" */
        fun formatIso(raw: Long): String {
            val isoVal = raw and 0x00FFFFFFL
            if (isoVal == 0x00FFFFFFL || isoVal == 0L) return "Auto"
            return isoVal.toString()
        }

        /**
         * 快门原始值（高 16 分子 / 低 16 分母）→ "1/125"/"1\""
         */
        fun formatShutter(raw: Long): String {
            if (raw == 0L) return "BULB"
            val numerator = (raw shr 16) and 0xFFFF
            val denominator = raw and 0xFFFF
            if (denominator == 0L) return "--"
            if (denominator == 10L) {
                val v = numerator / 10.0
                return if (v == v.toInt().toDouble()) "${v.toInt()}\"" else "${v}\""
            }
            if (numerator == 1L) return "1/$denominator"
            return "$numerator/$denominator"
        }

        // ── 把相机上报的原始值表转成下拉选项（标签 → 原始值）──
        // 这些函数只负责排序与格式化；「能不能给选项」由 CameraCapabilities.optionsFor 判定。

        /**
         * ISO 选项：Auto（低 24 位 = 0xFFFFFF）排最前，其余按数值升序，与相机菜单一致。
         *
         * 注意 Auto 的 raw 是 0x00FFFFFF 而不是 0——旧的「字符串 → raw」路径把 Auto 编码成 0，
         * 又被 `raw <= 0` 守卫挡掉，等于**根本没法把 ISO 设回 Auto**；改为直接使用相机上报的
         * 原始值后这个档位才真正可用。
         */
        fun isoOptions(supported: List<Long>): List<Pair<String, Long>> =
            supported
                .filter { (it and 0x00FFFFFFL) != 0L }
                .sortedWith(
                    compareBy<Long> { if ((it and 0x00FFFFFFL) == 0x00FFFFFFL) 0L else 1L }
                        .thenBy { it and 0x00FFFFFFL }
                )
                .map { formatIso(it) to it }

        /** 光圈选项：按 f 值升序（raw = f 值 ×100），过滤无效的 0 */
        fun fNumberOptions(supported: List<Long>): List<Pair<String, Long>> =
            supported
                .filter { it != 0L }
                .sorted()
                .map { formatFNumber(it) to it }

        /** 快门选项：按曝光时长升序（最快在前）；BULB 等无法解析的排到最后 */
        fun shutterOptions(supported: List<Long>): List<Pair<String, Long>> =
            supported
                .sortedWith(
                    compareBy<Long> { shutterSeconds(it) ?: Double.MAX_VALUE }
                )
                .map { formatShutter(it) to it }

        /** 白平衡选项：按相机上报的枚举值升序（与相机菜单顺序一致），过滤无效的 0 */
        fun whiteBalanceOptions(supported: List<Long>): List<Pair<String, Long>> =
            supported
                .filter { it != 0L }
                .sorted()
                .map { formatWhiteBalance(it) to it }

        /**
         * 快门时长（秒），用于排序；无法解析时返回 null（排到最后）。
         * "1/125" → 0.008；1 秒编码为 (10 shl 16)|10 → 10/10 = 1.0。
         */
        private fun shutterSeconds(raw: Long): Double? {
            if (raw == 0L) return null                 // BULB：没有固定时长
            val numerator = (raw shr 16) and 0xFFFF
            val denominator = raw and 0xFFFF
            if (denominator == 0L) return null
            return numerator.toDouble() / denominator.toDouble()
        }
    }
}
