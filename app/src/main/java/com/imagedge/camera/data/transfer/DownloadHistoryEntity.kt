package com.imagedge.camera.data.transfer

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-30
 *     desc   : 传输记录持久化实体（Room）——每次传输落一条账，是任务队列被清空后唯一的凭据
 *     version: 2.0
 * </pre>
 */

/**
 * 一次传输的流水账（成功与失败都记；用户主动取消不算一次传输，不记）。
 *
 * v2 只有 [savedPath] 这一句给人看的话，所以历史记录既打不开文件也重试不了。
 * v3 起把「当初下载的是哪个对象」与「落在了哪」一并留下：
 * [channelKey]/[handle]/[photoType]/[captureDate] → 能重建 [MediaItem]（重试的前提），
 * [savedUri] → 能打开文件（查看的前提）。
 *
 * 这四列**全部可空且无默认值**，因为升级前写入的行确实没有这些信息。
 * 界面据此收敛：取不到就不摆「重试」「查看」按钮，而不是拿 [savedPath] 反解一个假 Uri。
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 文件名 */
    val filename: String,
    /** 下载到的可读路径（MediaStore 相对路径 / SAF 文档 URI）——只给人看，不能反解成 Uri */
    val savedPath: String,
    /** 开始传输时间（epoch ms） */
    val startTime: Long,
    /** 结束传输时间（epoch ms） */
    val endTime: Long,
    /** 由哪台相机导出（相机型号） */
    val cameraModel: String,
    /** 文件大小（字节） */
    val sizeBytes: Long,
    /** 是否成功 */
    val success: Boolean,
    /** 下载标识（PTP: handle 字符串；UPnP: 资源 URL）。老数据为 null */
    val channelKey: String? = null,
    /** PTP 对象句柄（UPnP 通道为 0）。老数据为 null */
    val handle: Long? = null,
    /** PhotoType.name。老数据为 null */
    val photoType: String? = null,
    /** 拍摄时间（epoch ms）。老数据为 null */
    val captureDate: Long? = null,
    /** 成功落盘位置的 Uri 字符串。老数据为 null → 不提供「查看」 */
    val savedUri: String? = null,
    /** 失败原因（成功为 null） */
    val errorMessage: String? = null
) {
    /**
     * 与任务表对齐的去重 id。
     *
     * 不单独占一列：它本来就是 `channelKey|sizeBytes|filename` 的拼接
     * （见 [MediaItem.thumbKey]），存两份就会有不一致的风险。
     * 老数据缺 channelKey，算不出可信的 key → null。
     */
    val thumbKey: String?
        get() = channelKey?.let { "$it|$sizeBytes|$filename" }

    /**
     * 重建可重试的媒体项。信息不全（升级前的老记录）时返回 null，
     * 调用方**必须**因此隐藏重试，而不是拿 0 句柄去碰运气——
     * 用错句柄会让相机回 0x2009，把一条历史数据变成一次真实的传输失败。
     */
    fun toMediaItemOrNull(): MediaItem? {
        val key = channelKey ?: return null
        val objectHandle = handle ?: return null
        return MediaItem(
            handle = objectHandle,
            channelKey = key,
            filename = filename,
            sizeBytes = sizeBytes,
            photoType = photoType?.let { name ->
                runCatching { PhotoType.valueOf(name) }.getOrNull()
            } ?: PhotoType.JPEG,
            captureDate = captureDate?.let { Date(it) }
        )
    }
}
