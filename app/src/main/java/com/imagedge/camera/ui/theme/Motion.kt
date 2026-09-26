package com.imagedge.camera.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-31
 *     desc   : 动效规范（两档弹簧 + 两档时长），并遵从系统「移除动画」
 *     version: 1.1
 * </pre>
 */
object Motion {
    private fun <T> snappySpring() = spring<T>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    private fun <T> softSpring() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    val springSnappy = snappySpring<Float>()
    val springSoft = softSpring<Float>()

    /** Dp 版本（导航栏磁吸位移与指示条弹簧用） */
    val springSnappyDp = snappySpring<Dp>()
    val springSoftDp = softSpring<Dp>()

    val durationShort = 150
    val durationStandard = 250

    fun <T> tweenShort() = tween<T>(durationMillis = durationShort)
    fun <T> tweenStandard() = tween<T>(durationMillis = durationStandard)

    /**
     * 系统是否要求「移除动画」（无障碍设置里的移除动画 / 开发者选项里动画时长缩放 = 0）。
     *
     * 为什么要自己判断：**Compose 不读这个开关**。整个 `androidx.compose.animation`
     * 里没有任何一处查 `animator_duration_scale`（在 1.7.6 的 classes.jar 里搜过，
     * 唯一读它的是 `WindowRecomposer`，管的是重组时机，不是动画时长）。
     * View 体系会自动跳过，`animate*AsState` / `Animatable` 照跑不误，
     * 所以「遵从系统设置」只能在这一层做。读不到就按「没关」处理，不猜。
     */
    fun animationsDisabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)

    /** 同理给时长：0 毫秒就是「立刻」 */
    fun durationOrZero(durationMillis: Int, disabled: Boolean): Int =
        if (disabled) 0 else durationMillis
}

/**
 * 要求移除动画时直接到位、不补间。
 *
 * 写成顶层扩展而不是 `Motion` 的成员扩展：成员扩展要把 dispatch receiver 拉进作用域才能调用，
 * 调用点会变成「先 import 一个对象再指望它能解析」——顶层扩展一个 import 就够。
 */
fun <T> AnimationSpec<T>.orSnap(disabled: Boolean): AnimationSpec<T> =
    if (disabled) snap() else this

