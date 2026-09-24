package com.imagedge.camera.data.model

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 监看工作台偏好（T1）——纯显示层设置，不改相机文件、不下发任何相机命令
 *     version: 1.0
 * </pre>
 */

/**
 * 监看画面旋转角（顺时针）。
 *
 * 只做显示级旋转：相机里的文件始终是原始朝向。90°/270° 时帧的宽高互换，
 * 所以坐标模型必须按「有效尺寸」算 letterbox，不能拿原始宽高原样用。
 */
enum class ViewRotation(val degrees: Int) {
    Deg0(0),
    Deg90(90),
    Deg180(180),
    Deg270(270);

    /** 该角度下帧的宽高是否互换 */
    val swapsAxes: Boolean get() = this == Deg90 || this == Deg270

    /** 顺时针再转 90°（工具栏「旋转」的循环目标） */
    fun rotate90Clockwise(): ViewRotation = when (this) {
        Deg0 -> Deg90
        Deg90 -> Deg180
        Deg180 -> Deg270
        Deg270 -> Deg0
    }

    companion object {
        /** 存储值 → 枚举；未知/缺失回退默认，让降级安装与手改配置文件都不致崩 */
        fun fromStored(raw: String?, fallback: ViewRotation = Deg0): ViewRotation =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/** 构图网格（叠加在画面上，不参与任何像素处理） */
enum class GridMode {
    NONE,

    /** 三分线 */
    THIRD,

    /** 四分格 */
    QUARTER;

    companion object {
        fun fromStored(raw: String?, fallback: GridMode = NONE): GridMode =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/**
 * 比例标记（在画面上叠一个内接取景框，提示该比例的构图范围）。
 *
 * 只是**监看提示**：相机端成片比例由相机设置决定，本标记不裁剪、不改文件。
 * 直方图/斑马纹/伪色/波形与实时 LUT 属 T2，这里不涉及。
 */
enum class AspectMarker(val ratio: Float) {
    NONE(0f),
    R16_9(16f / 9f),
    R17_9(17f / 9f),
    R4_3(4f / 3f),
    R3_2(3f / 2f),
    R1_1(1f),
    R235(235f / 100f);

    /** ratio<=0 视为不绘制，避免相机/配置给出 0 比例时除零 */
    val isDrawn: Boolean get() = ratio > 0f && ratio.isFinite()

    companion object {
        fun fromStored(raw: String?, fallback: AspectMarker = NONE): AspectMarker =
            entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/**
 * 监看工作台偏好。
 *
 * 不含缩放与平移：那是**会话内**的手势状态，跨启动保留反而反直觉（用户期望每次进来是 1:1）。
 * 旋转/镜像/网格/标记属工作习惯，需要跨启动与跨横竖屏保留。
 */
data class MonitoringSettings(
    val rotation: ViewRotation = ViewRotation.Deg0,
    val mirrored: Boolean = false,
    val gridMode: GridMode = GridMode.NONE,
    val aspectMarker: AspectMarker = AspectMarker.NONE
) {

    /**
     * 从存储键值还原。
     *
     * 以纯 Map 为入参而不是直接读 SharedPreferences：解码规则要能在 JVM 单测里跑，
     * 「未知值必须回退默认」这条没有真机也能验证。
     */
    companion object {
        val DEFAULT = MonitoringSettings()

        const val KEY_ROTATION = "monitoring_rotation"
        const val KEY_MIRRORED = "monitoring_mirrored"
        const val KEY_GRID = "monitoring_grid"
        const val KEY_ASPECT = "monitoring_aspect"

        fun fromStored(stored: Map<String, String?>, mirrored: Boolean = false): MonitoringSettings =
            MonitoringSettings(
                rotation = ViewRotation.fromStored(stored[KEY_ROTATION]),
                mirrored = mirrored,
                gridMode = GridMode.fromStored(stored[KEY_GRID]),
                aspectMarker = AspectMarker.fromStored(stored[KEY_ASPECT])
            )
    }
}
