package com.imagedge.camera.domain.media

import com.imagedge.camera.data.model.MediaItem

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 媒体身份（设计 §8.1）：导航传指纹，不传列表下标
 *     version: 1.0
 * </pre>
 */

/**
 * 一份媒体的不透明身份。
 *
 * 查看器原来按**列表下标**导航：相册在后台刷新一次（选片推送、内容集重建、切功能模式），
 * 同一个下标就指向另一张照片，用户点开的和看到的不是同一张。
 * 设计 §8.1 要求传不透明 ID；项目里现成就有合格的指纹——[MediaItem.thumbKey]
 * （channelKey + 大小 + 文件名：相机复用句柄指向别的照片时，大小或文件名会变，键随之失效）。
 * 所以这里不新造一套 ID 体系，只把「拿指纹换位置」这件事变成一处可测的规则。
 */
typealias MediaId = String

val MediaItem.mediaId: MediaId get() = thumbKey

/**
 * 把身份解析成列表里的位置。
 *
 * @return 下标；列表为空或找不到时 null——调用方必须显示「相机内容已更新，请重新选择」，
 *         **不许**回退到第 0 张或上一次的页码：那等于把一张没被点开的照片说成用户点的。
 */
fun resolveIndex(items: List<MediaItem>, mediaId: MediaId?): Int? {
    if (mediaId.isNullOrBlank()) return null
    val at = items.indexOfFirst { it.mediaId == mediaId }
    return if (at < 0) null else at
}
