package com.imagedge.camera.injection

import android.content.Context
import androidx.room.Room
import com.imagedge.camera.data.transfer.DownloadDatabase
import com.imagedge.camera.data.transfer.DownloadHistoryDao
import com.imagedge.camera.data.transfer.DownloadTaskDao
import com.imagedge.camera.lut.CpuLutProcessor
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

    /** LUT 处理器（CPU 先行；Vulkan GPU 版落地后按设备能力智能选择） */
    @Provides
    @Singleton
    fun provideLutProcessor(): LutProcessor = CpuLutProcessor()

    /** 下载任务数据库（队列 + 传输记录持久化） */
    @Provides
    @Singleton
    fun provideDownloadDatabase(@ApplicationContext context: Context): DownloadDatabase =
        Room.databaseBuilder(context, DownloadDatabase::class.java, "download.db")
            .addMigrations(DownloadDatabase.MIGRATION_1_2)
            .build()

    /** 下载任务 DAO */
    @Provides
    @Singleton
    fun provideDownloadTaskDao(db: DownloadDatabase): DownloadTaskDao = db.downloadTaskDao()

    /** 传输记录 DAO */
    @Provides
    @Singleton
    fun provideDownloadHistoryDao(db: DownloadDatabase): DownloadHistoryDao = db.downloadHistoryDao()
}
