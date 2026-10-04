package com.imagedge.camera.feature.edit.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 缩略图条的数量策略与手柄定区——纯函数，故不依赖 Compose 也能测
 * </pre>
 */
class FilmstripLayoutTest {

    /**
     * `6` / `14` 这两个夹取值取自 OpenLoop `TrimFilmstripControls.kt:87-88` 的
     * `FILMSTRIP_FRAME_MIN` / `FILMSTRIP_FRAME_MAX`（评审已在源码处核对）。
     *
     * **除数 48 没有上游来源**——那是本项目自己的取值，含义是「每张缩略图约 48dp 宽」
     * （360dp 轨道 → 8 张、每张 45dp；≥672dp 触到 14 的上限，此后单张只会更宽）。
     * 至于「少于 6 张看不出运动」那是观感取舍，同样是本项目的判断，不是上游给的。
     */
    @Test
    fun `缩略图数量随轨道宽度增长并钳在 6 到 14 之间`() {
        // 入参是 **dp**，不是像素
        assertEquals(6, filmstripHandleCount(0f))
        assertEquals(6, filmstripHandleCount(100f))
        assertEquals(8, filmstripHandleCount(400f))
        assertEquals(14, filmstripHandleCount(100000f))
    }

    /**
     * 密度无关性钉在这里，而且**钉的是单位本身**：`filmstripHandleCount` 的入参是 dp，
     * 函数体里没有 density 因子，于是同一段 dp 宽度在任何屏幕上都是同一个数——
     * 没有可换算错的环节。
     *
     * 反证写在同一条用例里：若参数被误当成像素，360dp 的轨道在 density=2.75 上是 990px，
     * `/48` 会给出 20 → 被夹到 14，与期望的 8 差整整 6 张（帧数直接翻倍，内存开销也翻倍）。
     */
    @Test
    fun `轨道宽度以 dp 计故 360dp 的轨道在任何密度下都是 8 张`() {
        assertEquals(8, filmstripHandleCount(360f))
        // 旧写法（除 48 **像素**）在 density=2.75 上会得到的数，与上面的 8 不可调和
        val asPx = (360f * 2.75f / 48f).roundToInt().coerceIn(6, 14)
        assertEquals(14, asPx)
    }

    /**
     * 退化宽度也必须是合法张数：0 宽、负值、未约束宽（`Dp.Infinity.value`）、`NaN`
     * 都不得产出 0 张、越界，**更不得抛异常**。
     *
     * 后两档是真实输入不是刁难：调用方传的是 `BoxWithConstraints` 的 `maxWidth.value`，
     * 忘了 `fillMaxWidth()` 它就是 `Float.POSITIVE_INFINITY`，而 `Float.roundToInt()`
     * 在结果超出 Int 范围时抛 `IllegalArgumentException`——纯函数不该抛，所以先夹后取整。
     */
    @Test
    fun `任何轨道宽度都不会产出零张或超过上限`() {
        for (dp in intArrayOf(0, 1, 50, 200, 400, 800, 1600, 100000)) {
            val n = filmstripHandleCount(dp.toFloat())
            assertTrue("轨道 ${dp}dp 产出 $n 张", n in 6..14)
        }
        assertEquals(6, filmstripHandleCount(-360f))
        // 未约束宽是 Composable 里真实会遇到的输入（maxWidth = Infinity 分支）
        assertEquals(14, filmstripHandleCount(Float.POSITIVE_INFINITY))
        assertEquals(6, filmstripHandleCount(Float.NaN))
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
