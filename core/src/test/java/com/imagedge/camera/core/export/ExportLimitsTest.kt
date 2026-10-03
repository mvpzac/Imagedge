package com.imagedge.camera.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出尺寸上限的形状验收。
 *
 * 内存注入是刻意的：这条曲线错的时候不会抛异常，只会在大图上 OOM 或在低端机上
 * 静默降到不可用的小图，两者都只能靠断言提前钉住。
 */
class ExportLimitsTest {

    @Test
    fun `小堆被钳到下限`() {
        // 64MB 堆：64*1024*1024/3/8 ≈ 2.8M 像素 → 边长 1677 < 2048
        assertEquals(ExportLimits.MIN_LONG_EDGE, ExportLimits.maxLongEdge(64L * 1024 * 1024))
    }

    @Test
    fun `常见机型落在上下限之间`() {
        for (heapMb in listOf(128, 192, 256, 384, 512)) {
            val side = ExportLimits.maxLongEdge(heapMb * 1024L * 1024L)
            assertTrue(
                "$heapMb MB 堆 → $side 越界",
                side in ExportLimits.MIN_LONG_EDGE..ExportLimits.MAX_LONG_EDGE,
            )
        }
    }

    @Test
    fun `大堆被钳到上限而不是无限制放大`() {
        val huge = 8L * 1024 * 1024 * 1024
        assertEquals(ExportLimits.MAX_LONG_EDGE, ExportLimits.maxLongEdge(huge))
    }

    /** 上限单调不减：堆变大时导出的上限不该反而变小。 */
    @Test
    fun `单调不减`() {
        var previous = 0
        for (heapMb in listOf(32, 64, 128, 192, 256, 384, 512, 1024, 4096)) {
            val side = ExportLimits.maxLongEdge(heapMb * 1024L * 1024L)
            assertTrue("$heapMb MB 堆 → $side < 上一次 $previous", side >= previous)
            previous = side
        }
    }

    /**
     * 回归：写死 6000 会在小内存机器上 OOM，写死 2048 又会让正常机型的 24MP 出片
     * 被腰斩。两条都得防，所以下界与上界都要真的钳得住。
     */
    @Test
    fun `上下界都钳得住`() {
        assertEquals(ExportLimits.MIN_LONG_EDGE, ExportLimits.maxLongEdge(1))
        assertEquals(ExportLimits.MAX_LONG_EDGE, ExportLimits.maxLongEdge(Long.MAX_VALUE / 4))
    }

    @Test
    fun `无参入口在真机上返回一个可用边长`() {
        val side = ExportLimits.maxLongEdge()
        assertTrue("$side", side in ExportLimits.MIN_LONG_EDGE..ExportLimits.MAX_LONG_EDGE)
    }
}