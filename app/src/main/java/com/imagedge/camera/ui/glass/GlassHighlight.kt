package com.imagedge.camera.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asComposeShader
import com.kyant.backdrop.isRuntimeShaderSupported

/**
 * 按压高光（specular highlight）——**高光跟着手指走**。
 *
 * 观感来源：真实玻璃在手指按压处会发生镜面反射，形成一团随手指移动、松手淡出的亮斑。
 * 这是液态玻璃区别于「半透明板」最直观的一个细节——只有折射与模糊时，按压反馈仍然
 * 只体现在缩放上（我们此前就是这样）。
 *
 * 实现移植自上游示例的 `InteractiveHighlight`
 * （Kyant0/AndroidLiquidGlass，Apache-2.0），做了三处适配：
 * 1. 状态从「自带一个 CoroutineScope + 手势」改为**由 [glassReactive] 驱动**
 *    （手势已经在那里处理，重复注册会互相抢事件）；
 * 2. 只在玻璃可用（[GlassLevel] != NONE）时绘制：降级设备上玻璃本身已退回普通表面，
 *    再叠一层白光会显得突兀；
 * 3. 拖动时直接更新绘制坐标，松手只做透明度动画，避免为每个指针采样启动协程。
 *
 * 技术细节：AGSL（`RuntimeShader`）需要 Android 13+；更低版本退回
 * 一层均匀的白光叠加（观感弱一些但不会缺失反馈）。
 */
internal class GlassHighlightState {

    /** 按压进度 0..1：驱动高光强度 */
    val progress = Animatable(0f, 0.001f)

    /** 光点位置只失效绘制层；不作为 LaunchedEffect key 触发每帧重组/协程。 */
    var position by mutableStateOf(Offset.Zero)
        private set

    private val pressSpec = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)

    /**
     * 高光着色器：以光点为中心画一团径向渐隐的白光。
     * `smoothstep(radius, radius * 0.5, dist)` 表示「越靠近中心越亮」——
     * 注意 smoothstep 的两个边界是反的（从 radius 到 radius*0.5），
     * 这样内圈为 1、外圈为 0，得到柔和的圆形亮斑。
     */
    private val shader: RuntimeShader? = if (isRuntimeShaderSupported()) {
        RuntimeShader(
            """
            uniform float2 size;
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;

            half4 main(float2 coord) {
                float dist = distance(coord, position);
                float intensity = smoothstep(radius, radius * 0.5, dist);
                return color * intensity;
            }
            """
        )
    } else {
        null
    }

    private val shaderBrush: ShaderBrush? = shader?.let { ShaderBrush(it.asComposeShader()) }

    /** 光点吸附到手指位置（拖动中即时跟随，不做动画——动画会让高光「拖后腿」） */
    fun follow(point: Offset) { position = point }

    /** 按下：高光淡入到按下点 */
    suspend fun press(at: Offset) {
        position = at
        progress.animateTo(1f, pressSpec)
    }

    /** 松手：只淡出。省去一个与视觉收益不成比例的位置动画。 */
    suspend fun release() {
        progress.animateTo(0f, pressSpec)
    }

    /**
     * 绘制层：先画高光，再画内容（内容因此压在高光之上，文字不会被光斑糊掉）。
     * 必须挂在玻璃**内侧**（`glassSurface` 之后），否则高光会被玻璃的模糊盖住。
     */
    fun Modifier.highlightLayer(intensity: Float = 1f): Modifier = drawWithContent {
        val p = progress.value
        if (p > 0.01f) {
            val light = position
            if (shader != null && shaderBrush != null) {
                // 整体提亮一档：模拟玻璃表面被照亮的漫反射
                drawRect(Color.White.copy(alpha = 0.18f * intensity * p), blendMode = BlendMode.Plus)
                shader.apply {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(alpha = 0.34f * intensity * p))
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform(
                        "position",
                        light.x.coerceIn(0f, size.width),
                        light.y.coerceIn(0f, size.height)
                    )
                }
                drawRect(shaderBrush, blendMode = BlendMode.Plus)
            } else {
                // Android 13 以下没有 AGSL：退回均匀白光，压力反馈仍在
                drawRect(Color.White.copy(alpha = 0.25f * intensity * p), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }
}

/**
 * 高光强度随主题表面明暗调整。
 *
 * 浅色主题的表面接近白色，白色加法混合的可辨识度天然更低（白 + 白 = 白），
 * 所以浅色下需要更高强度；深色主题上同样的 alpha 会亮得刺眼。
 * 这两个数是**观感调节旋钮**，改这里即可全局生效。
 */
@Composable
internal fun highlightIntensity(): Float =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 1f else 0.55f

@Composable
internal fun rememberGlassHighlight(): GlassHighlightState = remember { GlassHighlightState() }

/**
 * 只跟踪按压、**不消费事件**的高光修饰符。
 *
 * 给「点击由自己处理」的玻璃元素用（典型是 `EntryCard`：卡片用 M3 `Surface(onClick)`
 * 提供涟漪与无障碍语义，如果我们再装一个会消费事件的手势处理器，两者的点击会打架）。
 * 这里在 [PointerEventPass.Initial] 阶段旁听事件、不调用 `consume()`，
 * 因此 Surface 的手势完全不受影响。
 */
@Composable
internal fun Modifier.glassPressTracking(state: GlassHighlightState): Modifier {
    if (!LocalGlassLevel.current.warrantsBackdropCapture()) return this

    var pressed by remember { mutableStateOf(false) }
    var pressPoint by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(pressed) {
        if (pressed) state.press(pressPoint) else state.release()
    }

    val intensity = highlightIntensity()
    return this
        .then(with(state) { Modifier.highlightLayer(intensity) })
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed && !pressed) {
                        pressed = true
                        pressPoint = change.position
                        state.follow(change.position)
                    } else if (change.pressed) {
                        state.follow(change.position)
                    } else if (pressed) {
                        pressed = false
                    }
                }
            }
        }
}
