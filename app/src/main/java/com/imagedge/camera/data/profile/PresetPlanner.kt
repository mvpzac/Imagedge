package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.PropertyWriteDecision
import com.imagedge.camera.data.model.RejectReason

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 预设逐项「校验 → 应用 → 读回」规划器（T6）
 *     version: 1.0
 * </pre>
 */

/** 预设里单项参数的处理结果 */
enum class PresetItemStatus {
    /** 下发成功**且读回值与预设一致**。只有这一种算成功 */
    APPLIED,

    /** 预设归属另一台相机（型号 + 固件不同），整项跳过 */
    PROFILE_MISMATCH,

    /** 当前没有可信的能力快照（未连接 / 0x9209 读取失败 / 只有档案里的历史快照） */
    NOT_PROBED,

    /** 相机上报这项只读、未启用，或压根不支持 */
    CAPABILITY_NOT_WRITABLE,

    /** 相机上报可写，但这个值不在它当次上报的枚举表 / 取值范围里——典型场景是换了镜头 */
    VALUE_NOT_ALLOWED,

    /** 已下发但相机没回 OK */
    CAMERA_REFUSED,

    /** 相机回了 OK，但读回来的值不是预设要的值 */
    READBACK_MISMATCH
}

/** 规划结果：要么可以下发，要么带着明确理由跳过。两者都不代表已经成功 */
sealed interface PresetPlanItem {

    data class Write(
        val capability: CameraCapability,
        val raw: Long,
        val propCode: Int,
        val valueSize: Int
    ) : PresetPlanItem

    data class Skip(
        val capability: CameraCapability,
        val raw: Long,
        val status: PresetItemStatus,
        val reason: String
    ) : PresetPlanItem
}

/** 逐项最终结果，直接对应界面上的「部分失败」报告 */
data class PresetItemOutcome(
    val capability: CameraCapability,
    val expected: Long,
    val status: PresetItemStatus,
    val reason: String,
    val actual: Long?
)

/**
 * 把一份预设对着一份能力快照拆成「能发的」和「不能发的」。
 *
 * 全部是纯函数，因为这三条验收都要求在无真机条件下被证明：
 * 「同机型不同镜头档位能过滤」→ [PresetItemStatus.VALUE_NOT_ALLOWED]；
 * 「错误机型被拒绝」→ [PresetItemStatus.PROFILE_MISMATCH]；
 * 「失败项不会被标记为已成功」→ [outcomes] 里 APPLIED 必须读回一致。
 *
 * 下发本身由调用方（ViewModel）执行，因为它要碰相机；这里只负责**决定能不能发、
 * 发完之后怎么判定成功**。
 */
object PresetPlanner {

    /** 预设里被跳过 / 失败的项：调用方据此决定「部分失败」提示 */
    fun plan(preset: ParameterPreset, capabilities: CameraCapabilities): List<PresetPlanItem> {
        val entries = preset.parameters.map { it to preset.values.getValue(it) }
        if (entries.isEmpty()) return emptyList()

        // 1) 档案不匹配：整份不做。哪怕型号相同，固件不同属性表就可能不同
        if (capabilities.identity.profileKey != preset.profileKey) {
            val reason = "预设归属「${preset.profileKey.ifBlank { "未知档案" }}」，" +
                "当前相机是「${capabilities.identity.profileKey.ifBlank { "未连接" }}」"
            return entries.map { (capability, raw) ->
                PresetPlanItem.Skip(capability, raw, PresetItemStatus.PROFILE_MISMATCH, reason)
            }
        }

        // 2) 没有可信的能力快照就一个字节都不发。档案里存的历史快照永远是 stale，
        //    拿它去写等于用「上次连的时候相机说可以」代替「现在相机说可以」
        if (capabilities.stale || !capabilities.descriptorRead) {
            return entries.map { (capability, raw) ->
                PresetPlanItem.Skip(
                    capability, raw, PresetItemStatus.NOT_PROBED,
                    "本轮未成功读取 0x9209 描述符，请先连上相机并进入遥控页"
                )
            }
        }

        // 3) 逐项过同一道写闸门：decideWrite 已同时校验「可写性」与「值是否被当次上报」
        return entries.map { (capability, raw) ->
            when (val decision = capabilities.decideWrite(capability, raw)) {
                is PropertyWriteDecision.Send -> PresetPlanItem.Write(
                    capability = capability,
                    raw = raw,
                    propCode = decision.propCode,
                    valueSize = decision.valueSize
                )

                is PropertyWriteDecision.Reject -> PresetPlanItem.Skip(
                    capability = capability,
                    raw = raw,
                    status = if (decision.kind == RejectReason.VALUE_NOT_REPORTED) {
                        PresetItemStatus.VALUE_NOT_ALLOWED
                    } else {
                        PresetItemStatus.CAPABILITY_NOT_WRITABLE
                    },
                    reason = decision.reason
                )
            }
        }
    }

    /**
     * 结合「相机是否回了 OK」与「重读回来的值」给出逐项最终结果。
     *
     * **APPLIED 的条件是读回一致，而不是命令被接受**：相机回 OK 却没改成功
     * （模式互锁、写错宽度被忽略）是真实存在的情况，把它记成成功就是用户最糟的处境——
     * 界面上一个勾，相机上什么也没变。
     *
     * @param sendResults 每项下发是否得到相机 OK；缺失视为未成功
     * @param readBack 全部下发完成后重读一次的实际参数
     */
    fun outcomes(
        plan: List<PresetPlanItem>,
        sendResults: Map<CameraCapability, Boolean>,
        readBack: CameraSettings
    ): List<PresetItemOutcome> = plan.map { item ->
        when (item) {
            is PresetPlanItem.Skip -> PresetItemOutcome(
                capability = item.capability,
                expected = item.raw,
                status = item.status,
                reason = item.reason,
                actual = null
            )

            is PresetPlanItem.Write -> {
                val actual = actualOf(item.capability, readBack)
                when {
                    sendResults[item.capability] != true -> PresetItemOutcome(
                        capability = item.capability,
                        expected = item.raw,
                        status = PresetItemStatus.CAMERA_REFUSED,
                        reason = "相机未返回 OK",
                        actual = actual
                    )

                    actual == item.raw -> PresetItemOutcome(
                        item.capability, item.raw, PresetItemStatus.APPLIED, "读回一致", actual
                    )

                    else -> PresetItemOutcome(
                        capability = item.capability,
                        expected = item.raw,
                        status = PresetItemStatus.READBACK_MISMATCH,
                        reason = "相机未采用该值",
                        actual = actual
                    )
                }
            }
        }
    }

    /**
     * 预设的原始值与 [CameraSettings] 同源（都取相机描述符口径），可直接等值比较。
     *
     * internal 而非 private：「把当前参数存成预设」要用**同一张映射表**取值，
     * 各写一份的话，存进去和读回来比对的就不是同一个参数了。
     */
    internal fun actualOf(capability: CameraCapability, settings: CameraSettings): Long? = when (capability) {
        CameraCapability.ISO -> settings.isoRaw
        CameraCapability.F_NUMBER -> settings.fNumberRaw
        CameraCapability.SHUTTER_SPEED -> settings.shutterRaw
        CameraCapability.EXPOSURE_PROGRAM_MODE -> settings.exposureProgramMode
        CameraCapability.WHITE_BALANCE -> settings.whiteBalance
        CameraCapability.EXPOSURE_BIAS -> settings.exposureBias
        CameraCapability.CAPTURE -> null
    }
}
