package com.imagedge.camera.data.remote

import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.upnp.UpnpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : UPnP 通道（"发送到智能手机"模式，JPEG 目录下载）
 *     version: 1.0
 * </pre>
 */

@Singleton
class UpnpChannel @Inject constructor() : CameraChannel {

    private var client: UpnpClient? = null

    override val channelType: ChannelType = ChannelType.UPNP
    override var deviceModel: String = "Sony Camera"
        private set

    override suspend fun connect(host: String) = withContext(Dispatchers.IO) {
        disconnect()
        val newClient = UpnpClient(host)
        // 拉取服务描述以确认可达（失败即抛异常，触发路由降级）
        newClient.getServiceDescription()
        // 标准 DMS 握手激活会话（索尼 DMS 需要先 GetProtocolInfo 才响应 Browse）
        newClient.activateSession()
        // 推送会话为可选前置（需相机端确认，盲目调用可能 507），失败不阻断浏览
        runCatching { newClient.startTransfer() }
            .onFailure { AppLog.w("upnp", "X_TransferStart 失败（非致命）：${it.message}") }
        client = newClient
        deviceModel = "Sony Camera"
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { client?.endTransfer() }
        client = null
    }

    override suspend fun listMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        val items = mutableListOf<MediaItem>()
        browseRecursive(c, "0", items)
        items
    }

    /** 递归浏览目录（根 "0" 开始，深入 container） */
    private fun browseRecursive(c: UpnpClient, objectId: String, out: MutableList<MediaItem>) {
        val result = c.browse(objectId)
        for (entry in result.items) {
            if (entry.isDirectory) {
                entry.id?.let { browseRecursive(c, it, out) }
            } else {
                entry.url?.let { url ->
                    out.add(
                        MediaItem(
                            handle = 0,
                            channelKey = url,
                            filename = inferFilename(entry.title, url, entry.contentType),
                            sizeBytes = entry.size ?: 0,
                            photoType = inferPhotoType(entry.contentType, url),
                            captureDate = null
                        )
                    )
                }
            }
        }
    }

    override suspend fun getThumbnail(item: MediaItem): ByteArray? = withContext(Dispatchers.IO) {
        // UPnP 降级通道不提供缩略图（相册以灰块占位）
        null
    }

    override suspend fun download(
        item: MediaItem,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        c.download(item.channelKey, output, onProgress)
    }

    /** 推断文件名（优先 URL 末尾文件名，否则 title + 扩展名） */
    private fun inferFilename(title: String, url: String, contentType: String?): String {
        val urlName = url.substringAfterLast("/", "").substringBefore("?")
        if (urlName.isNotEmpty() && urlName.contains(".")) return urlName
        val ext = when {
            contentType?.startsWith("video") == true -> ".mp4"
            contentType == "image/jpeg" -> ".jpg"
            else -> ".jpg"
        }
        return title.ifEmpty { "IMG" } + ext
    }

    /** 推断媒体类型 */
    private fun inferPhotoType(contentType: String?, url: String): PhotoType {
        val lower = url.lowercase()
        return when {
            contentType?.startsWith("video") == true -> PhotoType.VIDEO
            lower.endsWith(".mp4") || lower.endsWith(".mov") -> PhotoType.VIDEO
            else -> PhotoType.JPEG
        }
    }
}
