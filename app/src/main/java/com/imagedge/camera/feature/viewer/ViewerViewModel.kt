package com.imagedge.camera.feature.viewer

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.domain.media.mediaId
import com.imagedge.camera.domain.media.resolveIndex
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.data.model.MediaSessionCache
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.transfer.DownloadManager
import com.imagedge.camera.raw.RawDecoder
import com.imagedge.camera.ui.feedback.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 大图查看器——全屏翻页预览；JPEG 直接下载解码，
 *              RAW 经内嵌 JPEG 预览秒开（raw 模块 M1），带 LRU 缓存。
 *     version: 1.0
 * </pre>
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CameraRepository,
    private val downloadManager: DownloadManager,
    private val sessionCache: MediaSessionCache,
    private val rawDecoder: RawDecoder,
    private val snackbarController: SnackbarController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** 相册当前列表（含顺序） */
    val items: List<MediaItem> = sessionCache.items

    /**
     * 打开时点名的那张（设计 §8.1：导航传指纹，不传下标）。
     *
     * 列表在后台刷新过一次、或进程重建后会话失效时，指纹找不到对应项——
     * 这时 [startIndex] 为 null，界面显示「相机内容已更新，请重新选择」，
     * 而不是回退到第 0 张把没被点开的照片说成用户点的。
     */
    val mediaId: String? = savedStateHandle.get<String>("mediaId")

    val startIndex: Int? = resolveIndex(items, mediaId)

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading.asStateFlow()

    /** 预览缓存（thumbKey → 位图；LRU 上限 6 张，防多张 25MB RAW 撑爆内存） */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > 6
    }
    private val _previews = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val previews: StateFlow<Map<String, ImageBitmap>> = _previews.asStateFlow()

    /** 网格缩略图垫底（相册已加载的小图，点开瞬间可见） */
    fun gridPreview(item: MediaItem): ImageBitmap? =
        sessionCache.gridThumbnails[item.thumbKey]?.asImageBitmap()

    /**
     * 加载单张预览（JPEG 全量 / RAW 内嵌 JPEG）。
     * 解码按屏幕分辨率采样（inSampleSize）——24MP 全尺寸解码 1~2s，采样后大幅缩短。
     *
     * 视频**不走**本路径：查看器翻页时会预加载 ±1 页，若不拦住视频，一个几百 MB 的
     * MP4 会被整文件读进 ByteArrayOutputStream 直接 OOM 崩溃。视频预览走 [loadVideo]
     * （流式落盘 + ExoPlayer 播放），不入内存。
     */
    fun loadPreview(item: MediaItem) {
        if (item.photoType == PhotoType.VIDEO) return
        synchronized(cache) { if (cache.containsKey(item.thumbKey)) return }
        if (item.channelKey in _loading.value) return
        _loading.update { it + item.channelKey }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = repository.downloadToMemory(item)
                val jpeg = if (item.photoType == PhotoType.RAW) {
                    rawDecoder.decodeEmbeddedJpeg(bytes) ?: bytes
                } else {
                    bytes
                }
                val bitmap: Bitmap? = decodeSampled(jpeg)
                if (bitmap != null) {
                    synchronized(cache) { cache[item.thumbKey] = bitmap.asImageBitmap() }
                    _previews.value = synchronized(cache) { cache.toMap() }
                } else {
                    AppLog.w("viewer", "预览解码失败：${item.filename}")
                }
            } catch (e: Exception) {
                AppLog.w("viewer", "预览加载失败 ${item.filename}：${e.message}")
            } finally {
                _loading.update { it - item.channelKey }
            }
        }
    }

    fun cachedPreview(item: MediaItem): ImageBitmap? =
        synchronized(cache) { cache[item.thumbKey] }

    /** 加入下载队列（落盘 DCIM/Imagedge） */
    /** 入队下载，并给一次轻提示（规范：每个动作都要有回音） */
    fun enqueueDownload(item: MediaItem) {
        downloadManager.enqueue(item)
        snackbarController.show("已加入下载队列：${item.filename}")
    }

    /** 视频下载状态（thumbKey → 状态），供查看器 ExoPlayer 播放 */
    private val _videoStates = MutableStateFlow<Map<String, VideoDownloadState>>(emptyMap())
    val videoStates: StateFlow<Map<String, VideoDownloadState>> = _videoStates.asStateFlow()

    /**
     * 下载视频到缓存目录（供查看器播放）。缓存文件复用：文件名 + 大小匹配则跳过下载。
     */
    fun loadVideo(item: MediaItem) {
        val key = item.thumbKey
        val current = _videoStates.value[key]
        if (current is VideoDownloadState.Downloading || current is VideoDownloadState.Ready) return
        _videoStates.value = _videoStates.value + (key to VideoDownloadState.Downloading(0))
        viewModelScope.launch(Dispatchers.IO) {
            val directory = File(context.cacheDir, VIDEO_CACHE_DIRECTORY).apply { mkdirs() }
            val file = File(directory, videoCacheName(item))
            val partial = File(directory, file.name + ".part")
            try {
                if (file.exists() && item.sizeBytes > 0 && file.length() == item.sizeBytes) {
                    file.setLastModified(System.currentTimeMillis())
                    _videoStates.value = _videoStates.value + (key to VideoDownloadState.Ready(file))
                    return@launch
                }
                partial.delete()
                repository.downloadToFile(item, partial) { loaded, total ->
                    val progress = if (total > 0) (loaded * 100 / total).toInt() else 0
                    _videoStates.value = _videoStates.value + (key to VideoDownloadState.Downloading(progress))
                }
                if (item.sizeBytes > 0 && partial.length() != item.sizeBytes) {
                    throw java.io.IOException("视频缓存长度不完整")
                }
                moveAtomically(partial, file)
                pruneVideoCache(directory, keep = file)
                _videoStates.value = _videoStates.value + (key to VideoDownloadState.Ready(file))
            } catch (e: Exception) {
                partial.delete()
                AppLog.w("viewer", "视频下载失败 ${item.filename}：${e.message}")
                _videoStates.value = _videoStates.value + (key to VideoDownloadState.Failed(e.message ?: "下载失败"))
            }
        }
    }

    // ── 「需先保存到手机」的准备流程（设计 §4.4）──────────────────────────

    /** 编辑/分享要的是**原图**；查看器手里的只是采样预览，绝不能拿它当原图导出 */
    enum class ViewerIntent { Edit, Share }

    data class PrepareState(
        val intent: ViewerIntent,
        val mediaKey: String,
        val progress: Int? = null,
        val error: String? = null,
        /** 原图已落盘：界面据此继续原意图（打开编辑器 / 分享面板） */
        val readyUri: Uri? = null
    )

    private val _prepare = MutableStateFlow<PrepareState?>(null)
    val prepare: StateFlow<PrepareState?> = _prepare.asStateFlow()

    init {
        // 准备流程的进度与结局都来自队列本身：自己另开一条下载就是第二份真相
        viewModelScope.launch {
            downloadManager.tasks.collect { tasks ->
                val current = _prepare.value ?: return@collect
                if (current.readyUri != null) return@collect
                val task = tasks.firstOrNull { it.id == current.mediaKey }
                _prepare.value = when {
                    task == null -> current.copy(
                        progress = null,
                        error = "这个任务不在队列里了（可能被清空），请重新发起"
                    )
                    task.state == com.imagedge.camera.data.model.DownloadState.DONE &&
                        task.savedUri != null -> current.copy(readyUri = task.savedUri)
                    task.state == com.imagedge.camera.data.model.DownloadState.FAILED ->
                        current.copy(progress = null, error = task.errorMessage ?: "下载失败")
                    else -> current.copy(progress = task.progress, error = null)
                }
            }
        }
    }

    /**
     * 编辑/分享前先把原图落到相册。
     *
     * 已经存过就直接继续，不再传一遍；没存过才入队。入队失败（落库没受理）
     * 立刻把原因写进准备态，而不是让用户对着进度条等一个不会来的结果。
     */
    fun requestLocalCopy(item: MediaItem, intent: ViewerIntent) {
        val already = savedUriOf(item)
        if (already != null) {
            _prepare.value = PrepareState(intent, item.mediaId, readyUri = already)
            return
        }
        _prepare.value = PrepareState(intent, item.mediaId, progress = 0)
        viewModelScope.launch {
            if (!downloadManager.enqueueAllAwait(listOf(item))) {
                _prepare.value = PrepareState(
                    intent, item.mediaId,
                    error = "加入下载队列失败（可能是存储不可用），原图没有开始传"
                )
            }
        }
    }

    /** 取消只结束准备：pager 位置、已加载的预览都不动（设计 §4.4） */
    fun cancelPrepare() {
        _prepare.value = null
    }

    /** 界面消费「原图已就绪」事件（一次性，避免重组重复打开编辑器） */
    fun consumeReadyUri(): Pair<Uri, ViewerIntent>? {
        val current = _prepare.value ?: return null
        val uri = current.readyUri ?: return null
        _prepare.value = null
        return uri to current.intent
    }

    /** 已落盘项的 thumbKey → 相册 Uri（「已保存到手机」标记与编辑/分享能否直接走） */
    fun savedUrisFlow(): StateFlow<Map<String, Uri>> = downloadManager.tasks
        .map { tasks ->
            tasks.asSequence()
                .filter { it.savedUri != null }
                .associate { it.id to it.savedUri!! }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 这张是否已经落在相册里（决定「已保存到手机」标记与编辑/分享能不能直接走） */
    fun savedUriOf(item: MediaItem): Uri? =
        downloadManager.tasks.value.firstOrNull { it.id == item.mediaId }?.savedUri

    override fun onCleared() {
        File(context.cacheDir, VIDEO_CACHE_DIRECTORY).listFiles()
            ?.filter { it.name.endsWith(".part") }
            ?.forEach { runCatching { it.delete() } }
        super.onCleared()
    }

    private fun videoCacheName(item: MediaItem): String {
        val identity = "${item.channelKey}\u0000${item.sizeBytes}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity)
        val hash = digest.joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
        val extension = item.filename.substringAfterLast('.', "mp4")
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifEmpty { "mp4" }
        return "$hash.$extension"
    }

    private fun moveAtomically(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun pruneVideoCache(directory: File, keep: File) {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (candidate in files.asReversed()) {
            if (total <= MAX_VIDEO_CACHE_BYTES) break
            if (candidate == keep) continue
            val length = candidate.length()
            if (candidate.delete()) total -= length
        }
    }

    /** 采样解码：最长边 ≤ 2560px（查看器显示分辨率足够，解码/内存开销大幅降低） */
    private fun decodeSampled(jpeg: ByteArray, maxDim: Int = 2560): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim / 2 ||
            bounds.outHeight / (sample * 2) >= maxDim / 2
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private companion object {
        const val VIDEO_CACHE_DIRECTORY = "video_previews"
        const val MAX_VIDEO_CACHE_BYTES = 1024L * 1024 * 1024
    }
}

/** 视频下载状态（查看器播放用） */
sealed class VideoDownloadState {
    data object Idle : VideoDownloadState()
    data class Downloading(val progress: Int) : VideoDownloadState()
    data class Ready(val file: File) : VideoDownloadState()
    data class Failed(val message: String) : VideoDownloadState()
}
