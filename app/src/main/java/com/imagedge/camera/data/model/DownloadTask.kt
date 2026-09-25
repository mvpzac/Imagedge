package com.imagedge.camera.data.model

import android.graphics.Bitmap
import android.net.Uri

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-27
 *     desc   : 下载任务（下载队列项）
 *     version: 2.0
 * </pre>
 */

/**
 * 下载任务。
 *
 * v2 起任务里**带着它自己的媒体项**（[mediaItem]），而不是只带一个 id：
 * 「重试」需要句柄与通道键才能把同一个对象再次交给相机，而传输页拿不到相册的
 * 当前列表（切页时那份列表可能已经换范围、换通道甚至清空）。以前 retry 要求调用方
 * 自己递一个 MediaItem 进来，结果是全仓没有一处能调用它——失败的任务只能回相册重选。
 *
 * [id]/[filename]/[sizeBytes] 现在是 [mediaItem] 的派生值：同一件事不再有两个真相。
 */
data class DownloadTask(
    val mediaItem: MediaItem,
    /** 批次号，一次提交算一批（见 TransferBatch） */
    val batchId: Long,
    val state: DownloadState,
    /** 进度 0-100；[mediaItem].sizeBytes <= 0 时算不出来，界面走不定长进度条 */
    val progress: Int = 0,
    val errorMessage: String? = null,
    val thumbnail: Bitmap? = null,
    /**
     * 下载成功后在系统相册中的 Uri（「查看」「编辑」「分享」的入口）。
     *
     * 内存里流转，同时随任务行落库——任务行 v3 起不再在完成时被删掉，
     * 所以这个 Uri 现在也活得过一次进程重启（旧版本一重启就没了）。
     */
    val savedUri: Uri? = null,
    /** 失败是否已被用户看过：全局任务条只提示没看过的失败（设计 §4.5） */
    val failureViewed: Boolean = false
) {
    /** 任务 ID = MediaItem.thumbKey（相机会复用句柄，所以键里带大小与文件名） */
    val id: String get() = mediaItem.thumbKey
    val filename: String get() = mediaItem.filename
    val sizeBytes: Long get() = mediaItem.sizeBytes
}

/**
 * 任务是否处于「进行中」（排队或下载中）。
 *
 * 去重、队列繁忙判断、前台服务保活都必须以此为据，而不是「任务列表里有没有这个 ID」。
 * 若把已完成/失败的记录也算作占用，用户删掉本地文件后将永远无法重新下载同一张照片。
 */
val DownloadState.isActive: Boolean
    get() = this == DownloadState.QUEUED || this == DownloadState.DOWNLOADING
