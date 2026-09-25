package com.imagedge.camera.injection

import android.content.Context
import androidx.room.Room
import com.imagedge.camera.data.profile.CameraProfileDao
import com.imagedge.camera.data.profile.ParameterPresetDao
import com.imagedge.camera.data.profile.ProfileDatabase
import com.imagedge.camera.data.transfer.DownloadDatabase
import com.imagedge.camera.data.transfer.DownloadHistoryDao
import com.imagedge.camera.data.transfer.DownloadTaskDao
import com.imagedge.camera.lut.GpuLutProcessor
import com.imagedge.camera.lut.LutProcessor
import com.imagedge.camera.raw.EmbeddedJpegDecoder
import com.imagedge.camera.raw.RawDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : Hilt 依赖注入模块（纯 Kotlin 模块的依赖在此绑定，保持模块无 DI 依赖）
 *     version: 1.0
 * </pre>
 */

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** RAW 内嵌 JPEG 预览解码器（M2 换 libraw 实现时只改此处）；raw 模块保持纯净无 DI 依赖 */
    @Provides
    @Singleton
    fun provideRawDecoder(): RawDecoder = EmbeddedJpegDecoder()

    /**
     * LUT 处理器：GPU（GLES 3.0 + 3D 纹理，硬件三线性）优先，失败自动回退 CPU。
     *
     * 选择逻辑在 [GpuLutProcessor] 内部：小图（缩略图级别）与字节数组接口走 CPU，
     * 预览与全分辨率导出走 GPU；EGL/着色器/纹理任一环节不可用时记录原因并永久回退，
     * 保证功能不中断（日志 tag `CamRemote-lut`）。
     */
    @Provides
    @Singleton
    fun provideLutProcessor(): LutProcessor = GpuLutProcessor()

    /**
     * 下载任务数据库（队列 + 传输记录持久化）。
     *
     * 只登记迁移、绝不 `fallbackToDestructiveMigration()`：这张表存的正是「重启后还要能看见并
     * 重试的未完成传输」，清库重建恰好把本次要保住的东西抹掉，而且它连着系统相册里的文件。
     */
    @Provides
    @Singleton
    fun provideDownloadDatabase(@ApplicationContext context: Context): DownloadDatabase =
        Room.databaseBuilder(context, DownloadDatabase::class.java, "download.db")
            .addMigrations(DownloadDatabase.MIGRATION_1_2, DownloadDatabase.MIGRATION_2_3)
            .build()

    /** 下载任务 DAO */
    @Provides
    @Singleton
    fun provideDownloadTaskDao(db: DownloadDatabase): DownloadTaskDao = db.downloadTaskDao()

    /** 传输记录 DAO */
    @Provides
    @Singleton
    fun provideDownloadHistoryDao(db: DownloadDatabase): DownloadHistoryDao = db.downloadHistoryDao()

    /**
     * 相机档案数据库（T6）。
     *
     * 独立的 `profile.db` 而不是并进 `download.db`：后者已是 v2 且 `exportSchema = false`，
     * 加表就得手写一段无人能在真机上验证的迁移 DDL，写错的表现是老用户启动即崩。
     * 新库 version = 1 由 Room 首开建表，没有迁移路径。
     */
    @Provides
    @Singleton
    fun provideProfileDatabase(@ApplicationContext context: Context): ProfileDatabase =
        Room.databaseBuilder(context, ProfileDatabase::class.java, "profile.db")
            .build()

    /** 相机档案 DAO（档案 / 能力快照 / 最近连接） */
    @Provides
    @Singleton
    fun provideCameraProfileDao(db: ProfileDatabase): CameraProfileDao = db.cameraProfileDao()

    /** 命名参数预设 DAO */
    @Provides
    @Singleton
    fun provideParameterPresetDao(db: ProfileDatabase): ParameterPresetDao = db.parameterPresetDao()
}
