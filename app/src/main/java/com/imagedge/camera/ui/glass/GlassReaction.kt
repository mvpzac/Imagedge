package com.imagedge.camera.ui.glass

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.tanh
/**
 * 液态玻璃的「跟随手指」交互反应（参考 Kyant catalog 的 LiquidButton）：
 *
 * 1. **按压**：整块玻璃轻微缩小（scale 0.97），模拟被按下去；
 * 2. **拖动**：按住拖动时玻璃跟随手指位移，但用 tanh 阻尼——越拖越「拉不动」，
 *    松手后弹簧回弹（玻璃有惰性，而不是被直接拖走）；
 * 3. 位移小于 touchSlop 视为点击，回调 [onClick]。
 *
 * 手势回调作用域（AwaitPointerEventScope）是 restricted，不能在里面跑任意
 * suspend（如 Animatable）。因此这里只做两件事：手势循环内**即时更新**
 * [dragPx] 状态（非 suspend），回弹动画放到 [LaunchedEffect]（非受限）。
 *
 * @param onClick 松手且未拖动（视为点击）时回调
 * @param enabled false 时完全静默（无按压反馈、无回调）
 */
@Composable
fun Modifier.glassReactive(
    onClick: () -> Unit,
    enabled: Boolean = true
): Modifier {
    if (!enabled) {
        // 禁用态：无交互反应（保持透明可读）
        return this
    }

    val maxDragPx = with(LocalDensity.current) { MaxDragRadius.toPx() }

    var pressed by remember { mutableStateOf(false) }
    var dragPx by remember { mutableStateOf(Offset.Zero) }

    // 回弹：pressed 由 true 转 false 时，把拖动位移弹回原位
    LaunchedEffect(pressed) {
        if (!pressed) {
            val from = dragPx
            if (from != Offset.Zero) {
                animate(
                    initialValue = from,
                    targetValue = Offset.Zero,
                    typeConverter = Offset.VectorConverter,
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                    initialVelocity = Offset.Zero
                ) { value, _ ->
                    dragPx = value
                }
            }
        }
    }

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PressScale else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "glassPress"
    )

    return this
        .graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
            translationX = dragPx.x
            translationY = dragPx.y
        }
        .pointerInput(onClick, enabled) {
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true
                val downPos = down.position
                var isTap = true

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break  // 手指抬起

                    val moved = change.position - downPos
                    if (moved.getDistance() > viewConfiguration.touchSlop) {
                        isTap = false
                    }
                    // 即时位移（tanh 阻尼），非 suspend
                    dragPx = Offset(
                        maxDragPx * tanh(moved.x / maxDragPx),
                        maxDragPx * tanh(moved.y / maxDragPx)
                    )
                    change.consume()
                }

                pressed = false
                if (isTap) onClick()
            }
        }
}

private const val PressScale = 0.97f
private val MaxDragRadius = 6.dp