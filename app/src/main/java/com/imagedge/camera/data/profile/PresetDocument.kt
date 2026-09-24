package com.imagedge.camera.data.profile

import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 预设导出 / 导入文档格式（T6）
 *     version: 1.0
 * </pre>
 */

/** 导出格式版本。加字段就 +1；老版本读到不认识的结构一律明确拒绝，不做「尽力猜」 */
private const val FORMAT_VERSION = 1

@Serializable
data class PresetExportPayload(
    val format: Int = FORMAT_VERSION,
    /** 归属档案（型号 + 固件）：预设只对同一台物理设备有意义 */
    val profileKey: String = "",
    val model: String = "",
    val firmware: String = "",
    val exportedAt: Long = 0L,
    val presets: List<StoredPreset> = emptyList()
)

@Serializable
data class StoredPreset(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val items: List<StoredPresetItem> = emptyList()
)

@Serializable
data class StoredPresetItem(
    val capability: String = "",
    val rawValue: Long = 0L
)

/**
 * 文件外层：payload + 校验和。
 *
 * 校验和只覆盖 [PresetExportPayload] 那段文本本身，不含外层——这样校验和字段不参与
 * 自己所属的摘要计算，也不需要「先算再插字段」的脆弱技巧。
 */
@Serializable
data class PresetExportEnvelope(
    val payload: PresetExportPayload = PresetExportPayload(),
    val checksum: String = ""
)

/** 导入结果：可导入的预设 + 被拒原因。部分失败必须显式呈现，不能静默丢弃 */
data class PresetImportResult(
    val presets: List<ParameterPreset> = emptyList(),
    val rejected: List<String> = emptyList(),
    /** 文件整体不可信（格式版本不符、校验和不对、体积/条数越界、JSON 损坏） */
    val failure: String? = null
) {
    val isUsable: Boolean get() = failure == null && presets.isNotEmpty()
}

/**
 * 预设文档编解码。
 *
 * 全部是纯函数：一个从外部存储来的文件属于**不可信输入**，它的长度上限、条数上限、
 * 字段长度、校验和判定都能在 JVM 上验证，不需要真机。
 *
 * 刻意不提供任何承载凭据的字段：档案键只有型号 + 固件，预设条目只有「哪项能力 = 什么值」。
 * 连接密码从类型层面就无处可放，而不是靠实现时记得别写。
 */
object PresetDocument {

    /** 读入的文本上限：正常一份预设文件是 KB 级，超上限直接判为不可信 */
    const val MAX_FILE_CHARS = 256 * 1024

    /** 单文件预设条数上限 */
    const val MAX_PRESETS = 200

    /** 单个预设的参数项上限（就是参数总数，多出来即畸形） */
    private val MAX_ITEMS = PresetParameters.order.size

    private const val MAX_NAME_CHARS = CameraProfileStore.MAX_PRESET_NAME_CHARS
    private const val MAX_PROFILE_KEY_CHARS = 128

    private val json = Json {
        encodeDefaults = false
        // 未知键一律拒绝：文件来自外部存储，宽容解析会让被改过的结构悄悄混进来，
        // 也会让新版本写出的文件被旧版本半懂不懂地接受
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** 生成可写盘的导出文本 */
    fun encode(
        presetList: List<ParameterPreset>,
        identity: CameraIdentity,
        exportedAt: Long
    ): String {
        val payload = PresetExportPayload(
            format = FORMAT_VERSION,
            profileKey = identity.profileKey,
            model = identity.model,
            firmware = identity.firmware,
            exportedAt = exportedAt,
            presets = presetList.take(MAX_PRESETS).map { preset ->
                StoredPreset(
                    id = preset.id,
                    name = preset.name.take(MAX_NAME_CHARS),
                    createdAt = preset.createdAt,
                    updatedAt = preset.updatedAt,
                    items = preset.values
                        .filter { (capability, _) -> capability in PresetParameters.order }
                        .map { (capability, raw) -> StoredPresetItem(capability.name, raw) }
                )
            }
        )
        return json.encodeToString(
            PresetExportEnvelope.serializer(),
            PresetExportEnvelope(payload = payload, checksum = checksumOf(payload))
        )
    }

    /**
     * 解析导入文件。
     *
     * 任何一道防线不过就整份拒绝并给出原因——部分成功的前提是文件本身可信，
     * 而校验和不对的文件里，「看起来正常的那几条」同样不可信。
     *
     * @param connectedProfileKey 当前相机的档案键；null = 当前未连接，
     *   此时**不报**归属不匹配的提示——没有对照物就断言「不匹配」是猜，
     *   归属信息仍然随预设保存，等连上那台机器时由应用时刻的能力闸门把关。
     */
    fun decode(text: String?, connectedProfileKey: String?): PresetImportResult {
        if (text.isNullOrBlank()) return PresetImportResult(failure = "文件为空")
        if (text.length > MAX_FILE_CHARS) {
            return PresetImportResult(failure = "文件过大（${text.length} 字符，上限 $MAX_FILE_CHARS）")
        }
        val envelope = runCatching {
            json.decodeFromString(PresetExportEnvelope.serializer(), text)
        }.getOrNull()
            ?: return PresetImportResult(failure = "文件不是受支持的预设格式，或已损坏")

        val payload = envelope.payload
        if (payload.format != FORMAT_VERSION) {
            return PresetImportResult(
                failure = "不支持的格式版本 ${payload.format}（本应用读写版本 $FORMAT_VERSION）"
            )
        }
        if (payload.profileKey.length > MAX_PROFILE_KEY_CHARS) {
            return PresetImportResult(failure = "档案键异常（超过 $MAX_PROFILE_KEY_CHARS 字符）")
        }
        if (payload.presets.size > MAX_PRESETS) {
            return PresetImportResult(failure = "预设条数越界（${payload.presets.size} > $MAX_PRESETS）")
        }
        if (checksumOf(payload) != envelope.checksum) {
            return PresetImportResult(failure = "校验和不符：文件被修改过或传输不完整，已整份拒绝")
        }

        val rejected = mutableListOf<String>()
        val accepted = mutableListOf<ParameterPreset>()
        payload.presets.forEachIndexed { index, stored ->
            val label = stored.name.ifBlank { "第 ${index + 1} 条" }
            if (stored.name.length > MAX_NAME_CHARS) {
                rejected += "$label：名称过长（> $MAX_NAME_CHARS）"
                return@forEachIndexed
            }
            val items = stored.items.mapNotNull { item ->
                val capability = CameraCapability.entries.firstOrNull { it.name == item.capability }
                if (capability == null || capability !in PresetParameters.order) {
                    null
                } else {
                    capability to item.rawValue
                }
            }
            if (items.size != stored.items.size) {
                rejected += "$label：含未知或不支持的参数项"
                return@forEachIndexed
            }
            if (items.isEmpty() || items.size > MAX_ITEMS) {
                rejected += "$label：参数项数量异常（${items.size}）"
                return@forEachIndexed
            }
            accepted += ParameterPreset(
                id = stored.id,
                profileKey = payload.profileKey,
                name = stored.name,
                values = items.toMap(),
                createdAt = stored.createdAt,
                updatedAt = stored.updatedAt
            )
        }

        // 档案键与当前连接的相机不符：允许导入（预设按档案键归属，换回那台机器时才用得上），
        // 但必须明确告知，避免用户以为能在当前机器上用它们
        val notice = if (connectedProfileKey != null && payload.profileKey != connectedProfileKey) {
            "这些预设属于「${payload.profileKey.orUnknown()}」，与当前相机不同——已存档，但对当前相机不可应用"
        } else {
            null
        }
        return PresetImportResult(
            presets = accepted,
            rejected = rejected + listOfNotNull(notice)
        )
    }

    /** 对 payload 的规范序列化文本取 SHA-256（十六进制小写） */
    private fun checksumOf(payload: PresetExportPayload): String {
        val canonical = json.encodeToString(PresetExportPayload.serializer(), payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun String.orUnknown(): String = ifBlank { "未知档案" }
}
