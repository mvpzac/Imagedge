package com.imagedge.camera.data.model

import com.imagedge.camera.ptp.DevicePropParser
import com.imagedge.camera.ptp.DeviceProperty
import com.imagedge.camera.ptp.SonyDevicePropCode

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机能力模型（每项能力区分 unknown/unsupported/readOnly/writable，并保留判定依据）
 *     version: 1.0
 * </pre>
 */

/**
 * 单项能力的可用状态。
 *
 * [UNKNOWN] 与 [UNSUPPORTED] 必须分开：**请求超时不等于不支持**。把一次探测失败
 * 永久记成「不支持」，用户就再也看不到本该可用的控件，且无从判断是相机限制还是链路问题。
 */
enum class CapabilityState {
    /** 尚未探测，或探测失败（超时/断线）。下一次成功读取即恢复真实状态 */
    UNKNOWN,

    /** 相机明确上报不支持：0x9209 成功返回但缺该属性描述符，或传输通道结构性不具备 */
    UNSUPPORTED,

    /** 相机上报只读（GetSet≠0x01），或当前模式/镜头下被禁用（IsEnabled=0） */
    READ_ONLY,

    /** 相机上报可读写（GetSet=0x01 且 IsEnabled=1）；动作类能力表示可下发 */
    WRITABLE
}

/** 判定依据（保留证据来源，便于区分「相机实测上报」与「工程推断」） */
enum class CapabilityEvidence {
    /** 无证据：未探测或探测失败 */
    NONE,

    /** 相机 0x9209 描述符（GetSet / IsEnabled / FormFlag） */
    DEVICE_PROP_DESCRIPTOR,

    /** 传输通道结构性具备/不具备（如 UPnP 无 DeviceProp、无遥控拍摄） */
    TRANSPORT,

    /** 通道接口自我声明（[com.imagedge.camera.data.remote.CameraChannel.supportsCapture]） */
    CHANNEL_DECLARED
}

/** 可判定的相机能力项 */
enum class CameraCapability {
    ISO,
    F_NUMBER,
    SHUTTER_SPEED,
    EXPOSURE_PROGRAM_MODE,
    WHITE_BALANCE,
    EXPOSURE_BIAS,

    /** PTP 遥控拍摄（InitiateCapture）。BLE 快门是另一条独立通路，不由本项描述 */
    CAPTURE
}

/** 相机上报的取值范围（FormFlag=0x01），已按 dataType 符号扩展 */
data class CapabilityRange(
    val min: Long,
    val max: Long,
    val step: Long
)

/**
 * 单项能力的判定结果。
 *
 * @param propCode 判定所依据的 PTP 属性码；0 = 非 DeviceProp 能力（如 [CameraCapability.CAPTURE]）
 * @param valueSize 下发 0x9205 时必须使用的值宽度（字节），取自相机上报的 dataType；0 = 未知。
 *                  宽度写错会被相机拒绝或误解释，因此不能按属性码硬编码
 * @param supportedValues 相机上报的合法取值（FormFlag=0x02）；空 = 未上报
 * @param note 诊断说明（日志与兼容矩阵用，非界面文案）
 */
data class CapabilityDetail(
    val state: CapabilityState = CapabilityState.UNKNOWN,
    val evidence: CapabilityEvidence = CapabilityEvidence.NONE,
    val propCode: Int = 0,
    val valueSize: Int = 0,
    val supportedValues: List<Long> = emptyList(),
    val range: CapabilityRange? = null,
    val note: String = ""
) {
    val writable: Boolean get() = state == CapabilityState.WRITABLE
}

/**
 * 一次参数下发的决策结果。
 *
 * 把「该不该发」与「发什么」拆成显式两态：调用方只有拿到 [Send] 才可以触达通道，
 * [Reject] 必须静默丢弃（可记日志）。这样「界面禁用」与「不会发命令」由同一份判定保证。
 */
sealed interface PropertyWriteDecision {
    /** 可以下发：属性码、原始位模式与值宽度均来自相机描述符 */
    data class Send(
        val propCode: Int,
        val value: Long,
        val valueSize: Int
    ) : PropertyWriteDecision

    /** 不得下发：[state] 说明相机给出的事实，[reason] 是可直接落日志的诊断说明 */
    data class Reject(
        val capability: CameraCapability,
        val state: CapabilityState,
        val reason: String
    ) : PropertyWriteDecision
}

/**
 * 一次能力探测的快照。
 *
 * @param descriptorRead 0x9209 是否成功读取。false 时属性类能力只能是 [CapabilityState.UNKNOWN]
 * @param stale true = 当前身份下尚未成功探测（未连接 / 读取失败 / 刚切换功能模式或通道）。
 *              陈旧快照**只可展示，不可据此下发命令**——[canWrite] 已内建该约束。
 *              T6 若要把快照跨会话持久化，同样必须标记为 stale 直到重新探测通过
 */
data class CameraCapabilities(
    val identity: CameraIdentity = CameraIdentity.UNKNOWN,
    val items: Map<CameraCapability, CapabilityDetail> = emptyMap(),
    val descriptorRead: Boolean = false,
    val stale: Boolean = true
) {

    fun detail(capability: CameraCapability): CapabilityDetail =
        items[capability] ?: CapabilityDetail()

    fun stateOf(capability: CameraCapability): CapabilityState = detail(capability).state

    /**
     * 是否允许下发命令。
     *
     * [stale] 只描述 0x9209 描述符探测是否成功。由通道自我声明的能力
     * （[CapabilityEvidence.CHANNEL_DECLARED] / [CapabilityEvidence.TRANSPORT]）来自当前
     * 活跃通道而非描述符缓存，因此不受 stale 影响——否则描述符读取超时会让
     * 本来可用的遥控拍摄也被一并禁用。
     */
    fun canWrite(capability: CameraCapability): Boolean {
        val detail = detail(capability)
        if (detail.state != CapabilityState.WRITABLE) return false
        return !stale || detail.evidence != CapabilityEvidence.DEVICE_PROP_DESCRIPTOR
    }

    /**
     * 产出一次参数下发的决策。
     *
     * 属性码与值宽度都取自相机上报的描述符，不按属性码硬编码——硬编码宽度正是
     * 「ZV-E10 上报 UINT32 却按 UINT16 写」这类失败的来源。能力不足时返回
     * [PropertyWriteDecision.Reject]，调用方**不得**发出任何命令。
     */
    fun decideWrite(capability: CameraCapability, raw: Long): PropertyWriteDecision {
        val detail = detail(capability)
        if (!canWrite(capability)) {
            return PropertyWriteDecision.Reject(capability, detail.state, detail.note)
        }
        // 有符号值（如曝光补偿 -300）按相机上报的宽度回补成无符号原始位模式
        val mask = if (detail.valueSize >= 8) -1L else (1L shl (detail.valueSize * 8)) - 1L
        return PropertyWriteDecision.Send(detail.propCode, raw and mask, detail.valueSize)
    }

    /**
     * 生成 UI 下拉选项（标签 → 相机原始值）。
     *
     * 唯一原则：**选项只能来自相机上报的描述符**。不可写、或相机既没给枚举表也没给
     * 取值范围时返回空表，由上层禁用控件——不再回退到硬编码档位表。硬编码表会在
     * f/3.5-5.6 套头上提供 f/1.8，并把未经相机确认的值当成可写参数下发。
     */
    fun optionsFor(capability: CameraCapability): List<Pair<String, Long>> {
        if (!canWrite(capability)) return emptyList()
        val detail = detail(capability)
        detail.supportedValues.takeIf { it.isNotEmpty() }
            ?.let { values ->
                val options = enumerationOptions(capability, values)
                if (options.isNotEmpty()) return options
            }
        return detail.range?.let { rangeOptions(capability, it) } ?: emptyList()
    }

    private fun enumerationOptions(
        capability: CameraCapability,
        values: List<Long>
    ): List<Pair<String, Long>> = when (capability) {
        CameraCapability.ISO -> CameraSettings.isoOptions(values)
        CameraCapability.F_NUMBER -> CameraSettings.fNumberOptions(values)
        CameraCapability.SHUTTER_SPEED -> CameraSettings.shutterOptions(values)
        CameraCapability.EXPOSURE_PROGRAM_MODE ->
            CameraSettings.selectableProgramModes(values)
                .map { CameraSettings.formatProgramMode(it) to it }
        CameraCapability.WHITE_BALANCE -> CameraSettings.whiteBalanceOptions(values)
        CameraCapability.EXPOSURE_BIAS ->
            values.sortedDescending().map { CameraSettings.formatExposureBias(it) to it }
        CameraCapability.CAPTURE -> emptyList()
    }

    private fun rangeOptions(
        capability: CameraCapability,
        range: CapabilityRange
    ): List<Pair<String, Long>> {
        if (range.step <= 0L || range.max < range.min) return emptyList()
        // 相机上报的 min/max/step 属不可信输入：畸形范围不能变成无界列表
        val count = (range.max - range.min) / range.step + 1
        if (count < 1 || count > MAX_RANGE_OPTIONS) return emptyList()
        val values = (0 until count.toInt()).map { range.min + it * range.step }
        val labeled = values.map { labelOf(capability, it) to it }
        // 曝光补偿沿用「正向档位在前」的既有排序，其余按原始值升序
        return if (capability == CameraCapability.EXPOSURE_BIAS) labeled.asReversed() else labeled
    }

    companion object {
        /** 全部能力未知（未连接 / 探测失败） */
        val UNKNOWN = CameraCapabilities()

        /** 单个 Range 允许生成的选项上限（防御相机上报畸形范围） */
        private const val MAX_RANGE_OPTIONS = 64

        /** 相机原始值 → 界面标签（当前值与下拉选项共用同一套格式化，避免两处口径不一致） */
        fun labelOf(capability: CameraCapability, raw: Long): String = when (capability) {
            CameraCapability.ISO -> CameraSettings.formatIso(raw)
            CameraCapability.F_NUMBER -> CameraSettings.formatFNumber(raw)
            CameraCapability.SHUTTER_SPEED -> CameraSettings.formatShutter(raw)
            CameraCapability.EXPOSURE_PROGRAM_MODE -> CameraSettings.formatProgramMode(raw)
            CameraCapability.WHITE_BALANCE -> CameraSettings.formatWhiteBalance(raw)
            CameraCapability.EXPOSURE_BIAS -> CameraSettings.formatExposureBias(raw)
            CameraCapability.CAPTURE -> raw.toString()
        }

        /**
         * 各能力对应的 PTP 属性码。
         *
         * 只登记**已实测确认**的属性码。ISO/快门在标准 PTP 里另有 0x500F（ExposureIndex）
         * 与 0x500D（ExposureTime），但两者值编码与索尼私有码不同（ExposureTime 是毫秒），
         * 未经真机确认前不做候选回退——猜错编码比「未知并禁用」更糟。
         * 该回退属协议验证工作，见 docs/camera-capability-matrix.md 的未验证项。
         */
        private val PROP_CODES = mapOf(
            CameraCapability.ISO to SonyDevicePropCode.ISO,
            CameraCapability.F_NUMBER to SonyDevicePropCode.F_NUMBER,
            CameraCapability.SHUTTER_SPEED to SonyDevicePropCode.SHUTTER_SPEED,
            CameraCapability.EXPOSURE_PROGRAM_MODE to SonyDevicePropCode.EXPOSURE_PROGRAM_MODE,
            CameraCapability.WHITE_BALANCE to SonyDevicePropCode.WHITE_BALANCE,
            CameraCapability.EXPOSURE_BIAS to SonyDevicePropCode.EXPOSURE_BIAS
        )

        private val DEVICE_PROP_CAPABILITIES = PROP_CODES.keys

        /**
         * 0x9205/0x9207 写入路径支持的值宽度（见 `PtpIpClient.setDeviceProperty`）。
         * 相机上报其它宽度时不能猜着写——按只读处理并说明原因。
         */
        private val WRITABLE_VALUE_SIZES = setOf(2, 4)

        /**
         * 从 0x9209 bulk 数据中提取本模型关心的属性描述符。
         *
         * @return null 表示**读取或解析失败**（调用方必须落到 [CapabilityState.UNKNOWN]）。
         *         一个属性都没解析出来时也返回 null：此时无法区分「相机真的一个都不支持」
         *         与「bulk 数据错位」，宁可显示未知并允许重试，也不把猜测当成事实。
         */
        fun parseDescriptors(data: ByteArray): Map<Int, DeviceProperty>? =
            DevicePropParser.parse(data, PROP_CODES.values.toList()).ifEmpty { null }

        /**
         * 由一次能力探测构建快照。
         *
         * @param props 0x9209 解析结果。**null 表示读取失败（超时/断线/未连接）**，此时属性类
         *              能力一律为 [CapabilityState.UNKNOWN] 且 [stale] 为 true
         * @param supportsCapture 当前通道是否声明支持遥控拍摄
         */
        fun fromDescriptors(
            identity: CameraIdentity,
            props: Map<Int, DeviceProperty>?,
            supportsCapture: Boolean
        ): CameraCapabilities {
            val items = mutableMapOf<CameraCapability, CapabilityDetail>()
            items[CameraCapability.CAPTURE] = if (supportsCapture) {
                CapabilityDetail(
                    state = CapabilityState.WRITABLE,
                    evidence = CapabilityEvidence.CHANNEL_DECLARED,
                    note = "通道声明支持遥控拍摄"
                )
            } else {
                CapabilityDetail(
                    state = CapabilityState.UNSUPPORTED,
                    evidence = CapabilityEvidence.TRANSPORT,
                    note = "当前传输通道未声明遥控拍摄能力"
                )
            }

            if (identity.transport != CameraTransport.PTP_IP) {
                // 非 PTP 通道结构性不暴露 DeviceProp：这是确定事实，不是探测失败，
                // 因此可以明确标记为不支持（stale=false）
                val note = "传输通道 ${identity.transport ?: "无"} 不暴露 PTP DeviceProp"
                DEVICE_PROP_CAPABILITIES.forEach {
                    items[it] = CapabilityDetail(
                        state = CapabilityState.UNSUPPORTED,
                        evidence = CapabilityEvidence.TRANSPORT,
                        propCode = PROP_CODES.getValue(it),
                        note = note
                    )
                }
                return CameraCapabilities(identity, items, descriptorRead = false, stale = false)
            }

            if (props == null) {
                DEVICE_PROP_CAPABILITIES.forEach {
                    items[it] = CapabilityDetail(
                        state = CapabilityState.UNKNOWN,
                        evidence = CapabilityEvidence.NONE,
                        propCode = PROP_CODES.getValue(it),
                        note = "0x9209 读取失败（超时/断线/未连接），非「不支持」"
                    )
                }
                return CameraCapabilities(identity, items, descriptorRead = false, stale = true)
            }

            DEVICE_PROP_CAPABILITIES.forEach { capability ->
                val code = PROP_CODES.getValue(capability)
                val hexCode = "0x${code.toString(16).uppercase()}"
                val prop = props[code]
                if (prop == null) {
                    items[capability] = CapabilityDetail(
                        state = CapabilityState.UNSUPPORTED,
                        evidence = CapabilityEvidence.DEVICE_PROP_DESCRIPTOR,
                        propCode = code,
                        note = "0x9209 成功返回，但未包含 $hexCode 描述符"
                    )
                    return@forEach
                }
                // 相机说「可写」还不够：值宽度必须是写入路径支持的 2/4 字节，
                // 且当前未被模式/镜头禁用（IsEnabled=0）——两者都只能按只读展示
                val restriction = when {
                    !prop.settable -> "GetSet=0x${prop.getSet.toString(16).uppercase()}（相机上报只读）"
                    !prop.enabled -> "IsEnabled=0（当前拍摄模式/镜头下不可调整）"
                    prop.valueSize !in WRITABLE_VALUE_SIZES ->
                        "值宽度 ${prop.valueSize} 字节，写入路径仅支持 $WRITABLE_VALUE_SIZES"

                    else -> null
                }
                items[capability] = CapabilityDetail(
                    state = if (restriction == null) CapabilityState.WRITABLE
                    else CapabilityState.READ_ONLY,
                    evidence = CapabilityEvidence.DEVICE_PROP_DESCRIPTOR,
                    propCode = code,
                    valueSize = prop.valueSize,
                    supportedValues = prop.supported,
                    range = prop.toCapabilityRange(),
                    note = if (restriction == null) "$hexCode GetSet=0x01 且 IsEnabled=1"
                    else "$hexCode $restriction"
                )
            }
            return CameraCapabilities(identity, items, descriptorRead = true, stale = false)
        }

        private fun DeviceProperty.toCapabilityRange(): CapabilityRange? = range?.let {
            // 有符号类型（如 0x5010 INT16 EV×1000）必须扩展后才能正确排序与格式化
            CapabilityRange(min = signed(it.min), max = signed(it.max), step = signed(it.step))
        }
    }
}
