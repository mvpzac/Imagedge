package com.imagedge.camera.data.profile

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机档案 / 能力快照 / 最近连接 / 命名预设的 Room 存储（T6）
 *     version: 1.0
 * </pre>
 */

/**
 * 相机档案：一台物理设备一行。
 *
 * 主键是 [com.imagedge.camera.data.model.CameraIdentity.profileKey]（型号 + 固件），
 * **不含连接方式与功能模式**——那是能力快照的归档维度，用它当键会把同一台相机
 * 裂成好几份档案。
 */
@Entity(tableName = "camera_profile")
data class CameraProfileEntity(
    @PrimaryKey val profileKey: String,
    val model: String,
    val firmware: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val connectCount: Int
)

/**
 * 一次能力探测的持久化结果，按 snapshotKey（型号 + 固件 + 传输方式 + 功能模式）归档。
 *
 * 读回来时永远是**陈旧**快照，只可用于展示，不可据此下发命令——
 * 见 [CapabilitySnapshotCodec.decode]。
 */
@Entity(tableName = "capability_snapshot", primaryKeys = ["snapshotKey"])
data class CapabilitySnapshotEntity(
    val snapshotKey: String,
    val profileKey: String,
    val transport: String,
    val mode: Int,
    val snapshotJson: String,
    val probedAt: Long
)

/**
 * 最近连接记录。
 *
 * 只记「哪台机器、什么时候、用什么方式连的」。**不记 SSID，也不记任何凭据**：
 * 索尼的 SSID 里嵌着用户自定的相机名，属于接入点标识；而没有密码的 SSID 并不能省掉扫码，
 * 于是记录它只剩隐私成本、没有功能收益。重连仍需重新扫码取凭据。
 */
@Entity(tableName = "recent_connection")
data class RecentConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileKey: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val mode: Int,
    val connectedAt: Long
)

/** 命名预设的头部：一组参数值，归属某个档案 */
@Entity(tableName = "parameter_preset")
data class ParameterPresetEntity(
    @PrimaryKey val id: String,
    val profileKey: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 预设里的一项参数（能力 → 相机原始值）。
 *
 * 只存**值**，不存「当时能不能写」：可写性与合法档位一律以应用时相机当次上报的描述符为准，
 * 否则就等于把某台相机某次的经验固化成许可。
 * 复合主键实体不能给字段设默认值，是 Room 的限制。
 */
@Entity(tableName = "parameter_preset_item", primaryKeys = ["presetId", "capability"])
data class ParameterPresetItemEntity(
    val presetId: String,
    val capability: String,
    val rawValue: Long
)

/**
 * 档案 + 能力快照（一次查询取回，父子同表事务）。
 *
 * 必须用 `@Relation` 而不是「先 observe 父表、再在 map 里逐行查子表」：
 * Room 的失效跟踪按**查询涉及的表**算，只查 `camera_profile` 的 Flow 永远不会被
 * `capability_snapshot` 的写入唤醒。真机实测过这个坑——探测成功后快照确实落库了，
 * 但档案列表不刷新，看起来像「快照没存下来」。
 */
data class ProfileWithSnapshots(
    @Embedded val profile: CameraProfileEntity,
    @Relation(parentColumn = "profileKey", entityColumn = "profileKey")
    val snapshots: List<CapabilitySnapshotEntity>
)

/** 预设 + 它的参数项，同上 */
data class PresetWithItems(
    @Embedded val preset: ParameterPresetEntity,
    @Relation(parentColumn = "id", entityColumn = "presetId")
    val items: List<ParameterPresetItemEntity>
)

/** 档案 + 能力快照 + 最近连接 */
@Dao
interface CameraProfileDao {

    @Query("SELECT * FROM camera_profile WHERE profileKey = :profileKey")
    suspend fun findProfile(profileKey: String): CameraProfileEntity?

    @Upsert
    suspend fun upsertProfile(profile: CameraProfileEntity)

    @Transaction
    @Query("SELECT * FROM camera_profile ORDER BY lastSeenAt DESC")
    fun observeWithSnapshots(): Flow<List<ProfileWithSnapshots>>

    @Upsert
    suspend fun upsertSnapshot(snapshot: CapabilitySnapshotEntity)

    @Insert
    suspend fun insertRecentConnection(record: RecentConnectionEntity)

    @Query("SELECT * FROM recent_connection ORDER BY connectedAt DESC LIMIT :limit")
    fun observeRecentConnections(limit: Int): Flow<List<RecentConnectionEntity>>

    @Query("DELETE FROM recent_connection")
    suspend fun clearRecentConnections()

    /** 删档案时连带清掉它的能力快照，避免留下无人引用的旧探测结果 */
    @Query("DELETE FROM capability_snapshot WHERE profileKey = :profileKey")
    suspend fun deleteSnapshots(profileKey: String)

    @Query("DELETE FROM camera_profile WHERE profileKey = :profileKey")
    suspend fun deleteProfile(profileKey: String)
}

/** 命名预设 */
@Dao
interface ParameterPresetDao {

    @Upsert
    suspend fun upsertPreset(preset: ParameterPresetEntity)

    /** 改既有预设时要拿回原始 createdAt（覆盖更新不能把创建时间冲掉） */
    @Query("SELECT * FROM parameter_preset WHERE id = :presetId")
    suspend fun findPreset(presetId: String): ParameterPresetEntity?

    @Upsert
    suspend fun upsertItems(items: List<ParameterPresetItemEntity>)

    @Query("DELETE FROM parameter_preset_item WHERE presetId = :presetId")
    suspend fun clearItems(presetId: String)

    @Query("DELETE FROM parameter_preset WHERE id = :presetId")
    suspend fun deletePreset(presetId: String)

    /**
     * 删档案时连带删掉它的预设：预设永远只对那台机器有意义，
     * 留着它们只会得到一串在界面上无处归类、也无处导出的孤儿数据。
     * 先删条目再删头部，避免留下没有父记录的参数项。
     */
    @Query(
        "DELETE FROM parameter_preset_item WHERE presetId IN " +
            "(SELECT id FROM parameter_preset WHERE profileKey = :profileKey)"
    )
    suspend fun clearItemsOfProfile(profileKey: String)

    @Query("DELETE FROM parameter_preset WHERE profileKey = :profileKey")
    suspend fun deletePresetsOfProfile(profileKey: String)

    @Transaction
    @Query("SELECT * FROM parameter_preset ORDER BY updatedAt DESC")
    fun observeWithItems(): Flow<List<PresetWithItems>>

    @Transaction
    @Query("SELECT * FROM parameter_preset WHERE profileKey = :profileKey ORDER BY createdAt")
    suspend fun presetsWithItemsFor(profileKey: String): List<PresetWithItems>
}

/**
 * 档案数据库。
 *
 * **独立的 `profile.db`，刻意不挂进 `download.db`**：后者已有 v2 且 `exportSchema = false`，
 * 加表要手写 DDL 去匹配 Room 生成的校验 schema，而本机没有真机可跑一次首开验证——
 * 迁移写错的表现是老用户**启动即崩**。新开一个 v1 库由 Room 首次打开时建表，
 * 没有迁移路径就没有迁移可出错，代价只是一次额外的 builder 与第二个 `@Provides`。
 */
@Database(
    entities = [
        CameraProfileEntity::class,
        CapabilitySnapshotEntity::class,
        RecentConnectionEntity::class,
        ParameterPresetEntity::class,
        ParameterPresetItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun cameraProfileDao(): CameraProfileDao
    abstract fun parameterPresetDao(): ParameterPresetDao
}
