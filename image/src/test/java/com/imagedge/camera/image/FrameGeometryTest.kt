package com.imagedge.camera.image

import com.imagedge.camera.image.FrameGeometry.FrameSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版式数学的单测。
 *
 * 断言的重心不是"某个数字等于某个数字"，而是两条**不变式**：
 * 1. 版式随源图宽按比例放大（导出改全分辨率后不必重排版的前提）；
 * 2. 文本超出时一定收敛——要么缩到下限，要么截断，绝不静默溢出。
 */
class FrameGeometryTest {

    /** 比例字体模型：宽度 = 字号 × 系数。够用来验证算法，不是真实字体度量。 */
    private fun measurer(perChar: Float = 10f): (CharSequence) -> Float = { it.length * perChar }

    // ── 成品尺寸 ──────────────────────────────────────────────────────────

    @Test
    fun `经典白边四边留白且加信息栏`() {
        val out = FrameGeometry.output(FrameGeometry.FRAMED, srcWidth = 1000, srcHeight = 800)
        assertEquals(45, out.sideMargin)       // 1000*0.045
        assertEquals(155, out.barHeight)        // 1000*0.155
        assertEquals(1000 + 45 * 2, out.width)
        assertEquals(800 + 45 + 155, out.height)
        assertEquals(out.height - out.barHeight, out.barTop)
    }

    @Test
    fun `贴边模板宽度等于源图宽`() {
        val out = FrameGeometry.output(FrameGeometry.SIGNATURE, srcWidth = 1000, srcHeight = 800)
        assertEquals(1000, out.width)
        assertEquals(0, out.sideMargin)
        assertEquals(800 + 170, out.height)    // 1000*0.17
    }

    @Test
    fun `极简叠字不改变画面尺寸`() {
        val out = FrameGeometry.output(FrameGeometry.MINIMAL, srcWidth = 1000, srcHeight = 800)
        assertEquals(1000, out.width)
        assertEquals(800, out.height)
    }

    /**
     * 四套模板对任意源图尺寸都不得为负或为零——那会让 `createBitmap` 抛
     * IllegalArgumentException，而错误出现在渲染而非版式计算。
     */
    @Test
    fun `成品尺寸恒为正`() {
        val specs = listOf(
            FrameGeometry.FRAMED, FrameGeometry.POLAROID,
            FrameGeometry.SIGNATURE, FrameGeometry.MINIMAL,
        )
        for (spec in specs) {
            for ((w, h) in listOf(1 to 1, 16 to 9, 640 to 480, 4000 to 3000, 8000 to 6000)) {
                val out = FrameGeometry.output(spec, w, h)
                assertTrue("$spec @ ${w}x$h", out.width > 0 && out.height > 0)
                assertTrue("$spec @ ${w}x$h", out.barTop > 0)
            }
        }
    }

    // ── 比例不变式 ────────────────────────────────────────────────────────

    /**
     * 导出改按原分辨率重算后，**版式必须与预览同构**。
     * 做法不是比数字，而是断言同一个源图宽放大 4 倍时，成品尺寸也精确放大 4 倍。
     */
    @Test
    fun `版式随源图宽精确线性放大`() {
        for (spec in allSpecs()) {
            assertTrue("$spec 未脱离下限", FrameGeometry.proportionalAbove(spec, 1600))
            val small = FrameGeometry.output(spec, srcWidth = 1600, srcHeight = 1200)
            val large = FrameGeometry.output(spec, srcWidth = 6400, srcHeight = 4800)
            assertEquals("$spec width", small.width * 4, large.width)
            assertEquals("$spec margin", small.sideMargin * 4, large.sideMargin)
            assertEquals("$spec bar", small.barHeight * 4, large.barHeight)
            // 高度里源高本身占主导，不要求整条线性；只要求栏与留白各自成比例。
            assertEquals(
                "$spec barHeight/height 比值",
                small.barHeight.toDouble() / small.height,
                large.barHeight.toDouble() / large.height,
                0.001,
            )
        }
    }

    /**
     * 覆盖**全部**模板，而不只是硬编码的三套——多一套模板就少一份检查，
     * 而这个不变式恰恰是「导出改全分辨率不必重排版」的依据。
     * 新增模板时若忘了加进 [allSpecs]，这里会安静地少查一套。
     */
    private fun allSpecs(): List<FrameGeometry.FrameSpec> = listOf(
        FrameGeometry.FRAMED,
        FrameGeometry.POLAROID,
        FrameGeometry.SIGNATURE,
        FrameGeometry.MINIMAL,
    )

    /**
     * 变异测试：给某模板加一个只在预览尺寸以下才生效的下限，
     * [proportionalAbove] 必须开始报 false，否则导出与预览会在小图上分家。
     */
    @Test
    fun `加一个超出比例值的下限会破坏不变式`() {
        val spec = FrameGeometry.FRAMED
        assertTrue(FrameGeometry.proportionalAbove(spec, 1600))
        // 1600 × 0.155 = 248 < 300，故在 1600 上已经脱离下限
        assertFalse(FrameGeometry.proportionalAbove(spec.copy(barMin = 300), 1600))
        // 4000 × 0.155 = 620 ≥ 300，恢复
        assertTrue(FrameGeometry.proportionalAbove(spec.copy(barMin = 300), 4000))
    }

    // ── 字号自适应 ────────────────────────────────────────────────────────

    @Test
    fun `放得下就用基准字号`() {
        val size = FrameGeometry.fitTextSize(40f, 20f, 1000f) { 10 * it }
        assertEquals(40f, size, 0.001f)
    }

    @Test
    fun `放不下等比缩到可用宽度`() {
        // 40px 时宽 400，可用 200 → 20px
        val size = FrameGeometry.fitTextSize(40f, 10f, 200f) { 10 * it }
        assertEquals(20f, size, 0.001f)
    }

    @Test
    fun `缩到下限仍放不下时停在下限而不是继续缩`() {
        // 40px 时宽 400，可用 10 → 理论 1px，但下限 10px
        val size = FrameGeometry.fitTextSize(40f, 10f, 10f) { 10 * it }
        assertEquals(10f, size, 0.001f)
    }

    @Test
    fun `可用宽度非正时保留基准字号`() {
        assertEquals(40f, FrameGeometry.fitTextSize(40f, 10f, 0f) { error("不应测量") }, 0.001f)
        assertEquals(40f, FrameGeometry.fitTextSize(40f, 10f, -5f) { error("不应测量") }, 0.001f)
    }

    // ── 截断 ──────────────────────────────────────────────────────────────

    @Test
    fun `放得下原样返回同一实例`() {
        val text = "ILCE-7RM5"
        assertTrue(text === FrameGeometry.ellipsize(text, 1000f, measure = measurer()))
    }

    @Test
    fun `超宽时截断并补省略号`() {
        // 每字 10px，可用 55 → 4 字 + 省略号 = 50px
        val out = FrameGeometry.ellipsize("ILCE-7RM5", 55f, measure = measurer())
        assertEquals("ILCE…", out)
    }

    @Test
    fun `连省略号都放不下时返回空串`() {
        assertEquals("", FrameGeometry.ellipsize("ILCE", 5f, measure = measurer()))
    }

    @Test
    fun `空串与非正宽度不产生内容`() {
        assertEquals("", FrameGeometry.ellipsize("", 100f, measure = measurer()))
        assertEquals("", FrameGeometry.ellipsize("ILCE", 0f, measure = measurer()))
    }

    /**
     * 按码点截断：代理对（emoji / 部分生僻字）不能被劈成两半。
     *
     * 断言**不能**写成"结果里没有 U+FFFD"——按 char 下标截断产出的是孤立的
     * 高/低代理项（`0xD83D` 之类），不是替换字符；`U+FFFD` 只在解码非法字节时出现。
     * 那条断言在被测 bug 下也是绿的，所以证明不了任何事。
     * 这里查的是真正的失败形态：**结果里存在没有高代理项打头的低代理项**。
     */
    @Test
    fun `代理对不被劈开`() {
        val text = "📷📷📷📷📷"   // 5 个码位 = 10 个 char
        // 每 char 10px：可用 55 → 容纳 5 个 char = 2 个码位 + 省略号(1 char) = 5 char = 50px
        val out = FrameGeometry.ellipsize(text, 55f, measure = measurer())
        assertEquals("📷📷…", out)
        assertNoLoneSurrogate(out)
    }

    /**
     * 代理对被劈开的两种形态都要拦住：
     * 1. 中间出现没有高代理项打头的**低**代理项；
     * 2. 末尾落在一个**高**代理项上（它后面本该还有低代理项）。
     *
     * 只查第 1 种是不够的：截断点落在奇数下标时产出的是尾部孤立高代理项，
     * 那种结果里一个低代理项都没有，第 1 种检查会安静地放过它。
     */
    private fun assertNoLoneSurrogate(s: String) {
        for (i in s.indices) {
            if (Character.isLowSurrogate(s[i])) {
                assertTrue(
                    "第 $i 位是孤立低代理项 U+${s[i].code.toString(16)}：$s",
                    i > 0 && Character.isHighSurrogate(s[i - 1]),
                )
            }
        }
        if (s.isNotEmpty()) {
            val last = s.length - 1
            assertFalse(
                "末尾第 $last 位是孤立高代理项 U+${s[last].code.toString(16)}：$s",
                Character.isHighSurrogate(s[last]),
            )
        }
    }

    // ── 左右分栏预算 ──────────────────────────────────────────────────────

    @Test
    fun `无右段时左侧独占整行`() {
        assertEquals(500f, FrameGeometry.leftBudgetOf(500f, rightWidth = 0f, gap = 20f), 0.001f)
    }

    @Test
    fun `右段存在时左侧让出其宽度与间距`() {
        assertEquals(300f, FrameGeometry.leftBudgetOf(500f, rightWidth = 180f, gap = 20f), 0.001f)
    }

    /**
     * 回归：两段共用一行时，各自按**整行宽**自适应必然重叠——
     * 两段都"放得下"，合起来超出画布，表现为型号与署名静默叠在一起。
     *
     * 断言的是修复后的**最终结果**：分预算 → 各自适应 → 仍放不下则截断，三步之后
     * 左段 + 间距 + 右段必须落回整行内。只断言"不重叠"而不管左段文字本身，
     * 那条断言在旧代码下也是绿的——它证明不了任何事。
     */
    @Test
    fun `分预算后左右两段不重叠`() {
        val total = 500f
        val gap = 20f
        // 长度刻意取到「各自都放得下、合起来放不下」：repeat 保证字符数可预期。
        // 实际字符数 leftText=12×4=48、rightText=8×4=32，注释里的数字要跟着改。
        val rightText = "© Studio".repeat(4)          // 32 字
        val leftText = "ILCE-7RM5 机身".repeat(4)     // 48 字
        val perRight = 8f
        val perLeft = 9f

        // 旧行为：两段各自按整行宽自适应，两段都判定"放得下"，合起来溢出
        val oldLeft = leftText.length * perLeft
        val oldRight = rightText.length * perRight
        assertTrue("前提不成立，本用例测不到修复", oldLeft <= total && oldRight <= total)
        assertTrue("旧行为其实不溢出，用例失去意义", oldLeft + gap + oldRight > total)

        // 新行为：先分预算
        val rightSize = FrameGeometry.fitTextSize(24f, 14f, total) { rightText.length * perRight * it / 24f }
        val rightWidth = rightText.length * perRight * rightSize / 24f
        val budget = FrameGeometry.leftBudgetOf(total, rightWidth, gap)
        val leftSize = FrameGeometry.fitTextSize(30f, 16f, budget) { leftText.length * perLeft * it / 30f }
        val leftWidth = leftText.length * perLeft * leftSize / 30f
        assertTrue("左段预算未让出右段宽度", budget < total)
        // 左段必须真的撞到 minSize 下限，否则下面走不到截断分支，用例只剩前半段有效
        assertEquals("左段应停在字号下限 16", 16f, leftSize, 0.001f)
        assertTrue("前提不成立：minSize 下限本就能收口", leftWidth + gap + rightWidth > total)

        // 仍超出（minSize 下限会拦住继续缩）时靠截断收口
        val finalLeft = if (leftWidth + gap + rightWidth <= total) leftText
        else FrameGeometry.ellipsize(leftText, budget) { it.length * perLeft * leftSize / 30f }
        assertTrue("未走截断分支", finalLeft.length < leftText.length && finalLeft.endsWith("…"))
        val finalLeftWidth = finalLeft.length * perLeft * leftSize / 30f
        assertTrue(
            "左段 $finalLeftWidth + 间距 $gap + 右段 $rightWidth 仍超出整行 $total",
            finalLeftWidth + gap + rightWidth <= total + 0.001f,
        )
    }

    /**
     * 截断是右段本身就超长时的唯一收口：左段预算会被压到 0，
     * 此时不能靠缩字号（那只会停在 minSize），必须靠省略号。
     */
    @Test
    fun `右段独占整行时左段退化为省略号`() {
        val total = 200f
        val rightWidth = total   // 右段吃掉全部
        val budget = FrameGeometry.leftBudgetOf(total, rightWidth, gap = 20f)
        assertEquals(0f, budget, 0.001f)

        val leftSize = FrameGeometry.fitTextSize(30f, 16f, budget) { 1f }
        assertEquals("预算为 0 时保留基准字号", 30f, leftSize, 0.001f)

        // 每字 10px，可用 55 → 4 字 + 省略号 = 50px（可用 60 会截到 5 字）
        val clipped = FrameGeometry.ellipsize("ILCE-7RM5", 55f) { it.length * 10f }
        assertEquals("ILCE…", clipped)
        // 可用宽度为 0 时连省略号都放不下，返回空串而不是留一个"…"孤零零挂在那
        assertEquals("", FrameGeometry.ellipsize("ILCE-7RM5", 0f) { it.length * 10f })
    }

    @Test
    fun `右段比整行还宽时左段预算为零而不是负数`() {
        assertEquals(0f, FrameGeometry.leftBudgetOf(100f, rightWidth = 300f, gap = 20f), 0.001f)
    }

    // ── 快门格式化 ────────────────────────────────────────────────────────

    @Test
    fun `一秒以上显示整秒`() {
        assertEquals("2s", FrameGeometry.formatShutter(seconds = 2.0, apexTv = null))
        assertEquals("1s", FrameGeometry.formatShutter(seconds = 1.0, apexTv = null))
    }

    @Test
    fun `一秒以下显示倒数`() {
        assertEquals("1/125s", FrameGeometry.formatShutter(seconds = 0.008, apexTv = null))
        assertEquals("1/8000s", FrameGeometry.formatShutter(seconds = 0.000125, apexTv = null))
    }

    /**
     * 回归：`ExposureTime` 缺失时不能整个字段留空。此时用 APEX 的
     * `ShutterSpeedValue`，`Tv = log2(1/t)`，所以曝光时间 = 2^(-Tv)。
     *
     * **方向容易搞反**：Tv 越大越快。写成 `2^Tv` 的后果不是格式错，是把 1/128s
     * 显示成 128s——长曝与快门互换，而两边看起来都是"合法的快门值"。
     * 所以下面把两个方向都钉住，而不只钉 1/128 这一支。
     */
    @Test
    fun `缺少 ExposureTime 时退回 APEX`() {
        assertEquals("1/128s", FrameGeometry.formatShutter(seconds = null, apexTv = 7.0))
        assertEquals("2s", FrameGeometry.formatShutter(seconds = null, apexTv = -1.0))
        assertEquals("1s", FrameGeometry.formatShutter(seconds = null, apexTv = 0.0))
    }

    @Test
    fun `APEX 方向搞反会把快门显示成长曝`() {
        // Tv = -7 → 曝光时间 128 秒；写成 2^Tv 会得到 1/128s，快门与长曝互换
        assertEquals("128s", FrameGeometry.formatShutter(seconds = null, apexTv = -7.0))
    }

    @Test
    fun `两个来源都没有时留空而不编造`() {
        assertEquals("", FrameGeometry.formatShutter(seconds = null, apexTv = null))
    }

    /** ExposureTime 优先于 APEX：两个都在时不得被后者盖掉。 */
    @Test
    fun `ExposureTime 优先于 APEX`() {
        assertEquals("1/125s", FrameGeometry.formatShutter(seconds = 0.008, apexTv = 3.0))
    }

    @Test
    fun `非正的曝光时间不产生 1-0 之类的文本`() {
        assertEquals("", FrameGeometry.formatShutter(seconds = 0.0, apexTv = null))
        assertEquals("", FrameGeometry.formatShutter(seconds = -1.0, apexTv = null))
    }

    /** APEX 是正数就一定导出正曝光时间：tv=10 是 1/1024s，合法，不该被当成"无效"。 */
    @Test
    fun `正 APEX 值仍是合法快门`() {
        assertEquals("1/1024s", FrameGeometry.formatShutter(seconds = null, apexTv = 10.0))
    }
}