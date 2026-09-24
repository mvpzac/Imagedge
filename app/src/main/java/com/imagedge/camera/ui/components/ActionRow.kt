package com.imagedge.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

/**
 * 普通表面的列表行（批次 A）。
 *
 * 为什么在 [EntryCard] 之外再加一个：EntryCard 自带玻璃折射，于是「每一个入口」都长成
 * 重点操作，一屏四五个玻璃块互相抢注意力。本组件是**不透明普通表面**，
 * 用于设置分组、创作工具列表这类平级条目（UI 规范 §3.2 的玻璃预算）。
 *
 * [EntryCard] 保持原样不动：改它的默认表面会牵动全项目所有调用点。
 *
 * 禁用必须给原因：一个变灰的行只表达「现在点不动」，不表达「为什么」和「怎么办」，
 * 用户只能猜。[disabledReason] 就是为此存在，而不是可选的装饰。
 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    description: String? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(Radius.Card)
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = UiSize.RowMin)
            .padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        if (icon != null) {
            IconBadge(
                icon = icon,
                size = 36.dp,
                iconSize = 18.dp,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val supporting = description ?: if (!enabled) disabledReason else null
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
        }
        val slot = trailing
        if (slot != null) {
            slot(this)
        } else {
            LucideIcon(
                Lucide.ChevronRight,
                contentDescription = null,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        }
    }
}

/**
 * 设置行：标题 + **当前值** + 进入详情。
 *
 * 常用设置必须直接显示当前值（如「保存位置 / DCIM/Imagedge」）——
 * 要点进下一层才知道现在是什么，等于把状态藏起来了。
 */
@Composable
fun SettingsRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    description: String? = null,
    enabled: Boolean = true,
    disabledReason: String? = null
) {
    ActionRow(
        title = title,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        description = description ?: value,
        enabled = enabled,
        disabledReason = disabledReason
    )
}

/** 分组标题：普通页面上的一条小标题，不带任何表面 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier, icon: Int? = null) {
    Row(
        modifier = modifier.padding(top = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        if (icon != null) {
            LucideIcon(icon, contentDescription = null, size = 16.dp)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 禁用态内容不透明度，与 AppButton / AppChip / AppLink 同一档 */
private const val DISABLED_ALPHA = 0.38f
