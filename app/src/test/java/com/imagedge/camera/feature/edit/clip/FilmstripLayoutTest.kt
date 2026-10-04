package com.imagedge.camera.feature.edit.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 缩略图条的数量策略与手柄定区——纯函数，故不依赖 Compose 也能测
 * </pre>
 */
class FilmstripLayoutTest {

    /**
     * 缩略图密度取自 OpenLoop `TrimFilmstripControls.kt:87-88` 的
     * `FILMSTRIP_FRAME_MIN = 6` / `FILMSTRIP_FRAME_MAX = 14`。
     * 少于 6 张看不出运动，多于 14 张单张太窄、连手柄都放不下。
     */
    @Test
    fun `缩略图数量随轨道宽度增长并钳在 6 到 14 之间`() {
        assertEquals(6, filmstripHandleCount(0f))
        assertEquals(6, filmstripHandleCount(100f))
        assertEquals(8, filmstripHandleCount(400f))
        assertEquals(14, filmstripHandleCount(100000f))
    }

    @Test
    fun `任何轨道宽度都不会产出零张或超过上限`() {
        for (px in intArrayOf(0, 1, 50, 200, 400, 800, 1600, 100000)) {
            val n = filmstripHandleCount(px.toFloat())
            assertTrue("轨道 ${px}px 产出 $n 张", n in 6..14)
        }
    }

    /**
     * 钉的是**过滤顺序**，不是结果：起手被禁用时，按在它上面应当命中同一半径内的封面柄，
     * 而不是落进死区。
     *
     * 顺序反了（先 `ClipMath.resolveZone` 再看 enabled）的后果是静默的：
     * 「封面」tab 上，用户手指按住封面竖线旁边想去拖封面，却因为那一点离起手柄更近
     * 而被判给被禁用的起手柄，最终一个字也不动。
     */
    @Test
    fun `启用集合先于定区过滤否则封面 tab 上按下起手柄旁边就是死区`() {
        // 按下点 x=100：距起手柄 0px、距封面柄 12px，两者都在 24px 半径内
        val hit = filmstripHandleFor(
            x = 100f,
            startPx = 100f,
            endPx = 400f,
            coverPx = 112f,
            enabled = setOf(FilmstripHandle.Cover),
        )
        assertEquals(FilmstripHandle.Cover, hit)
    }

    /** 反过来也要钉住：启用的柄照常命中，任何一个启用柄都不在半径内时必须是 null（不动） */
    @Test
    fun `启用的手柄照常命中而半径内没有启用手柄时不拖任何东西`() {
        assertEquals(
            FilmstripHandle.In,
            filmstripHandleFor(
                x = 100f,
                startPx = 100f,
                endPx = 400f,
                coverPx = 112f,
                enabled = setOf(FilmstripHandle.In, FilmstripHandle.Out, FilmstripHandle.Cover),
            ),
        )
        assertNull(
            filmstripHandleFor(
                x = 260f,
                startPx = 100f,
                endPx = 400f,
                coverPx = 112f,
                enabled = setOf(FilmstripHandle.In),
            ),
        )
    }
}
