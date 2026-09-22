package com.imagedge.camera.data.remote

import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.upnp.UpnpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _connectionState = MutableStateFlow(ChannelConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ChannelConnectionState> = _connectionState.asStateFlow()
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
        _connectionState.value = ChannelConnectionState.CONNECTED
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val closingClient = client
        client = null
        _connectionState.value = ChannelConnectionState.DISCONNECTED
        closingClient?.cancelActiveCall()
        runCatching { closingClient?.endTransfer() }
        Unit
    }

    override suspend fun listMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("未连接相机")
        val items = mutableListOf<MediaItem>()
        browseAll(c, items)
        items
    }

    /** Iterative, paginated browse with cycle and resource limits. */
    private fun browseAll(c: UpnpClient, out: MutableList<MediaItem>) {
        data class PendingContainer(val id: String, val depth: Int)

        val pending = ArrayDeque<PendingContainer>().apply { add(PendingContainer("0", 0)) }
        val visited = mutableSetOf<String>()
        val mediaKeys = mutableSetOf<String>()
        var pages = 0
        while (pending.isNotEmpty()) {
            val container = pending.removeFirst()
            if (!visited.add(container.id)) continue
            check(container.depth <= MAX_BROWSE_DEPTH) { "UPnP 目录深度超过上限" }
            check(visited.size <= MAX_CONTAINERS) { "UPnP 目录数量超过上限" }

            var startIndex = 0
            while (true) {
                check(++pages <= MAX_BROWSE_PAGES) { "UPnP 分页数量超过上限" }
                val result = c.browse(container.id, startIndex, BROWSE_PAGE_SIZE)
                for (entry in result.items) {
                    if (entry.isDirectory) {
                        entry.id?.takeIf { id -> id.isNotBlank() && id.length <= 512 && id !in visited }?.let {
                            pending.addLast(PendingContainer(it, container.depth + 1))
                        }
                    } else {
                        entry.url?.let { url ->
                            if (mediaKeys.add(url)) {
                                check(out.size < MAX_MEDIA_ITEMS) { "UPnP 媒体数量超过上限" }
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
                val returned = result.numberReturned.takeIf { it > 0 } ?: result.items.size
                if (returned <= 0) break
                val next = startIndex + returned
                check(next > startIndex) { "UPnP 分页索引未前进" }
                startIndex = next
                if (result.totalMatches > 0) {
                    if (startIndex >= result.totalMatches) break
                } else if (returned < BROWSE_PAGE_SIZE) {
                    // 有些 DMS 不上报 TotalMatches；只在这种情况下用“短页”判定结尾。
                    break
                }
            }
        }
    }

    private companion object {
        const val BROWSE_PAGE_SIZE = 100
        const val MAX_BROWSE_DEPTH = 32
        const val MAX_CONTAINERS = 10_000
        const val MAX_MEDIA_ITEMS = 100_000
        const val MAX_BROWSE_PAGES = 20_000
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
