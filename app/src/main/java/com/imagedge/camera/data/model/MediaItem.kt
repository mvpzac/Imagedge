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
     *
     * **仅限会话内使用**：句柄每次重新枚举都会重排，同一张照片重连后
     * thumbKey 就变了。跨会话的去重要用 [contentKey]。
     */
    val thumbKey: String
        get() = "$channelKey|$sizeBytes|$filename"

    /**
     * 与句柄无关的内容指纹，供**跨会话**去重（自动保存账本）使用。
     *
     * 拿 thumbKey 去重等于：每次重连相机，账本都认不出拍过的照片，
     * 于是整张卡被自动再拉一遍。文件名 + 大小 + 拍摄时间三者在同一台相机上
     * 对同一张照片是稳定的；拍摄时间缺失（被编辑/压缩过的图）时仍靠前两者区分，
     * 不会退化成「所有无名日期的图共用一个键」。
     */
    val contentKey: String
        get() = "$filename|$sizeBytes|${captureDate?.time ?: 0L}"
}
