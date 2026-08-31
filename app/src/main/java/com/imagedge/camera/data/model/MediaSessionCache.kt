package com.imagedge.camera.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 会话级媒体列表缓存——相册页与二级页（大图查看器）共享当前列表，
 *              避免通过导航参数传递大列表。
 *     version: 1.1 —— 缩略图缓存改为有界，新增 clearThumbnails
 * </pre>
 */

/**
 * 共享缩略图缓存上限（条目数）。
 *
 * 与相册页自身的缓存独立计数，但量级一致：这里主要服务大图查看器的「垫底图」与
 * 下载队列缩略图，不需要留存上千张。
 */
private const val MAX_SESSION_THUMBNAILS = 150

@Singleton
class MediaSessionCache @Inject constructor() {

    /** 最近一次相册扫描结果（thumbKey 顺序与相册网格一致） */
    @Volatile
    var items: List<MediaItem> = emptyList()

    /**
     * 网格缩略图（大图查看器的即时垫底图：点开瞬间可见，原图后台升级）。
     *
     * **有界**：超过 [MAX_SESSION_THUMBNAILS] 按插入顺序淘汰最旧的。
     * 原实现是无界 Map 且**从不清理**——切浏览模式、刷新列表都不清，
     * 整卡模式下上千张缩略图会持续占用内存直至 OOM，且跨会话残留陈旧条目。
     *
     * 每次插入整体换引用（persistent 风格），读取方看到的是一致快照，
     * 不会出现并发遍历被结构性修改打断的问题。
     *
     * 淘汰时只丢弃引用、**不调用 `Bitmap.recycle()`**：位图可能仍被 Compose 的 Image
     * 持有，回收会触发 "Canvas: trying to use a recycled bitmap" 崩溃，交给 GC 更安全。
     */
    @Volatile
    var gridThumbnails: Map<String, android.graphics.Bitmap> = emptyMap()
        private set

    /**
     * 缩略图缓存代数：每次清空（内存 trim / 切浏览模式）递增。
     * 相册格子的加载触发把它并入键——否则 trim 清空缓存后，格子既不知道
     * 位图没了也不会重新加载，网格会出现永久灰块。
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** 相册刷新后同步 */
    fun update(items: List<MediaItem>) {
        this.items = items
    }

    /** 相册缩略图加载后同步（有界：超出上限按插入顺序淘汰最旧的一条） */
    @Synchronized
    fun updateThumbnail(key: String, bitmap: android.graphics.Bitmap) {
        val next = LinkedHashMap(gridThumbnails)
        next[key] = bitmap
        while (next.size > MAX_SESSION_THUMBNAILS) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        gridThumbnails = next
    }

    /**
     * 清空缩略图缓存（切换浏览模式、内存紧张时调用）。
     *
     * 注意这里不能 recycle 位图——它们可能仍被 UI 引用（详见 [gridThumbnails] 注释）。
     */
    @Synchronized
    fun clearThumbnails() {
        gridThumbnails = emptyMap()
        _generation.value += 1
    }
}
