package com.imagedge.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.glassSurface
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.theme.PillShape
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/** 圆形图标衬底（统一 12% 主色透明度，替代散落各页的手写衬底） */
@Composable
fun IconBadge(
    icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.12f), shape = PillShape),
        contentAlignment = Alignment.Center
    ) {
        LucideIcon(icon, contentDescription = contentDescription, size = iconSize, tint = tint)
    }
}

/** 中枢入口卡：图标衬底 + 标题 + 副标题 + chevron（相册/编辑中枢与设置页共用） */
@Composable
fun EntryCard(
    icon: Int,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    // 玻璃底层 + 透明 Surface：Surface 保留点击涟漪与内容色语义，玻璃只负责折射。
    //
    // 前提：背景源由页面的 GlassBackdropLayer 提供，卡片**不在**该图层采集范围内
    // （若卡片被采集就会形成自引用 —— 渲染递归 → RenderThread 栈溢出，真机实测过）。
    // 降级（不支持/省电/低内存）时 glassSurface 直接返回普通背景，观感与原来一致。
    val shape = RoundedCornerShape(Radius.Card)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop = LocalGlassBackdrop.current,
                level = rememberGlassLevel(),
                shape = shape,
                surfaceColor = MaterialTheme.colorScheme.surface
            )
    ) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth()
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            IconBadge(icon = icon, tint = iconTint)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LucideIcon(
                Lucide.ChevronRight,
                contentDescription = null,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }
    }
}
