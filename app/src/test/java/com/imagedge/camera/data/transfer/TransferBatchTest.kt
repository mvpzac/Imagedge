package com.imagedge.camera.data.transfer

import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 批次口径验收（批次 C）：摘要只说本批、任务条只在有事时存在、重启后状态怎么落
 * </pre>
 */
class TransferBatchTest {

    private fun item(
        handle: Long,
        filename: String = "DSC_$handle.ARW",
        sizeBytes: Long = 1024L
    ) = MediaItem(
        handle = handle,
        channelKey = "ptp://$handle",
        filename = filename,
        sizeBytes = sizeBytes,
        photoType = PhotoType.RAW,
        captureDate = Date(1_700_000_000_000L)
    )

    private fun task(
        handle: Long,
        state: DownloadState,
        batchId: Long = 1L,
        progress: Int = 0,
        failureViewed: Boolean = false
    ) = DownloadTask(
        mediaItem = item(handle),
        batchId = batchId,
        state = state,
        progress = progress,
        errorMessage = if (state == DownloadState.FAILED) "相机连接已断开" else null,
        failureViewed = failureViewed
    )

    // ── 批次摘要 ───────────────────────────────────────────────────────

    @Test
    fun `summary counts saved and unfinished of the design example`() {
        // 设计 §4.5 的样例：已保存 2 项，1 项未完成
        val tasks = listOf(
            task(1, DownloadState.DONE),
            task(2, DownloadState.DONE),
            task(3, DownloadState.FAILED)
        )

        val summary = batchSummaryOf(tasks)

        requireNotNull(summary)
        assertEquals(2, summary.savedCount)
        assertEquals(1, summary.unfinishedCount)
        assertEquals(listOf(3L), summary.retryable.map { it.mediaItem.handle })
    }

    @Test
    fun `summary speaks only about the newest batch`() {
        // 上一批的失败还留在队列里（用户没点清空），但摘要说的是「刚提交的那一批」。
        // 混着数会出现「已保存 5 项」而用户这批只勾了 2 张的怪事。
        val tasks = listOf(
            task(1, DownloadState.DONE, batchId = 4),
            task(2, DownloadState.FAILED, batchId = 4),
            task(3, DownloadState.DONE, batchId = 5),
            task(4, DownloadState.QUEUED, batchId = 5)
        )

        val summary = batchSummaryOf(tasks)

        requireNotNull(summary)
        assertEquals(5L, summary.batchId)
        assertEquals("旧批次的完成项不算进本批", 1, summary.savedCount)
        assertEquals(1, summary.unfinishedCount)
    }

    @Test
    fun `queued and downloading count as unfinished but are not retryable`() {
        // 「重试未完成项」只针对失败项：排队/下载中的再入队一次就是同一文件传两遍
        val tasks = listOf(
            task(1, DownloadState.QUEUED),
            task(2, DownloadState.DOWNLOADING, progress = 40),
            task(3, DownloadState.FAILED)
        )

        val summary = batchSummaryOf(tasks)

        requireNotNull(summary)
        assertEquals(0, summary.savedCount)
        assertEquals(3, summary.unfinishedCount)
        assertEquals(listOf(3L), summary.retryable.map { it.mediaItem.handle })
    }

    @Test
    fun `no tasks means no batch to summarize`() {
        assertNull(batchSummaryOf(emptyList()))
    }

    // ── 全局任务条 ─────────────────────────────────────────────────────

    @Test
    fun `mini bar appears while anything is still transferring`() {
        val bar = miniBarOf(listOf(task(1, DownloadState.DOWNLOADING, progress = 70)))

        requireNotNull(bar)
        assertEquals(1, bar.activeCount)
        assertEquals("没有在跑的任务就不该有进度可看", 0, bar.unviewedFailureCount)
    }

    @Test
    fun `mini bar appears for failures the user has not seen yet`() {
        val bar = miniBarOf(
            listOf(
                task(1, DownloadState.DONE),
                task(2, DownloadState.FAILED, failureViewed = false)
            )
        )

        requireNotNull(bar)
        assertEquals(0, bar.activeCount)
        assertEquals(1, bar.unviewedFailureCount)
    }

    @Test
    fun `mini bar disappears once the failures have been viewed`() {
        // 「无任务即消失」：看过一次之后， settled 的账目不能永远占着底部
        val bar = miniBarOf(
            listOf(
                task(1, DownloadState.DONE),
                task(2, DownloadState.FAILED, failureViewed = true)
            )
        )

        assertNull(bar)
    }

    @Test
    fun `mini bar ignores an old batchs unviewed failure`() {
        val bar = miniBarOf(
            listOf(
                task(1, DownloadState.FAILED, batchId = 3, failureViewed = false),
                task(2, DownloadState.DONE, batchId = 4)
            )
        )

        assertNull("本批没事就不弹条，旧账留在页面上看", bar)
    }

    @Test
    fun `empty queue means no mini bar`() {
        assertNull(miniBarOf(emptyList()))
    }

    // ── 重启后的状态 ────────────────────────────────────────────────────

    @Test
    fun `an interrupted download comes back as queued not at its old percentage`() {
        // PTP 整文件下载没有断点：重启后「45%」是一个立刻就不成立的数字
        assertEquals(DownloadState.QUEUED, restoredStateOf(DownloadState.DOWNLOADING))
        assertEquals(DownloadState.QUEUED, restoredStateOf(DownloadState.QUEUED))
        assertTrue(resumesOnRestore(DownloadState.DOWNLOADING))
    }

    @Test
    fun `settled outcomes are restored as facts and never re-downloaded`() {
        assertEquals(DownloadState.DONE, restoredStateOf(DownloadState.DONE))
        assertEquals(DownloadState.FAILED, restoredStateOf(DownloadState.FAILED))
        assertFalse("自动重试会在每次开机时重烧一遍相机", resumesOnRestore(DownloadState.FAILED))
        assertFalse(resumesOnRestore(DownloadState.DONE))
    }

    @Test
    fun `a task row carries its state progress uri and batch through the database`() {
        val entity = DownloadTaskEntity.from(item(9), batchId = 3L)
        assertEquals("QUEUED", entity.state)
        assertEquals(3L, entity.batchId)
        assertEquals(DownloadState.QUEUED, entity.persistedState)
        assertEquals("刚入队时既没有结果也没有 Uri", null, entity.savedUri)

        val done = entity.copy(
            state = DownloadState.DONE.name,
            progress = 100,
            savedUri = "content://media/external/images/media/42"
        )
        assertEquals(DownloadState.DONE, done.persistedState)
        assertEquals(100, done.progress)
    }

    @Test
    fun `an unreadable state name falls back to queued instead of being dropped`() {
        // 读不懂的状态当已完成 = 骗用户；当已失败删掉行 = 丢数据。只有排队是安全的
        val odd = DownloadTaskEntity.from(item(1), batchId = 0L).copy(state = "SOMETHING_NEW")
        assertEquals(DownloadState.QUEUED, odd.persistedState)
    }

    @Test
    fun `a restored row can be re-downloaded on its own without the album list`() {
        // 重试的前提：恢复出来的任务必须自带句柄与通道键
        val restored = DownloadTaskEntity.from(item(7, "DSC_0007.JPG", 4096L), batchId = 2L)
            .let { DownloadTask(mediaItem = it.toMediaItem(), batchId = it.batchId, state = it.persistedState) }

        assertEquals("ptp://7", restored.mediaItem.channelKey)
        assertEquals(7L, restored.mediaItem.handle)
        assertEquals(restored.id, restored.mediaItem.thumbKey)
        assertEquals("DSC_0007.JPG", restored.filename)
        assertEquals(4096L, restored.sizeBytes)
    }

    // ── 历史记录的可打开 / 可重试 ─────────────────────────────────────────

    @Test
    fun `a legacy record offers neither view nor retry`() {
        // 升级前写入的行没有 savedUri / channelKey / handle，硬凑一个句柄去下载
        // 就是拿 0x2009 换一次真实的失败请求
        val legacy = DownloadHistoryEntity(
            id = 1,
            filename = "DSC_0001.ARW",
            savedPath = "DCIM/Imagedge/DSC_0001.ARW",
            startTime = 1L,
            endTime = 2L,
            cameraModel = "ZV-E10",
            sizeBytes = 1024L,
            success = true
        )

        assertNull(legacy.savedUri)
        assertNull(legacy.thumbKey)
        assertNull(legacy.toMediaItemOrNull())
    }

    @Test
    fun `a new record can be reopened and retried`() {
        val record = DownloadHistoryEntity(
            id = 2,
            filename = "DSC_0002.ARW",
            savedPath = "DCIM/Imagedge/DSC_0002.ARW",
            startTime = 1L,
            endTime = 2L,
            cameraModel = "ZV-E10",
            sizeBytes = 1024L,
            success = true,
            channelKey = "ptp://12",
            handle = 12L,
            photoType = PhotoType.RAW.name,
            captureDate = 1_700_000_000_000L,
            savedUri = "content://media/external/images/media/9"
        )

        assertEquals("ptp://12|1024|DSC_0002.ARW", record.thumbKey)
        val rebuilt = record.toMediaItemOrNull()
        requireNotNull(rebuilt)
        assertEquals(12L, rebuilt.handle)
        assertEquals(PhotoType.RAW, rebuilt.photoType)
        // 记录行的 thumbKey 必须与任务行的主键算得出同一个值，否则两边对不上账
        assertEquals(DownloadTaskEntity.from(rebuilt, batchId = 1L).id, record.thumbKey)
    }

    @Test
    fun `a record with half its identity missing is not retryable`() {
        val partial = DownloadHistoryEntity(
            id = 3,
            filename = "DSC_0003.JPG",
            savedPath = "",
            startTime = 1L,
            endTime = 2L,
            cameraModel = "",
            sizeBytes = 1L,
            success = false,
            channelKey = "ptp://5",
            errorMessage = "相机连接已断开"
        )

        assertNull("句柄都没记下，重试就是瞎猜", partial.toMediaItemOrNull())
        assertEquals("相机连接已断开", partial.errorMessage)
    }
}
