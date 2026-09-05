package com.imagedge.camera.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.imagedge.camera.ui.theme.Radius

/**
 * 玻璃卡片：Material3 `Card` 的液态玻璃替代品。
 *
 * 用法与 Card 一致（支持可选 `onClick` 整卡点击）；
 * 观感为通透玻璃（折射背景光晕）而非 Card 的不透明容器。
 * 玻璃底层之上保留一层透明 Surface，用于承接 `LocalContentColor`
 *（与 Card 的内容色语义一致）。
 *
 * 降级时 glassSurface 退回普通表面，观感与 Card 接近。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(Radius.Card)
    Box(
        modifier = modifier
            .glassSurface(
                backdrop = LocalGlassBackdrop.current,
                level = rememberGlassLevel(),
                shape = shape,
                surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = contentColor,
            shape = shape,
            modifier = Modifier.matchParentSize()
        ) {
            content()
        }
    }
}
