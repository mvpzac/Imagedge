package com.imagedge.camera.data.profile

import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机档案与命名预设的读写入口（T6）
 *     version: 1.0
 * </pre>
 */

/**
 * 档案与预设的存储门面。
 *
 * 两个写入点由 [com.imagedge.camera.data.remote.CameraRepository] 驱动：连接成功记一次
 * 档案与最近连接，能力探测成功时再补一份快照。放在仓库而不是界面层，是为了覆盖
 * 「主页连接卡片」「设置页手动 IP」两个入口以及以后新增的入口——只有一处知道连接真的建成了。
 *
 * 日志一律只打**存在性与标识**，不打凭据：本类的数据模型里根本没有凭据字段，
 * 但 profileKey 含型号/固件，属可打印范围（与 T0 的 `logCapabilitySnapshot` 口径一致）。
 */
@Singleton
class CameraProfileStore @Inject constructor(
    private val profileDao: CameraProfileDao,
    private val presetDao: ParameterPresetDao
) {

    companion object {
        /** 最近连接列表的展示上限。历史记录无上限地长下去没有意义，也会让列表页失去可扫读性 */
        private const val RECENT_LIMIT = 20
        private const val TAG = "profile"

        /** 预设名长度上限：列表里要读得完，也让导入文件里异常长的名字不会撑爆版面 */
        const val MAX_PRESET_NAME_CHARS = 40
    }

    // ── 档案 ─────────────────────────────────────────────────────────

    /**
     * 档案列表（含各自的能力快照）。档案数量级是个位数，父子同事务取回不构成性能问题。
     *
     * 走 [CameraProfileDao.observeWithSnapshots] 而不是「observe 父表 + 逐行查子表」：
     * 后者只跟踪 `camera_profile` 一张表，探测成功写入快照时列表不会被唤醒
     *（真机实测：快照在库里，界面却一直是「还没有成功探测过能力」）。
     */
    fun observeProfiles(): Flow<List<CameraProfile>> =
        profileDao.observeWithSnapshots().map { rows ->
            rows.map { it.profile.toDomain(it.snapshots) }
        }

    fun observeRecentConnections(): Flow<List<RecentConnection>> =
        profileDao.observeRecentConnections(RECENT_LIMIT).map { rows ->
            rows.map {
                RecentConnection(
                    profileKey = it.profileKey,
                    model = it.model,
                    firmware = it.firmware,
                    transport = it.transport.toTransportOrNull(),
                    mode = it.mode,
                    connectedAt = it.connectedAt
                )
            }
        }

    /**
     * 记录一次成功连接：建档（或更新首末次与次数）+ 追加最近连接。
     *
     * 身份未知（型号还没拿到）时直接跳过——写一条空档案只会让列表里多出一个没法辨认的条目。
     */
    suspend fun recordConnection(identity: CameraIdentity, at: Long = System.currentTimeMillis()) {
        if (!identity.isKnown || identity.transport == null) return
        withContext(Dispatchers.IO) {
            val key = identity.profileKey
            val existing = profileDao.findProfile(key)
            profileDao.upsertProfile(
                CameraProfileEntity(
                    profileKey = key,
                    model = identity.model,
                    firmware = identity.firmware,
                    firstSeenAt = existing?.firstSeenAt ?: at,
                    lastSeenAt = at,
                    connectCount = (existing?.connectCount ?: 0) + 1
                )
            )
            profileDao.insertRecentConnection(
                RecentConnectionEntity(
                    profileKey = key,
                    model = identity.model,
                    firmware = identity.firmware,
                    transport = identity.transport.name,
                    mode = identity.mode,
                    connectedAt = at
                )
            )
            AppLog.i(TAG, "记录连接：$key transport=${identity.transport} mode=${identity.mode}")
        }
    }

    /**
     * 落一份能力快照。
     *
     * 只接受**本轮真实探测成功**的快照（`descriptorRead` 为真且不陈旧）：把失败态存下来，
     * 下次进档案页就会看到一片「未知」被当成这台相机的能力事实。
     */
    suspend fun recordCapabilities(capabilities: CameraCapabilities) {
        val identity = capabilities.identity
        if (!capabilities.descriptorRead || capabilities.stale) return
        if (!identity.isKnown) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            profileDao.upsertSnapshot(
                CapabilitySnapshotEntity(
                    snapshotKey = identity.snapshotKey,
                    profileKey = identity.profileKey,
                    transport = identity.transport?.name ?: "",
                    mode = identity.mode,
                    snapshotJson = CapabilitySnapshotCodec.encode(capabilities, now),
                    probedAt = now
                )
            )
        }
    }

    /**
     * 删除一台相机的档案：能力快照与预设一并清除。
     *
     * 预设按档案键归属，档案没了就再没有「这台相机」可归类，也没有导出入口——
     * 留着只会变成既看不见也用不了的孤儿数据。
     */
    suspend fun deleteProfile(profileKey: String) = withContext(Dispatchers.IO) {
        profileDao.deleteSnapshots(profileKey)
        presetDao.clearItemsOfProfile(profileKey)
        presetDao.deletePresetsOfProfile(profileKey)
        profileDao.deleteProfile(profileKey)
        AppLog.i(TAG, "删除档案 $profileKey（含其能力快照与预设）")
    }

    suspend fun clearRecentConnections() = withContext(Dispatchers.IO) {
        profileDao.clearRecentConnections()
    }

    // ── 命名预设 ─────────────────────────────────────────────────────

    fun observePresets(): Flow<List<ParameterPreset>> =
        presetDao.observeWithItems().map { rows -> rows.map { it.toDomain() } }

    suspend fun presetsFor(profileKey: String): List<ParameterPreset> = withContext(Dispatchers.IO) {
        presetDao.presetsWithItemsFor(profileKey).map { it.toDomain() }
    }

    /** 把当前参数存为预设：同名同档案视为覆盖更新，避免手滑连点攒出一串「未命名 3」 */
    suspend fun savePreset(
        profileKey: String,
        name: String,
        values: Map<CameraCapability, Long>,
        existingId: String? = null
    ): ParameterPreset = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val trimmed = name.trim().take(MAX_PRESET_NAME_CHARS)
        val id = existingId ?: UUID.randomUUID().toString()
        val createdAt = existingId?.let { presetDao.findPreset(it)?.createdAt } ?: now
        presetDao.upsertPreset(
            ParameterPresetEntity(
                id = id,
                profileKey = profileKey,
                name = trimmed,
                createdAt = createdAt,
                updatedAt = now
            )
        )
        // 先清后写：只 upsert 会让上一次存在、这次被取消勾选的项残留在预设里
        presetDao.clearItems(id)
        presetDao.upsertItems(
            values.entries
                .filter { it.key in PresetParameters.order }
                .map { (capability, raw) ->
                    ParameterPresetItemEntity(presetId = id, capability = capability.name, rawValue = raw)
                }
        )
        ParameterPreset(id, profileKey, trimmed, values, createdAt, now)
    }

    suspend fun deletePreset(presetId: String) = withContext(Dispatchers.IO) {
        presetDao.clearItems(presetId)
        presetDao.deletePreset(presetId)
    }

    /** 导入用：整批写入，返回实际入库条数 */
    suspend fun importPresets(presets: List<ParameterPreset>): Int = withContext(Dispatchers.IO) {
        var written = 0
        presets.forEach { preset ->
            val id = preset.id.ifBlank { UUID.randomUUID().toString() }
            presetDao.upsertPreset(
                ParameterPresetEntity(
                    id = id,
                    profileKey = preset.profileKey,
                    name = preset.name.take(MAX_PRESET_NAME_CHARS),
                    createdAt = preset.createdAt,
                    updatedAt = preset.updatedAt
                )
            )
            presetDao.clearItems(id)
            presetDao.upsertItems(
                preset.values.entries
                    .filter { it.key in PresetParameters.order }
                    .map { (capability, raw) ->
                        ParameterPresetItemEntity(id, capability.name, raw)
                    }
            )
            written++
        }
        written
    }

    private fun CameraProfileEntity.toDomain(
        snapshots: List<CapabilitySnapshotEntity>
    ): CameraProfile = CameraProfile(
        profileKey = profileKey,
        model = model,
        firmware = firmware,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
        connectCount = connectCount,
        capabilities = snapshots.mapNotNull { entity ->
            CapabilitySnapshotCodec.decode(entity.snapshotJson)?.let {
                ProfileCapability(
                    transport = entity.transport.toTransportOrNull(),
                    mode = entity.mode,
                    probedAt = entity.probedAt,
                    capabilities = it
                )
            }
        }.sortedBy { it.probedAt }
    )

    private fun ParameterPresetEntity.toDomain(
        items: List<ParameterPresetItemEntity>
    ): ParameterPreset = ParameterPreset(
        id = id,
        profileKey = profileKey,
        name = name,
        values = items.mapNotNull { item ->
            CameraCapability.entries.firstOrNull { it.name == item.capability }?.let { it to item.rawValue }
        }.toMap(),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun PresetWithItems.toDomain(): ParameterPreset = preset.toDomain(items)

    private fun String.toTransportOrNull(): CameraTransport? =
        CameraTransport.entries.firstOrNull { it.name == this }
}
