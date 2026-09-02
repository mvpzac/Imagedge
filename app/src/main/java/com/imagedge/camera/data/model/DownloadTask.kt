package com.imagedge.camera.data.model

import android.graphics.Bitmap
import android.net.Uri

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 下载任务（下载队列项）
 *     version: 1.0
 * </pre>
 */

/**
 * 下载任务
 * @param id 任务 ID（= MediaItem.channelKey）
 * @param filename 文件名
 * @param sizeBytes 文件大小
 * @param state 下载状态
 * @param progress 进度 0-100
 * @param errorMessage 失败原因
 * @param thumbnail 缩略图（入队时从相册缩略图缓存取，可能为 null）
 * @param savedUri 下载成功后在系统相册中的 Uri（分享环节的入口）。
 *                 仅在内存中流转：任务完成后会从 Room 移除，无需持久化。
 */
data class DownloadTask(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val state: DownloadState,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val thumbnail: Bitmap? = null,
    val savedUri: Uri? = null
)

/**
 * 任务是否处于「进行中」（排队或下载中）。
 *
 * 去重、队列繁忙判断、前台服务保活都必须以此为据，而不是「任务列表里有没有这个 ID」。
 * 若把已完成/失败的记录也算作占用，用户删掉本地文件后将永远无法重新下载同一张照片。
 */
val DownloadState.isActive: Boolean
    get() = this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING
