package com.imagedge.camera.feature.album

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
class PhotoViewerViewModel @Inject constructor(
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

    val startIndex: Int = (savedStateHandle.get<String>("index")?.toIntOrNull() ?: 0)
        .coerceIn(0, (items.size - 1).coerceAtLeast(0))

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
            try {
                val file = File(context.cacheDir, "preview_" + item.filename)
                if (file.exists() && item.sizeBytes > 0 && file.length() == item.sizeBytes) {
                    _videoStates.value = _videoStates.value + (key to VideoDownloadState.Ready(file))
                    return@launch
                }
                repository.downloadToFile(item, file) { loaded, total ->
                    val progress = if (total > 0) (loaded * 100 / total).toInt() else 0
                    _videoStates.value = _videoStates.value + (key to VideoDownloadState.Downloading(progress))
                }
                _videoStates.value = _videoStates.value + (key to VideoDownloadState.Ready(file))
            } catch (e: Exception) {
                AppLog.w("viewer", "视频下载失败 ${item.filename}：${e.message}")
                _videoStates.value = _videoStates.value + (key to VideoDownloadState.Failed(e.message ?: "下载失败"))
            }
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
}

/** 视频下载状态（查看器播放用） */
sealed class VideoDownloadState {
    data object Idle : VideoDownloadState()
    data class Downloading(val progress: Int) : VideoDownloadState()
    data class Ready(val file: File) : VideoDownloadState()
    data class Failed(val message: String) : VideoDownloadState()
}
