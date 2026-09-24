package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.CapabilityDetail
import com.imagedge.camera.data.model.CapabilityEvidence
import com.imagedge.camera.data.model.CapabilityRange
import com.imagedge.camera.data.model.CapabilityState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 能力快照的持久化编解码（T6）
 *     version: 1.0
 * </pre>
 */

/**
 * 能力快照的存储形态。
 *
 * 为什么要单独一层 DTO 而不是给 [CameraCapabilities] 挂 @Serializable：
 * 存储格式是**对外契约**（要落进数据库列、要进导出文件），一旦定型就得长期兼容；
 * 领域模型则跟着协议认知走。绑在一起的话改一个字段就等于改文件格式。
 *
 * 枚举一律存**名字字符串**而不是序号：kotlinx 对 @Serializable enum 遇到未知值会直接抛
 * 序列化异常，那样一份旧快照里一个不认识的状态就能让整台相机的档案打不开。
 * 这里按 [CapabilityState.UNKNOWN] 等安全默认逐项降级。
 */
@Serializable
data class StoredCapabilitySnapshot(
    val model: String = "",
    val firmware: String = "",
    /** [CameraTransport] 的名字；null/未知 → 未连接 */
    val transport: String? = null,
    val mode: Int = CameraIdentity.MODE_UNKNOWN,
    val items: List<StoredCapabilityItem> = emptyList(),
    /** 探测完成时间（毫秒）。仅作展示与排序用，不代表数据仍然有效 */
    val probedAt: Long = 0L
)

@Serializable
data class StoredCapabilityItem(
    val capability: String = "",
    val state: String = "",
    val evidence: String = "",
    val propCode: Int = 0,
    val valueSize: Int = 0,
    val supportedValues: List<Long> = emptyList(),
    val range: StoredRange? = null,
    val note: String = ""
)

@Serializable
data class StoredRange(
    val min: Long = 0L,
    val max: Long = 0L,
    val step: Long = 0L
)

/**
 * 能力快照 ⇄ 存储文本。
 *
 * 解码是纯函数：格式规则（上限、未知值降级、损坏判定）不需要真机或 Robolectric 就能验证，
 * 这是本仓库一贯的做法（见 `MonitoringSettings.fromStored`）。
 */
object CapabilitySnapshotCodec {

    /** 单条 supportedValues 的长度上限：解析器上报本就限 200，这里对**外部数据**再设一道 */
    private const val MAX_STORED_VALUES = 256
    private const val MAX_STORED_ITEMS = 16

    /** 落库/读库的文本上限，防止把异常大的字符串塞进一次 JSON 解析 */
    const val MAX_JSON_BYTES = 64 * 1024

    private val json = Json {
        encodeDefaults = false
    }

    /**
     * 编码为存储文本。
     *
     * 只存**判定结果**，不存连接凭据——本类型压根没有承载密码/SSID 的字段，
     * 这一点由类型保证，而不是靠「记得别写进去」。
     */
    fun encode(capabilities: CameraCapabilities, probedAt: Long): String {
        val identity = capabilities.identity
        val document = StoredCapabilitySnapshot(
            model = identity.model,
            firmware = identity.firmware,
            transport = identity.transport?.name,
            mode = identity.mode,
            probedAt = probedAt,
            items = capabilities.items.entries.map { (capability, detail) ->
                StoredCapabilityItem(
                    capability = capability.name,
                    state = detail.state.name,
                    evidence = detail.evidence.name,
                    propCode = detail.propCode,
                    valueSize = detail.valueSize,
                    supportedValues = detail.supportedValues.take(MAX_STORED_VALUES),
                    range = detail.range?.let { StoredRange(it.min, it.max, it.step) },
                    note = detail.note.take(MAX_NOTE_CHARS)
                )
            }
        )
        return json.encodeToString(StoredCapabilitySnapshot.serializer(), document)
    }

    /**
     * 解码为「可展示但不可下发」的能力快照。
     *
     * 读回来的快照永远是 [CameraCapabilities.stale] = true。而对**通道声明类**条目
     * （如遥控拍摄，T0 允许它在描述符读取失败时仍可写，因为它来自当前活跃通道），
     * 这里直接不还原：「现在连着哪条通道」根本不是可持久化的事实，
     * 存一圈回来若照单恢复，就等于让一份历史档案凭空授权一次遥控拍摄。
     * 丢弃后这些项回到 UNKNOWN，重新连上再探测即可恢复。
     *
     * @return null 表示文本不可信（空、超长、JSON 损坏、条目数越界）
     */
    fun decode(text: String?): CameraCapabilities? {
        if (text.isNullOrBlank()) return null
        if (text.length > MAX_JSON_BYTES) return null
        val stored = runCatching {
            json.decodeFromString(StoredCapabilitySnapshot.serializer(), text)
        }.getOrNull() ?: return null
        if (stored.items.size > MAX_STORED_ITEMS) return null

        val transport = stored.transport?.let { name ->
            CameraTransport.entries.firstOrNull { it.name == name }
        }
        val identity = CameraIdentity(
            model = stored.model,
            firmware = stored.firmware,
            transport = transport,
            mode = stored.mode
        )
        val items = stored.items.mapNotNull { item ->
            val capability = CameraCapability.entries.firstOrNull { it.name == item.capability }
                ?: return@mapNotNull null
            val evidence = CapabilityEvidence.entries.firstOrNull { it.name == item.evidence }
                ?: CapabilityEvidence.NONE
            // 只恢复由 0x9209 描述符支撑的条目
            if (evidence != CapabilityEvidence.DEVICE_PROP_DESCRIPTOR) return@mapNotNull null
            capability to CapabilityDetail(
                state = CapabilityState.entries.firstOrNull { it.name == item.state }
                    ?: CapabilityState.UNKNOWN,
                evidence = evidence,
                propCode = item.propCode,
                valueSize = item.valueSize,
                supportedValues = item.supportedValues.take(MAX_STORED_VALUES),
                range = item.range?.let { CapabilityRange(it.min, it.max, it.step) },
                note = item.note
            )
        }.toMap()

        return CameraCapabilities(
            identity = identity,
            items = items,
            descriptorRead = false,
            stale = true
        )
    }

    /** 快照的探测时刻；无快照时为 null。仅用于档案展示「能力是何时实测的」 */
    fun probedAtOf(text: String?): Long? =
        text?.takeIf { it.isNotBlank() && it.length <= MAX_JSON_BYTES }?.let { raw ->
            runCatching {
                json.decodeFromString(StoredCapabilitySnapshot.serializer(), raw).probedAt
            }.getOrNull()
        }

    private const val MAX_NOTE_CHARS = 200
}
