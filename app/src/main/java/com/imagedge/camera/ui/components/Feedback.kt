package com.imagedge.camera.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 反馈组件之骨架屏（加载用骨架屏，不用转圈；
 *              空状态/进行中/结果消息见 States.kt）
 *              - 动效只改 opacity，周期 1.5s（规范：只动 transform/opacity）
 *     version: 1.1
 * </pre>
 */

/** 骨架屏呼吸动画（1.5s 循环，只动 opacity） */
@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    return alpha
}

/** 骨架块（圆角默认取卡片档） */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(Radius.Card)
) {
    Box(
        modifier = modifier
            .alpha(skeletonAlpha())
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = shape
            )
    )
}

/** 相册网格骨架屏：列数与间距同真实网格，避免加载完成时跳动 */
@Composable
fun AlbumGridSkeleton(
    columns: Int = 3,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        userScrollEnabled = false
    ) {
        items(12) {
            SkeletonBox(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.Tag)
            )
        }
    }
}
