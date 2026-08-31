package com.imagedge.camera.data.transfer

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 下载任务持久化实体（Room）——只存「排队中/下载中」的任务，进程被杀后可恢复
 *     version: 1.0
 * </pre>
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,       // MediaItem.thumbKey
    val handle: Long,
    val channelKey: String,
    val filename: String,
    val sizeBytes: Long,
    val photoType: String,            // PhotoType.name
    val captureDate: Long?            // Date.time
) {
    fun toMediaItem(): MediaItem = MediaItem(
        handle = handle,
        channelKey = channelKey,
        filename = filename,
        sizeBytes = sizeBytes,
        photoType = runCatching { PhotoType.valueOf(photoType) }.getOrDefault(PhotoType.JPEG),
        captureDate = captureDate?.let { Date(it) }
    )

    companion object {
        fun from(item: MediaItem): DownloadTaskEntity = DownloadTaskEntity(
            id = item.thumbKey,
            handle = item.handle,
            channelKey = item.channelKey,
            filename = item.filename,
            sizeBytes = item.sizeBytes,
            photoType = item.photoType.name,
            captureDate = item.captureDate?.time
        )
    }
}
