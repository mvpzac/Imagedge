package com.imagedge.camera.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.glass.GlassSwitch
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

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
    role: Role = Role.RadioButton,
    /**
     * 长按动作（默认没有）。给「点一下是一件事、长按是另一件事」的选项用
     * ——编辑页的预设 chip：点 = 套用，长按 = 删除。
     *
     * 必须在本组件里做成**同一个**触点，不能让调用方在外面再套一层可点容器：
     * 下面那个 `clickable` 会先吃掉 down 事件，外层的 `combinedClickable`（无论
     * onClick 还是 onLongClick）就再也收不到手势——长按静默失效，比没这个动作更糟。
     */
    onLongClick: (() -> Unit)? = null,
    /** 长按动作给读屏的自定义操作名；不给就等于 TalkBack 用户拿不到删除 */
    onLongClickLabel: String? = null,
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
    // 只有真给了长按动作才换触点：其余全部调用点（比例、分区、旋转、镜像…）
    // 走的还是原来那条 `clickable` 路径，行为与语义一字不变
    val gesture = if (onLongClick == null) {
        Modifier.clickable(enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            role = role
        )
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = UiSize.TouchMin)
            .clip(shape)
            .background(background, shape)
            .then(gesture)
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
    // 「项数多就横向滚动」原来只写在文档里：非滚动分支每项 weight(1f)，第 5 项起
    // 宽度不够就开始用 Ellipsis 截字。改成由组件自己兜底——调用方漏传 scrollable
    // 的结果是横向滚动，而不是几个被截掉一半的标签。阈值取 4 是实测的最大值：
    // 今天不传 scrollable 的调用点里项数最多的几个（导出尺寸、倒计时、编辑页标签页、
    // 照片筛选、三拼比例）都正好 4 项，所以这条分支今天不改变任何一页的观感，
    // 只是把「再加一项就会静默截字」这个坑从调用方身上挪到了组件里。
    if (scrollable || items.size > NON_SCROLLABLE_MAX_CHIPS) {
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

/** 滑条标签列与数值列共用的宽度下限。取 min 而非定宽的理由见 [AppSlider] */
private val SLIDER_COLUMN_MIN = 60.dp

/** 非滚动模式下每项的可用宽度下限：再往下列表标签就会被 Ellipsis 截断 */
private const val NON_SCROLLABLE_MAX_CHIPS = 4

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
            // **下限，不是定宽**。定宽会让系统大字模式下重新炸开：
            // DesignScaleLocked 只把 LocalDensity 乘上 1~1.35，fontScale 原样保留，
            // 而这里的文字是 sp —— 字号随 fontScale 涨，dp 容器只随 density·scale 涨，
            // 两者不同步。fontScale = 2.0 时四字标签要 ~96dp，60dp 会把它挤折成两行。
            //
            // 取 min 的代价是「比 min 宽的那一行会自己变宽，滑条随之变窄」，
            // 而同屏各行字号相同、短标签都吃 min，所以默认字体下各行仍然等宽对齐。
            // 字号真的变大时，宁可滑条窄一点也不让文字折行/截断——
            // 折成两行会把整行撑高，而这一列是等高的。
            modifier = Modifier.widthIn(min = SLIDER_COLUMN_MIN),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Slider(
            value = value.toFloat(),
            // **四舍五入，不是截断**：steps = 0 时 M3 的 Slider 是连续的，拇指能停在 12 与 13
            // 之间，而截断会把它记成 12，松手时用户看到拇指自己弹了一下。
            //
            // 别改成 `steps = range.last - range.first - 1` 去把轨道分档——试过：
            // M3 会改画轨道本身（深色药丸里再套一层浅色药丸、端点圆点消失），
            // 那比「松手弹一下」难看得多，而且它只在真机上看得出来，编译与单测都拦不住。
            onValueChange = { onValueChange(it.roundToInt()) },
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
            // 与标签列同一个理由、同一套解法，见上。定宽会让数字位数之外的**字号变化**
            // 重新把它挤爆，而这一列恰好是最容易在真机上被发现的。
            modifier = Modifier.widthIn(min = SLIDER_COLUMN_MIN),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
            .heightIn(min = UiSize.TouchMin),
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
            .defaultMinSize(minHeight = UiSize.TouchMin)
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
 * 紧凑动作按钮（替代裸 `TextButton`）。
 *
 * 用于卡片尾部、状态条右侧这类「不该出现实心大按钮」的位置；触摸目标撑到 48dp。
 *
 * **为什么它必须有自己的容器**：以前这里只有一行主色文字，而本应用的 `primary`
 * 是近黑（浅色主题）/近白（深色主题），与正文只差一个字重——用户读到的是「标签」
 * 而不是「可以按的东西」，按压反馈要等手指落下才出现。现在静止态就有一层由 [color]
 * 派生的淡色片 + 8dp 栅格上的 12dp 圆角，与 [AppButton] 同族但更轻。
 *
 * 容器**从 [color] 取色而不是从 `surfaceVariant` 取色**：这样深色画面上的浮层
 * （监看、大图、成片回看）传 `OnViewer` 时会自动得到一层提亮底，浅色卡片里传默认
 * 主色时得到淡灰底，确认框的 `error` 得到淡红底——不需要为这三种场景做三个组件。
 * 按设计语言「不加描边」的既有口径，层级只靠浓淡与字色表达；浓度刻意压在
 * `AppButton(PRIMARY)` 之下，免得行内动作跟「一屏一个主操作」抢焦点。
 */
@Composable
fun AppLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val shape = RoundedCornerShape(Radius.Control)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 这层色片是「压暗」还是「提亮」底色，取决于底是深还是浅：深色底上 10% 的近白
    // 等于没有，要提到 22% 才看得出是一块按钮。两个信号缺一不可——
    // surface 覆盖深色主题，[color] 覆盖浅色主题里压在纯黑画面上的浮层（OnViewer）。
    val liftOnDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f || color.luminance() > 0.5f
    val containerAlpha = when {
        !enabled -> 0.05f
        liftOnDark -> if (pressed) 0.30f else 0.22f
        else -> if (pressed) 0.17f else 0.10f
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = UiSize.TouchMin)
            .clip(shape)
            .background(color.copy(alpha = containerAlpha), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = Spacing.M),
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
            .size(UiSize.TouchMin)
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
