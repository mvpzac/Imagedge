package com.imagedge.camera.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
            Glow(Color(0xFF8FB8DE), 0.22f, 0.16f, 0.5f),  // 青蓝 · 左上
            Glow(Color(0xFFC3B0EC), 0.20f, 0.84f, 0.4f),  // 紫 · 右上
            Glow(Color(0xFFF0C79B), 0.18f, 0.72f, 1.0f),  // 暖 · 底部
            Glow(Color(0xFF9ED0BC), 0.18f, 0.06f, 0.86f)  // 青绿 · 左下
        )
    } else {
        listOf(
            Glow(Color(0xFF31507A), 0.55f, 0.16f, 0.5f),
            Glow(Color(0xFF514085), 0.50f, 0.84f, 0.4f),
            Glow(Color(0xFF7A5426), 0.48f, 0.72f, 1.0f),
            Glow(Color(0xFF285A46), 0.48f, 0.06f, 0.86f)
        )
    }
    val base = if (isLight) Color(0xFFF2F3F5) else Color(0xFF0E0F13)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(base)
                glows.forEach { it.draw(this) }
            }
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
    )
}

/** 一团光晕：中心色、强度、归一化中心坐标 */
private class Glow(
    val color: Color,
    val alpha: Float,
    val centerX: Float,
    val centerY: Float
) {
    fun draw(scope: DrawScope) {
        val w = scope.size.width
        val h = scope.size.height
        val center = Offset(w * centerX, h * centerY)
        // 半径取最长边的 ~65%，保证光晕边缘始终超出屏幕（弥散到底，无硬边）
        val radius = scope.size.maxDimension * 0.65f
        scope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}
