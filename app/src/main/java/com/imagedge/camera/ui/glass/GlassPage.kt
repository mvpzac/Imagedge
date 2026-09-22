package com.imagedge.camera.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * 玻璃背景层 —— 页面内元素能够安全使用玻璃的前提，也是玻璃效果的「素材来源」。
 *
 * 它承担两件事：
 * 1. 作为玻璃的**背景源**（`layerBackdrop` 把这一层采集进离屏图层）
 * 2. 提供可供折射的**视觉内容**
 *
 * 第 2 点是玻璃显形的关键：玻璃的观感来自背后画面的**明暗与色相变化**
 * 被模糊、被边缘折射——如果背景是一整块纯色，模糊后仍是纯色，折射也不产生
 * 任何变化，玻璃就成了一块没有质感的半透明板（早期版本正是如此）。
 *
 * 因此这里铺一层**低饱和的多色光晕**（4 团大半径径向渐变），观感类似
 * Apple 壁纸的弥散光斑：静态时柔和不刺眼，玻璃浮上去后立刻有「看穿一层
 * 有厚度的介质」的效果。光晕色相与明暗主题各自适配。
 */
@Composable
fun GlassBackdropLayer(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier
) {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    // 光晕色板：按主题取柔和低饱和色相（右上→左下逆时针分布，避免与内容抢视觉）
    val glows = if (isLight) {
        listOf(
            Glow(Color(0xFF7FB0E0), 0.34f, 0.14f, 0.46f), // 青蓝 · 左上
            Glow(Color(0xFFB79BEA), 0.32f, 0.86f, 0.36f), // 紫 · 右上
            Glow(Color(0xFFF2BE86), 0.28f, 0.72f, 1.02f), // 暖 · 底部
            Glow(Color(0xFF8ACDB4), 0.28f, 0.04f, 0.88f)  // 青绿 · 左下
        )
    } else {
        listOf(
            Glow(Color(0xFF3A5F8F), 0.62f, 0.14f, 0.46f),
            Glow(Color(0xFF5C4896), 0.58f, 0.86f, 0.36f),
            Glow(Color(0xFF8A5F2A), 0.55f, 0.72f, 1.02f),
            Glow(Color(0xFF2C6A52), 0.55f, 0.04f, 0.88f)
        )
    }
    val base = if (isLight) Color(0xFFF2F3F5) else Color(0xFF0E0F13)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                // Brushes used to be rebuilt on every draw. Cache all four radial shaders until
                // size/theme changes; this removes steady allocations from scroll/animation frames.
                val radius = size.maxDimension * 0.55f
                val prepared = glows.map { glow ->
                    val center = Offset(size.width * glow.centerX, size.height * glow.centerY)
                    PreparedGlow(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glow.color.copy(alpha = glow.alpha),
                                glow.color.copy(alpha = 0f)
                            ),
                            center = center,
                            radius = radius
                        ),
                        center = center
                    )
                }
                onDrawBehind {
                    drawRect(base)
                    prepared.forEach { glow ->
                        drawCircle(
                            brush = glow.brush,
                            radius = radius,
                            center = glow.center
                        )
                    }
                }
            }
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
    )
}

/** 一团光晕：中心色、强度、归一化中心坐标 */
private data class Glow(
    val color: Color,
    val alpha: Float,
    val centerX: Float,
    val centerY: Float
)

private data class PreparedGlow(val brush: Brush, val center: Offset)
