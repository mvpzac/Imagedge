package com.imagedge.camera.data.transfer

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 传输记录持久化实体（Room）——每次下载完成/失败写入一条历史
 *     version: 1.0
 * </pre>
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 文件名 */
    val filename: String,
    /** 下载到的可读路径（MediaStore 相对路径 / SAF 文档 URI） */
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
    val success: Boolean
)
