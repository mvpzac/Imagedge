package com.imagedge.camera.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/31
 *     desc   : 动效规范（全项目统一两档弹簧 + 两档时长）：
 *              - snappy：小元素（指示条、图标上浮）
 *              - soft：容器（导航滑条、面板弹入）
 *     version: 1.0
 * </pre>
 */
object Motion {
    private fun <T> snappySpring() = spring<T>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    private fun <T> softSpring() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    val springSnappy = snappySpring<Float>()
    val springSoft = softSpring<Float>()

    /** Dp 版本（RootScreen 等位移动画用） */
    val springSnappyDp = snappySpring<Dp>()
    val springSoftDp = softSpring<Dp>()

    val durationShort = 150
    val durationStandard = 250

    fun <T> tweenShort() = tween<T>(durationMillis = durationShort)
    fun <T> tweenStandard() = tween<T>(durationMillis = durationStandard)
}
