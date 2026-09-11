package com.imagedge.camera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.glass.LocalGlassLevel
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.glassReactive
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.glass.GlassProfile
import com.imagedge.camera.ui.glass.glassSurface

/** 按钮内容内边距：垂直 14dp 与文字行高凑约 48dp 目标高度；水平 24dp 保非全宽时不贴边 */
private val ButtonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/** 统一按钮：PRIMARY 强调 / SECONDARY 次级 / GHOST 文字 */
enum class AppButtonType { PRIMARY, SECONDARY, GHOST }

/** 按钮内容对齐：CENTER = 单行居中；START = 图标 + 左对齐双行 + 尾部箭头（大号行动按钮） */
enum class AppButtonAlign { CENTER, START }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: AppButtonType = AppButtonType.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: Int? = null,
    trailingIcon: Int? = null,
    /** 第二行说明文字：传入后按钮变为「标题 + 说明」双行布局 */
    subtitle: String? = null,
    align: AppButtonAlign = AppButtonAlign.CENTER,
    fullWidth: Boolean = true,
    // 自定义内容槽：传入后替代默认的「图标 + 单行文字」布局
    //（仅用于极特殊排版；常规双行/带箭头请用 subtitle + align + trailingIcon）
    content: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(Radius.Control)
    val backdrop = LocalGlassBackdrop.current
    val glassLevel = LocalGlassLevel.current
    val useGlass = backdrop != null && glassLevel.warrantsBackdropCapture()

    /**
     * 默认内容：三种形态由参数决定，不再需要调用方自己拼 Column
     * 1. 单行居中（默认）：[图标] 文字
     * 2. 双行居中：标题 + 说明，用于主页大按钮
     * 3. 双行左对齐（align = START）：左侧图标 + 中间双行 + 右侧箭头，用于列表式行动项
     */
    val defaultContent: @Composable () -> Unit = {
        val titleStyle =
            if (subtitle != null) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.labelLarge
        val subtitleColor = LocalContentColor.current.copy(alpha = 0.72f)
        if (align == AppButtonAlign.START) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                if (leadingIcon != null) {
                    LucideIcon(leadingIcon, contentDescription = null, size = 22.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text, style = titleStyle)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = subtitleColor)
                    }
                }
                if (trailingIcon != null) {
                    LucideIcon(trailingIcon, contentDescription = null, size = 18.dp)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (leadingIcon != null) {
                        LucideIcon(leadingIcon, contentDescription = null, size = 20.dp)
                        Spacer(Modifier.width(Spacing.S))
                    }
                    Text(text, style = titleStyle)
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(Spacing.S))
                        LucideIcon(trailingIcon, contentDescription = null, size = 18.dp)
                    }
                }
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = subtitleColor)
                }
            }
        }
    }
    val body: @Composable () -> Unit = { (content ?: defaultContent)() }
    val baseModifier = if (fullWidth) modifier.fillMaxWidth() else modifier

    // ===== 玻璃路径 =====
    // 历史坑：早期直接在 Material3 `Button` 上叠 drawBackdrop 会渲染成黑色实心块
    //（M3 容器自带的 elevation 底色与离屏图层冲突），当时的兜底是「透明背景 + 描边」的
    // 伪玻璃——**没有模糊也没有折射**，观感与真玻璃差别很大。
    // 现在按钮本身已经是 `Box` 实现（不是 M3 Button），可以安全走真 glassSurface：
    // 小尺寸档位（更轻的模糊 + 更小的折射带），既保住可读性又有玻璃质感。
    if (useGlass && type != AppButtonType.GHOST) {
        // 不加任何描边（黑线描边会把极简黑白界面切碎，也与液态玻璃的语言冲突）。
        // 主/次的层级改由**玻璃浓淡 + 文字色**表达：
        //   主 = 更浓的玻璃（表面色取 onSurfaceVariant，12% 罩色后是一层浅灰烟熏）
        //   次 = 与相册页卡片同色的清透玻璃（surface）
        // 两种都不含深色填充，深浅主题下都成立（深色主题里"更浓"表现为更亮）。
        val textColor: Color
        val tintColor: Color
        when (type) {
            AppButtonType.PRIMARY -> {
                textColor = MaterialTheme.colorScheme.primary
                tintColor = MaterialTheme.colorScheme.onSurfaceVariant
            }
            AppButtonType.SECONDARY -> {
                textColor = MaterialTheme.colorScheme.onSurface
                tintColor = MaterialTheme.colorScheme.surface
            }
            else -> return@AppButton
        }
        Box(
            modifier = baseModifier
                .clip(shape)
                .glassSurface(
                    backdrop = backdrop,
                    level = glassLevel,
                    shape = shape,
                    profile = GlassProfile.SMALL,
                    surfaceColor = tintColor
                )
                .glassReactive(onClick = onClick, enabled = enabled)
                .padding(ButtonContentPadding),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides textColor) {
                body()
            }
        }
        return
    }

    // ===== 降级 / GHOST 路径 =====
    when (type) {
        AppButtonType.GHOST -> TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = baseModifier
        ) { body() }
        AppButtonType.PRIMARY -> Box(
            modifier = baseModifier
                .background(MaterialTheme.colorScheme.primary, shape)
                .clickable(enabled = enabled) { onClick() }
                .padding(ButtonContentPadding),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
                body()
            }
        }
        AppButtonType.SECONDARY -> Box(
            modifier = baseModifier
                // 降级路径同样不描边：用一档中性填充与主按钮区分
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .clickable(enabled = enabled) { onClick() }
                .padding(ButtonContentPadding),
            contentAlignment = Alignment.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                body()
            }
        }
    }
}
