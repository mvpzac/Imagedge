package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机档案 / 能力快照 / 命名预设 / 最近连接的领域模型（T6）
 *     version: 1.0
 * </pre>
 */

/**
 * 一份档案在某个 (传输方式, 功能模式) 下**最后一次实测到**的能力快照。
 *
 * [capabilities] 从持久化文本解出来时永远是 `stale = true`：它只能用于展示
 * 「上次连的时候这台机器能调什么」，不能用于下发命令。
 */
data class ProfileCapability(
    val transport: CameraTransport?,
    val mode: Int,
    val probedAt: Long,
    val capabilities: CameraCapabilities
)

/** 相机档案（一台物理设备） */
data class CameraProfile(
    val profileKey: String,
    val model: String,
    val firmware: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val connectCount: Int,
    /** 按 (传输方式, 功能模式) 分档的历史能力快照；空表示从未成功探测过 */
    val capabilities: List<ProfileCapability> = emptyList()
) {
    /** 固件未上报时如实标注，而不是留空让用户以为信息缺失是渲染问题 */
    val firmwareLabel: String get() = firmware.ifBlank { "未上报" }
}

/**
 * 命名参数预设：一组「能力 → 相机原始值」。
 *
 * 只承载**值**。不承载「这些值当时可不可写」——那是应用时刻由相机描述符决定的事实，
 * 存下来就等于把一次的经验固化成长期许可。
 *
 * 也不承载任何连接凭据：本类型没有可放密码/SSID 的字段，这一点由类型结构保证。
 */
data class ParameterPreset(
    val id: String,
    val profileKey: String,
    val name: String,
    val values: Map<CameraCapability, Long>,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** 预设里可被展示/应用的参数项（CAPTURE 是动作，不属于参数） */
    val parameters: List<CameraCapability>
        get() = PresetParameters.order.filter { values.containsKey(it) }
}

/** 预设允许承载的参数集合与固定顺序（CAPTURE 是动作，不是参数） */
object PresetParameters {
    val order = listOf(
        CameraCapability.EXPOSURE_PROGRAM_MODE,
        CameraCapability.ISO,
        CameraCapability.F_NUMBER,
        CameraCapability.SHUTTER_SPEED,
        CameraCapability.WHITE_BALANCE,
        CameraCapability.EXPOSURE_BIAS
    )
}

/** 一次最近连接记录（无凭据，见 [RecentConnectionEntity] 的说明） */
data class RecentConnection(
    val profileKey: String,
    val model: String,
    val firmware: String,
    val transport: CameraTransport?,
    val mode: Int,
    val connectedAt: Long
)
