package com.imagedge.camera.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * 玻璃背景层 —— 页面内元素能够安全使用玻璃的前提。
 *
 * 它同时承担两件事：
 * 1. 作为玻璃的**背景源**（`layerBackdrop` 把这一层采集进离屏图层）
 * 2. 提供可供折射的**视觉内容**
 *
 * 第 2 点常被忽略：纯色被模糊后仍是纯色，折射也不会产生明暗变化，
 * 玻璃就成了一块没有质感的半透明板。因此这里铺一层极克制的
 * 中性渐变——用主题自身的 surface 色阶，不引入任何彩色，
 * 静态看几乎察觉不到，但玻璃折射时能拉出明暗层次。
 *
 * @param backdrop 背景源；传 null 表示不启用玻璃，退化为普通纯色背景
 */
@Composable
fun GlassBackdropLayer(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    topColor: Color = MaterialTheme.colorScheme.surface,
    bottomColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val gradient = Brush.linearGradient(
        colorStops = arrayOf(
            0f to topColor,
            0.55f to topColor,
            1f to bottomColor
        ),
        start = Offset.Zero,
        end = Offset(0f, Float.POSITIVE_INFINITY),
        tileMode = TileMode.Clamp
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (backdrop != null) gradient else Brush.linearGradient(listOf(topColor, topColor)))
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
    )
}
