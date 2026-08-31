package com.imagedge.camera.upnp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : UPnP 目录项（DIDL-Lite 解析结果）
 *     version: 1.1 —— 多分辨率 res 支持（下载 URL + 缩略图 URL 分离）
 * </pre>
 */

/**
 * UPnP 目录项（文件或文件夹）
 * @param url 下载用 URL（取 LRG/ORG 最优分辨率；文件夹为 null）
 * @param size 文件大小（LRG/ORG 的 size；可能为 null）
 */
data class UpnpItem(
    val id: String?,
    val parentId: String?,
    val title: String,
    val upnpClass: String,
    val contentType: String?,
    val url: String?,
    val size: Long?,
    val isDirectory: Boolean
)

/**
 * Browse 结果
 */
data class BrowseResult(
    val items: List<UpnpItem>,
    val numberReturned: Int,
    val totalMatches: Int
)
