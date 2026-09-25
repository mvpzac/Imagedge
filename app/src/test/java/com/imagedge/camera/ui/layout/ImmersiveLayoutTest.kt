package com.imagedge.camera.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 沉浸页排布判定验收（设计 §4.6）：什么时候才允许画面与控件并排
 * </pre>
 */
class ImmersiveLayoutTest {

    @Test
    fun `a phone held upright stacks picture above controls`() {
        assertEquals(ImmersiveLayout.Stacked, immersiveLayoutOf(widthDp = 412f, heightDp = 915f))
    }

    @Test
    fun `a phone turned sideways puts controls to the right of the picture`() {
        assertEquals(ImmersiveLayout.SideBySide, immersiveLayoutOf(widthDp = 915f, heightDp = 412f))
    }

    @Test
    fun `a narrow landscape window keeps stacking rather than squeezing the shutter`() {
        // 小屏横屏 / 系统分屏：并排之后控件栏放不下 72dp 快门加一行按钮，宁可让画面矮一点
        assertEquals(ImmersiveLayout.Stacked, immersiveLayoutOf(widthDp = 560f, heightDp = 300f))
    }

    @Test
    fun `a square window is not treated as landscape`() {
        // 折叠屏展开与分屏常见方形窗口：两栏都会被压窄，上下排更完整
        assertEquals(ImmersiveLayout.Stacked, immersiveLayoutOf(widthDp = 800f, heightDp = 800f))
    }

    @Test
    fun `the threshold is inclusive so a 600dp window gets two columns`() {
        assertEquals(ImmersiveLayout.SideBySide, immersiveLayoutOf(widthDp = 600f, heightDp = 400f))
        assertEquals(ImmersiveLayout.Stacked, immersiveLayoutOf(widthDp = 599.9f, heightDp = 400f))
    }
}
