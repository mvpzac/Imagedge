package com.imagedge.camera.data.model

import com.imagedge.camera.ptp.PhotoType
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 相册媒体项（相机端对象，未下载 / 已下载统一模型）
 *     version: 1.0
 * </pre>
 */

/** 下载状态 */
enum class DownloadState {
    NOT_DOWNLOADED,   // 未下载
    QUEUED,           // 排队中
    DOWNLOADING,      // 下载中
    DONE,             // 已完成
    FAILED            // 失败
}

/**
 * 相册媒体项
 * @param handle PTP 对象句柄（UPnP 通道为 0）
 * @param channelKey 下载标识（PTP: handle 字符串；UPnP: 资源 URL）
 * @param filename 文件名
 * @param sizeBytes 文件大小
 * @param photoType 媒体类型
 * @param captureDate 拍摄时间
 */
data class MediaItem(
    val handle: Long,
    val channelKey: String,
    val filename: String,
    val sizeBytes: Long,
    val photoType: PhotoType,
    val captureDate: Date?
) {
    /**
     * 缩略图/内容缓存 key：相机会复用 handle（同一 handle 指向不同照片），
     * 叠加大小与文件名，保证内容变化时 UI 缓存正确失效。
     */
    val thumbKey: String
        get() = "$channelKey|$sizeBytes|$filename"
}
