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
    // ⚠️ 不做玻璃化（真机 Redmi/Android16 实测）：EntryCard 位于 NavHost 的
    // layerBackdrop 采集范围内部，drawBackdrop 引用祖先图层的同时又被该图层
    // 采集，形成渲染递归 → RenderThread 栈溢出（SIGSEGV）。
    // 导航栏（Scaffold.bottomBar）不在采集范围内，所以玻璃安全；
    // 页面内元素要做玻璃，必须先把页面拆成「背景层 + 玻璃控件层」。
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.Card),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
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
