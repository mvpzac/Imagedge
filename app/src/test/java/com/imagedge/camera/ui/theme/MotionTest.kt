package com.imagedge.camera.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 「移除动画」降级验收（新手手册 §7）：开关按下时确实不补间
 * </pre>
 */
class MotionTest {

    @Test
    fun `reduced motion replaces the spec with a snap`() {
        val tween: AnimationSpec<Float> = tween(durationMillis = 250)

        assertSame("没要求移除动画时，原样返回——不该悄悄改掉时长", tween, tween.orSnap(false))
        // snap() 每次新建实例，所以这里比类型而不是引用
        assertEquals(snap<Float>()::class, tween.orSnap(true)::class)
    }

    @Test
    fun `reduced motion zeroes durations instead of shortening them`() {
        assertEquals(250, Motion.durationOrZero(250, disabled = false))
        assertEquals(
            "「移除动画」是不要动画，不是要快一点的动画",
            0,
            Motion.durationOrZero(250, disabled = true)
        )
    }
}
