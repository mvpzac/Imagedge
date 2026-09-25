package com.imagedge.camera.domain.media

import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 媒体身份解析验收（设计 §8.1）：指纹对不上就不猜照片
 * </pre>
 */
class MediaIdTest {

    private fun item(handle: Long, name: String, size: Long = 1024L) = MediaItem(
        handle = handle,
        channelKey = "ptp://$handle",
        filename = name,
        sizeBytes = size,
        photoType = PhotoType.JPEG,
        captureDate = Date(0L)
    )

    private val list = listOf(
        item(11, "DSC00011.JPG"),
        item(12, "DSC00012.JPG"),
        item(13, "DSC00013.ARW", 24_000_000L)
    )

    @Test
    fun `a fingerprint resolves to its position`() {
        assertEquals(0, resolveIndex(list, list[0].mediaId))
        assertEquals(2, resolveIndex(list, list[2].mediaId))
    }

    @Test
    fun `a stale fingerprint resolves to nothing, not to the first photo`() {
        // 回退到 0 会把一张用户没点开的照片说成他点的那张
        assertNull(resolveIndex(list, "ptp://99|1|GONE.JPG"))
        assertNull(resolveIndex(list, null))
        assertNull(resolveIndex(list, ""))
        assertNull(resolveIndex(emptyList(), list[0].mediaId))
    }

    @Test
    fun `a reused handle pointing at different content is a different id`() {
        // 相机复用句柄：同一 channelKey 换了指纹，旧 ID 必须解析失败而不是打开新照片
        val sameHandleDifferentContent = item(11, "DSC99999.JPG")

        assertEquals(list[0].mediaId, resolveIndex(list, list[0].mediaId)?.let { list[it].mediaId })
        assertNull(resolveIndex(list, sameHandleDifferentContent.mediaId))
    }
}
