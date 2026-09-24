package com.imagedge.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.glass.GlassSwitch
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/**
 * 设计系统控件层（UI 规范 §6.1 决策表）。
 *
 * 为什么需要这一层：`FilterChip` / `AssistChip` / `Switch` / `Slider` / `OutlinedTextField`
 * 这类 M3 原生控件各自带一套默认尺寸、圆角与配色，页面里直接用就会出现
 * 「同一屏三种圆角、两套高度」的观感。这里把它们收敛成**一套受 token 约束的组件**，
 * 页面只允许引用这些组件（沉浸型页面的绘制原语除外）。
 */

/**
 * 选项标签（替代 `FilterChip` / `AssistChip`）。
 *
 * - 选中态用「主色描边 + 主色文字 + 淡主色底」，未选中态用表面色 + hairline 描边；
 * - 高度 40dp，但触摸目标靠 [defaultMinSize] 保证 ≥ 48dp（规范 §8.1）；
 * - 语义为 [Role.RadioButton]（互斥选项）或 [Role.Checkbox]（多选），供 TalkBack 播报。
 */
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Int? = null,
    role: Role = Role.RadioButton
) {
    val shape = RoundedCornerShape(Radius.Control)
    val alpha = if (enabled) 1f else 0.38f
    // 选中态用更深的主色淡填充表达（原先靠描边，黑线在极简配色里过重）
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(background, shape)
            .clickable(enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = Spacing.M),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor.copy(alpha = alpha)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
            ) {
                if (leadingIcon != null) {
                    LucideIcon(leadingIcon, contentDescription = null, size = 16.dp)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 一排选项标签。
 *
 * @param scrollable 选项多于 4 个（或含长文案）时传 true，改为横向滚动，避免换行挤压版面
 */
@Composable
fun <T> AppChipRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    /** 单项禁用（如 PNG 格式下"元数据策略"不可选） */
    enabled: (T) -> Boolean = { true },
) {
    if (scrollable) {
        LazyRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            contentPadding = PaddingValues(vertical = Spacing.XS)
        ) {
            items(items) { item ->
                AppChip(
                    label = label(item),
                    selected = item == selected,
                    onClick = { onSelect(item) },
                    enabled = enabled(item)
                )
            }
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        items.forEach { item ->
            AppChip(
                label = label(item),
                selected = item == selected,
                onClick = { onSelect(item) },
                enabled = enabled(item),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 参数滑条（替代裸 `Slider`）：标签 + 滑条 + 数值。
 *
 * 标签固定宽度让多个滑条纵向对齐；数值固定宽度避免数字位数变化时滑条左右跳动。
 */
@Composable
fun AppSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = -100..100,
    valueSuffix: String = "",
    /** 离散档位（0 = 连续）；如画质 60..100 每 5 一档 → steps = 7 */
    steps: Int = 0,
    /** 自定义数值文案（如时间轴显示 "12.5s"）；null = 显示原始整数值 */
    valueText: String? = null,
    /** 拖动结束回调（用于"松手才做重活"的场景，如抽帧刷新封面） */
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    // 只有取值范围含负数时才显示 "+"（画质 90 显示成 "+90" 会很怪）
    val signed = range.first < 0
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.M)
        )
        Text(
            text = valueText
                ?: if (signed && value > 0) "+$value$valueSuffix" else "$value$valueSuffix",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}

/** 文本输入（替代裸 `OutlinedTextField`）：统一圆角、单行、token 内边距 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.Control),
        modifier = modifier.fillMaxWidth()
    )
}

/** 开关行（替代裸 `Switch`）：左标签（+ 可选说明）右开关，整行 ≥ 48dp */
@Composable
fun AppSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = Spacing.M)
        )
    }
}

/**
 * 裸开关（无标签）：用于「开关 + 输入框」这类并排布局。
 * 必须传 [contentDescription]——没有可见标签时，TalkBack 只能读到"开关"。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * 行内文字动作（替代裸 `TextButton`）。
 *
 * 用于卡片尾部、状态条右侧这类「不该出现实心按钮」的位置；
 * 触摸目标同样撑到 48dp，避免因为视觉小而点不中。
 */
@Composable
fun AppLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(Radius.Tag))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.S, vertical = Spacing.XS),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else color.copy(alpha = 0.38f)
        )
    }
}

/**
 * 图标按钮（替代裸 `IconButton`）。
 *
 * 与 `PageHeader` 的返回钮同款观感：48dp 触控 + 半透明圆底 + hairline 描边。
 * **必须传 [contentDescription]**——图标按钮没有可见文字，缺了它 TalkBack 只会读"按钮"。
 *
 * [tint] 供**深色画面之上的浮层**（取景监看、大图查看）覆写图标色：默认取 `primary`，
 * 而浅色主题的 `primary` 是近黑，压在纯黑监看背景上会完全看不见。
 * 覆写不影响任何沿用默认值的既有调用点。
 */
@Composable
fun AppIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    tint: Color? = null
) {
    val iconColor = tint ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            // 只有半透明圆底、没有描边：黑线圆环在极简配色里过于抢眼
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        LucideIcon(
            lucide = icon,
            contentDescription = contentDescription,
            size = iconSize,
            tint = if (enabled) iconColor
            else iconColor.copy(alpha = 0.38f)
        )
    }
}

/** 分隔线：统一 hairline 与颜色，避免页面各自用 Divider 的不同默认值 */
@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 区块（UI 规范 §6.1）：可选标题 + 可选尾部动作 + 内容，纵向节奏固定为 12dp。
 * 页面由若干 Section 组成时，区块之间由 [AppPage] 的 16dp 间距负责。
 */
@Composable
fun AppSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                trailing?.invoke()
            }
        }
        content()
    }
}
