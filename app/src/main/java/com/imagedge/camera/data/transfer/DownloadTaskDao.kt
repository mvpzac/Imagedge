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
 *     time   : 2026/08/28
 *     desc   : 下载任务 DAO + 传输记录 DAO + Room 数据库
 *     version: 2.0
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

    @Query("SELECT * FROM download_task")
    suspend fun getAll(): List<DownloadTaskEntity>
}

/** 传输记录 DAO：下载完成/失败后追加历史，供「传输记录」页展示 */
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
    version = 2,
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
    }
}
