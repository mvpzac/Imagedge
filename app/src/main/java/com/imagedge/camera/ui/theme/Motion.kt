package com.imagedge.camera.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * 动效规范（全项目统一两档弹簧 + 两档时长）：
 * - snappy：小元素（指示条、图标上浮）
 * - soft：容器（导航滑条、面板弹入）
 */
object Motion {
    val springSnappy = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessMedium
    )
    val springSoft = spring<Float>(
        dampingRatio = 0.6f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Dp 版本（RootScreen 等位移动画用） */
    val springSnappyDp = spring<Dp>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessMedium
    )
    val springSoftDp = spring<Dp>(
        dampingRatio = 0.6f,
        stiffness = Spring.StiffnessMediumLow
    )

    val durationShort = 150
    val durationStandard = 250

    fun <T> fadeShort() = tween<T>(durationMillis = durationShort)
    fun <T> fadeStandard() = tween<T>(durationMillis = durationStandard)
}
