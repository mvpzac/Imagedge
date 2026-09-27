package com.imagedge.camera.data.transfer

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : download.db 迁移验收。迁移写错的表现是老用户「下次启动即崩」，
 *              崩的还是用户唯一那份传输记录——这是全 App 最贵的一类回归。
 * </pre>
 *
 * 需要真机或模拟器：`./gradlew :app:connectedDebugAndroidTest`。
 *
 * schema 来自 `app/schemas/`（build.gradle.kts 用 assets.srcDir 指过去）。
 * **只校验到 v3**：v1/v2 的 schema 当年没导出，`runMigrationsAndValidate(…, 2, …)`
 * 会去 assets 里找 `2.json` 并抛 FileNotFound。所以下面只断言「一路迁到 v3 后的
 * 最终形态」——这正是用户真正经历的路径。
 *
 * 旧库的表结构由 [3.json] 反推（减去 MIGRATION_2_3 新增的那些列）写成。
 * 改实体时**不要**动这里，要动的是 MIGRATION_*。
 */
/** v2 的 download_task：v3 的 13 列减去 MIGRATION_2_3 加的 6 列 */
private const val TASK_V2_DDL = """
    CREATE TABLE IF NOT EXISTS `download_task` (
        `id` TEXT NOT NULL, `handle` INTEGER NOT NULL, `channelKey` TEXT NOT NULL,
        `filename` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `photoType` TEXT NOT NULL,
        `captureDate` INTEGER, PRIMARY KEY(`id`)
    )
"""

/** v2 的 download_history：v3 的 14 列减去 MIGRATION_2_3 加的 6 列 */
private const val HISTORY_V2_DDL = """
    CREATE TABLE IF NOT EXISTS `download_history` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `filename` TEXT NOT NULL,
        `savedPath` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL,
        `cameraModel` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `success` INTEGER NOT NULL
    )
"""

@RunWith(AndroidJUnit4::class)
class DownloadDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DownloadDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * 手造一个指定 user_version 的旧库。
     *
     * `user_version` 决定 Room 从哪个版本起走迁移路径——漏设的话
     * Room 会去找 (1,3) 这条并不存在的迁移而直接抛异常。
     */
    private fun legacyDb(name: String, version: Int, withHistory: Boolean): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(TASK_V2_DDL.trimIndent())
                if (withHistory) {
                    db.execSQL(HISTORY_V2_DDL.trimIndent())
                    db.execSQL(
                        "INSERT INTO `download_history` (`filename`,`savedPath`,`startTime`," +
                            "`endTime`,`cameraModel`,`sizeBytes`,`success`) " +
                            "VALUES ('IMG_0.jpg','/x',1,2,'ZV-E10',50,1)"
                    )
                }
                db.execSQL(
                    "INSERT INTO `download_task` (`id`,`handle`,`channelKey`,`filename`," +
                        "`sizeBytes`,`photoType`,`captureDate`) " +
                        "VALUES ('a',7,'ptp','IMG_1.jpg',100,'JPEG',1600000000000)"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val db = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration(instrumentation.targetContext, name, callback)
        ).writableDatabase
        check(db.version == version) { "旧库 user_version 应为 $version，实为 ${db.version}" }
        return db
    }

    @Test
    fun migratesFrom2To3WithoutLosingRows() {
        val db = legacyDb(DB_NAME, version = 2, withHistory = true)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 3, true, DownloadDatabase.MIGRATION_2_3
        )

        // v2 的表里只存在未完成的行，升级后落到 'QUEUED' 是如实的
        migrated.query("SELECT `status` FROM `download_task` WHERE `id` = 'a'").use {
            it.moveToFirst()
            assertEquals("QUEUED", it.getString(0))
        }
        // 老记录行的新列全为 NULL：界面因此不给「查看」「重试」，而不是猜一个
        migrated.query("SELECT `savedUri` FROM `download_history` WHERE `id` = 1").use {
            it.moveToFirst()
            assertNull("老记录的新列必须是 NULL", it.getString(0))
        }
        // 迁移前就有的数据必须原样还在
        migrated.query("SELECT `filename`, `sizeBytes` FROM `download_task` WHERE `id` = 'a'").use {
            it.moveToFirst()
            assertEquals("IMG_1.jpg", it.getString(0))
            assertEquals(100, it.getInt(1))
        }
        migrated.close()
    }

    @Test
    fun migratesFrom1AllTheWayTo3InOneLaunch() {
        // 真实升级路径：v1 老用户直接装到当前版本，MIGRATION_1_2 也必须被带上
        val db = legacyDb(DB_NAME, version = 1, withHistory = false)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME, 3, true,
            DownloadDatabase.MIGRATION_1_2,
            DownloadDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT `status`, `progress` FROM `download_task` WHERE `id` = 'a'").use {
            it.moveToFirst()
            assertEquals("QUEUED", it.getString(0))
            assertEquals(0, it.getInt(1))
        }
        // MIGRATION_1_2 建出来的记录表必须是空的，而不是没建
        migrated.query("SELECT COUNT(*) FROM `download_history`").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        migrated.close()
    }

    private companion object {
        const val DB_NAME = "migration-test-download.db"
    }
}
