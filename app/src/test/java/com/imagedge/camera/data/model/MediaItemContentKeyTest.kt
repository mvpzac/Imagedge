package com.imagedge.camera.data.model

import com.imagedge.camera.ptp.PhotoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-27
 *     desc   : 内容指纹必须跨会话稳定。PTP 句柄每次重新枚举都会重排，
 *              拿它当去重键 = 每次重连相机就把拍过的照片再自动拉一遍。
 * </pre>
 */
class MediaItemContentKeyTest {

    private fun item(
        handle: Long,
        filename: String = "DSC0001.JPG",
        sizeBytes: Long = 4_000_000,
        captureDate: Date? = Date(1_700_000_000_000L),
    ) = MediaItem(
        handle = handle,
        channelKey = handle.toString(),
        filename = filename,
        sizeBytes = sizeBytes,
        photoType = PhotoType.JPEG,
        captureDate = captureDate,
    )

    @Test
    fun `the same photo renumbered by the camera keeps its content key`() {
        // 核心缺陷：thumbKey 含句柄，重连后同一张照片换了句柄 → 账本认不出 → 重复自动保存
        val before = item(handle = 0x10001)
        val after = item(handle = 0x20099)

        assertNotEquals("句柄确实变了（这正是问题前提）", before.thumbKey, after.thumbKey)
        assertEquals("内容指纹必须跨会话稳定", before.contentKey, after.contentKey)
    }

    @Test
    fun `different photos get different content keys`() {
        val a = item(handle = 1, filename = "DSC0001.JPG")
        val b = item(handle = 1, filename = "DSC0002.JPG")

        assertNotEquals(a.contentKey, b.contentKey)
    }

    @Test
    fun `a re-saved file of a different size is a different photo`() {
        // 相机复用文件名的情况：编辑过的图与原图同名
        val original = item(handle = 1, filename = "DSC0001.JPG", sizeBytes = 4_000_000)
        val edited = item(handle = 1, filename = "DSC0001.JPG", sizeBytes = 1_200_000)

        assertNotEquals(original.contentKey, edited.contentKey)
    }

    @Test
    fun `a photo taken at a different moment is a different photo`() {
        val a = item(handle = 1, captureDate = Date(1_700_000_000_000L))
        val b = item(handle = 1, captureDate = Date(1_700_000_060_000L))

        assertNotEquals(a.contentKey, b.contentKey)
    }

    @Test
    fun `a missing capture date does not collapse every photo into one key`() {
        // 缺拍摄时间很常见（已编辑/压缩过的图）。此时至少文件名与大小仍要参与，
        // 否则所有无名日期的照片会共用一个键。
        val a = item(handle = 1, filename = "A.JPG", captureDate = null)
        val b = item(handle = 2, filename = "B.JPG", captureDate = null)

        assertNotEquals(a.contentKey, b.contentKey)
    }
}
