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
     * `6` / `14` 这两个夹取值取自 OpenLoop `TrimFilmstripControls.kt:87-88` 的
     * `FILMSTRIP_FRAME_MIN` / `FILMSTRIP_FRAME_MAX`（评审已在源码处核对）。
     *
     * **除数 48 没有上游来源**——那是本项目自己的取值，含义是「每张缩略图约 48dp 宽」
     * （360dp 轨道 → 8 张、每张 45dp；≥648dp 就取到 14 的上限：
     * `648 / 48 = 13.5`，`roundToInt` 平局向正无穷得 14）。
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
     * 密度无关性钉在这里，而**能钉住的只有签名**。
     *
     * 上一版把它写成了一条数值反证（"360dp@2.75 = 990px → 20 → 14"），实测它咬不住：
     * `360 / 48 = 7.5 → 8`，**除数是像素时也是 8**——同一个数字区分不出单位。
     * 而按 dp 契约，调用方在 density=1 与 density=2.75 上传进来的**本来就是同一个数字 360**
     * （读的是 `maxWidth.value`，不是 `toPx()`），再比一次两次相同的调用同样区分不出东西。
     *
     * 真正能区分的是**函数体里有没有密度因子**：一旦有，就必须再加一个 density 入参
     * （函数体里拿不到别处的密度），签名于是从 `(Float) -> Int` 变成两参。下面三条分别
     * 从编译期、运行期签名、数值上把它钉死，**实测各自会红**：
     *
     * - 钉 1（编译期）：`val count: (Float) -> Int = ::filmstripHandleCount`。
     *   **只能咬住必填的 density 入参**——加上 `density: Float` 后编译失败：
     *   `Initializer type mismatch: expected '(Float) -> Int', actual 'KFunction2<Float, Float, Int>'`。
     * - 钉 2（运行期签名，反射）：形参恰好一个 float。**带默认值的 density 入参钉 1 抓不住**
     *   （实测 `fun f(x: Float, y: Float = 1f)` 赋给 `(Float) -> Int` 照样编译通过，
     *   Kotlin 会把有默认值的尾参适配掉），但这里会红：
     *   `expected:<[float]> but was:<[float, float]>`。
     * - 钉 3（数值锚点）：签名不变、只在函数体里偷偷除一个密度因子时，钉 1/钉 2 全都不响，
     *   只有这一条会红（`360 / 2.75 / 48 = 2.73` → 夹到下限 6，与期望的 8 冲突）。
     *
     * 数值断言到此为止只作锚点。`990f` 是 **990dp**（一条更宽的轨道）的输入，它给 14 是
     * **钳位**、不是密度效应：`990 / 48 = 20.625` → `roundToInt` 得 21 → 夹到 14。
     */
    @Test
    fun `轨道宽度以 dp 计故签名里没有密度因子360dp 的轨道恒为 8 张`() {
        // 钉 1（编译期）：签名就是 (Float) -> Int，一个入参，多一个就编译不过
        val count: (Float) -> Int = ::filmstripHandleCount

        // 钉 2（运行期）：形参恰好一个 float。多出 density 之类的入参当场红。
        // ClipFilmstripKt 是 ClipFilmstrip.kt 的 JVM 门面类（顶层函数的宿主）。
        val signature = Class.forName("com.imagedge.camera.feature.edit.clip.ClipFilmstripKt")
            .declaredMethods
            .single { it.name == "filmstripHandleCount" }
        assertEquals(
            "签名里不允许出现密度入参——密度换算一旦进了函数体，帧数就会跟着屏幕走",
            listOf(Float::class.javaPrimitiveType),
            signature.parameterTypes.toList(),
        )

        // 锚点：360dp 轨道恒 8 张（每张 45dp），与跑在哪块屏上无关
        assertEquals(8, count(360f))
        // 990dp 是另一条更宽的轨道：990/48 = 20.625 → 21 → 钳到 14
        assertEquals(14, count(990f))
    }

    /**
     * 退化宽度也必须是合法张数：0 宽、负值、未约束宽（`Dp.Infinity.value`）、`NaN`
     * 都不得产出 0 张、越界，**更不得抛异常**。
     *
     * 后两档是真实输入不是刁难：调用方在自己的 `BoxWithConstraints` 里读自己的
     * `maxWidth.value`，忘了 `fillMaxWidth()` 它就是 `Float.POSITIVE_INFINITY`。
     *
     * 但**无限值不是崩溃点**（实测更正上一轮的说法）：
     * `Float.POSITIVE_INFINITY.roundToInt()` 返回 `2147483647` 而不是抛异常——
     * 标准库内部是 `Math.round(f)`，溢出时饱和。旧代码靠末尾的 `coerceIn(6, 14)`
     * 就已经把它收成 14，那条路径上从来没有崩过。
     * 真正抛 `IllegalArgumentException` 的只有 `NaN`，而 `NaN` 同样真实可达：
     * `Dp.Unspecified.value` 就是 `NaN`。它没被 `coerceIn` 挡住，是因为
     * `coerceIn` 对 `NaN` 是空操作、照样返回 `NaN`。
     * 所以「先夹后取整 + 单独挡 NaN」这条实现没错，错的是上一轮给它的理由。
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
