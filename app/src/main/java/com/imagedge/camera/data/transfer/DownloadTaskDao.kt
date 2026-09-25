package com.imagedge.camera.data.transfer

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-28
 *     desc   : 下载任务 DAO + 传输记录 DAO + Room 数据库
 *     version: 3.0
 * </pre>
 */
@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity)

    /**
     * 批量插入（P1-12）。
     * 全选数百张时逐条 insert 会产生同样数量的协程与事务，改为单事务批量写入。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<DownloadTaskEntity>)

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM download_task WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    /** 按批次与入队顺序返回：任务行不再在完成时被删，队列里会同时留着好几批的账 */
    @Query("SELECT * FROM download_task ORDER BY batchId, rowid")
    suspend fun getAll(): List<DownloadTaskEntity>

    /**
     * 写入一次传输的终态。
     *
     * 只 UPDATE 不 REPLACE：REPLACE 要重写整行，而完成事件并不掌握批次号与文件名，
     * 拿内存里的旧值回填会把并发写入的列一起覆盖掉。
     */
    @Query(
        """
        UPDATE download_task
        SET state = :state, progress = :progress, errorMessage = :errorMessage,
            savedUri = :savedUri
        WHERE id = :id
        """
    )
    suspend fun updateOutcome(
        id: String,
        state: String,
        progress: Int,
        errorMessage: String?,
        savedUri: String?
    )

    /** 已分配过的最大批次号；空表返回 -1，下一批就是 0（与升级前老行的默认值不冲突） */
    @Query("SELECT COALESCE(MAX(batchId), -1) FROM download_task")
    suspend fun maxBatchId(): Long

    /** 把某一批里未看过的失败标记为已看（用户已经站在传输页上看着它们了） */
    @Query(
        """
        UPDATE download_task SET failureViewed = 1
        WHERE batchId = :batchId AND state = 'FAILED' AND failureViewed = 0
        """
    )
    suspend fun markBatchFailuresViewed(batchId: Long)
}

/** 传输记录 DAO：下载完成/失败后追加历史，供「记录」页展示、打开与重试 */
@Dao
interface DownloadHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadHistoryEntity)

    @Query("SELECT * FROM download_history ORDER BY endTime DESC")
    fun observeAll(): Flow<List<DownloadHistoryEntity>>

    @Query("DELETE FROM download_history")
    suspend fun clearAll()
}

@Database(
    entities = [DownloadTaskEntity::class, DownloadHistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao

    companion object {
        /** v1 → v2：新增传输记录表（download_history） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `savedPath` TEXT NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `cameraModel` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `success` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v2 → v3：任务行补状态/进度/原因/Uri/批次/是否已看，记录行补可重试与可打开的身份。
         *
         * 只用 `ADD COLUMN`，不重建表：升级前的行必须原样读得出来。
         * 三条不能松的规矩：
         * 1. 非空列一律带 DEFAULT，否则 SQLite 拒绝在已有行的表上添加非空列；
         * 2. DEFAULT 的字面量与实体里 [@androidx.room.ColumnInfo] 的 defaultValue 逐字一致
         *    （字符串默认值要带那一对外层单引号，Room 比较的是 sqlite_master 里的原文）；
         * 3. 老库升级来的任务行落到 `'QUEUED'` 是**如实**的——v2 的表里只存在未完成的行，
         *    重启后它们会被重新排队整文件重传；老记录行的新列全为 NULL，
         *    界面因此不给「查看」「重试」，而不是猜一个。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `download_task` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'QUEUED'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `download_task` ADD COLUMN `progress` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `errorMessage` TEXT")
                db.execSQL("ALTER TABLE `download_task` ADD COLUMN `savedUri` TEXT")
                db.execSQL(
                    """
                    ALTER TABLE `download_task` ADD COLUMN `batchId` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE `download_task` ADD COLUMN `failureViewed` INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `channelKey` TEXT")
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `handle` INTEGER")
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `photoType` TEXT")
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `captureDate` INTEGER")
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `savedUri` TEXT")
                db.execSQL("ALTER TABLE `download_history` ADD COLUMN `errorMessage` TEXT")
            }
        }
    }
}
