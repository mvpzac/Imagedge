package com.imagedge.camera.data.transfer

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-28
 *     desc   : 下载任务持久化实体（Room）——排队/下载中/完成/失败**全部**留行，进程被杀后状态不丢
 *     version: 2.0
 * </pre>
 */

/**
 * 一条传输任务。
 *
 * v1/v2 只存「身份」（哪个相机的哪个对象），状态与进度活在内存里，进程一没就只剩
 * 「排队中」——完成拿不到相册 Uri、失败没有原因、队列清空后什么都没留下。
 * v3 起把结果一并落库：[state]/[progress]/[errorMessage]/[savedUri]/[batchId]/[failureViewed]。
 *
 * 带 `DEFAULT` 的四列必须用 [@ColumnInfo] 写死默认值，且与 MIGRATION_2_3 的 DDL 逐字一致：
 * Room 打开库时会把 sqlite_master 里的 dflt_value 与这里的声明**做字符串比较**，
 * 不一致就是启动即崩（IllegalStateException: Migration validation failed）。
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,       // MediaItem.thumbKey
    val handle: Long,
    val channelKey: String,
    val filename: String,
    val sizeBytes: Long,
    val photoType: String,            // PhotoType.name
    val captureDate: Long?,           // Date.time
    /** 队列状态（DownloadState.name）。老库升级来的行只可能是当时未完成的 → 默认 QUEUED */
    @ColumnInfo(defaultValue = "'QUEUED'") val state: String = DownloadState.QUEUED.name,
    /** 0-100。只在排队（0）与终态时落库：下载中每 150ms 写一次 Room 没有意义，见 DownloadManager */
    @ColumnInfo(defaultValue = "0") val progress: Int = 0,
    /** 失败原因（成功/进行中为 null） */
    val errorMessage: String? = null,
    /** 成功后系统相册/SAF 的 Uri 字符串。null = 老数据没记过，界面上就不提供「查看」 */
    val savedUri: String? = null,
    /** 批次号，一次提交算一批（DownloadManager 在入队时分配）；升级前的行为 0 */
    @ColumnInfo(defaultValue = "0") val batchId: Long = 0L,
    /** 失败是否已被用户看过（全局任务条只提示**未看过**的失败） */
    @ColumnInfo(defaultValue = "0") val failureViewed: Boolean = false
) {
    fun toMediaItem(): MediaItem = MediaItem(
        handle = handle,
        channelKey = channelKey,
        filename = filename,
        sizeBytes = sizeBytes,
        photoType = runCatching { PhotoType.valueOf(photoType) }.getOrDefault(PhotoType.JPEG),
        captureDate = captureDate?.let { Date(it) }
    )

    /**
     * 落库的状态名。取不出枚举时按「排队中」处理：
     * 一个读不懂的状态既不该被当成已完成（用户会以为传好了），也不该被删掉（那是丢数据）。
     */
    val persistedState: DownloadState
        get() = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.QUEUED)

    companion object {
        fun from(item: MediaItem, batchId: Long): DownloadTaskEntity = DownloadTaskEntity(
            id = item.thumbKey,
            handle = item.handle,
            channelKey = item.channelKey,
            filename = item.filename,
            sizeBytes = item.sizeBytes,
            photoType = item.photoType.name,
            captureDate = item.captureDate?.time,
            batchId = batchId
        )
    }
}
