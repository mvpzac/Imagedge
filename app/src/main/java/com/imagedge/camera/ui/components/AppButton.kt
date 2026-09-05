package com.imagedge.camera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/** 按钮内容内边距：垂直 14dp 与文字行高凑约 48dp 目标高度；水平 24dp 保非全宽时不贴边 */
private val ButtonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/** 统一按钮：PRIMARY 强调 / SECONDARY 次级 / GHOST 文字 */
enum class AppButtonType { PRIMARY, SECONDARY, GHOST }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: AppButtonType = AppButtonType.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: Int? = null,
    fullWidth: Boolean = true,
    // 自定义内容槽：传入后替代默认的「图标 + 单行文字」布局
    //（供 HomeBigButton 这类需要双行/富内容的按钮复用同一套玻璃样式）
    content: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(Radius.Control)
    val backdrop = LocalGlassBackdrop.current
    val glassLevel = rememberGlassLevel()
    val useGlass = backdrop != null && glassLevel.warrantsBackdropCapture()

    val defaultContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                LucideIcon(leadingIcon, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(Spacing.S))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    val body: @Composable () -> Unit = { (content ?: defaultContent)() }
    val baseModifier = if (fullWidth) modifier.fillMaxWidth() else modifier

    // ===== 玻璃路径 =====
    // 不用 drawBackdrop：在我们的双背景源架构下，按钮位置 backdrop.graphicsLayer
    // 在某些路径上为空（具体根因未深挖），drawBackdrop 会渲染成全黑——
    // 完全背离「液态玻璃」的初衷。
    // 改用半透明背景 + 描边的"伪玻璃"实现：浅色半透明表面透出背后光晕，
    // 描边模拟玻璃边缘。**不是真玻璃**（无 blur/lens），但绝对不是黑色大块，
    // 且观感与背景光晕一致。EntryCard、GlassCard 等大尺寸玻璃容器
    // 仍用真 glassSurface（它们工作正常）。
    if (useGlass && type != AppButtonType.GHOST) {
        val bg: Color
        val textColor: Color
        val border: BorderStroke?
        when (type) {
            AppButtonType.PRIMARY -> {
                // 透明背景 + 主色描边 + 主色文字：液态玻璃按钮不靠填充色，
// 透明透出背后光晕，描边和文字颜色提供强调。这绝对不是黑色。
                bg = Color.Transparent
                textColor = MaterialTheme.colorScheme.primary
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            }
            AppButtonType.SECONDARY -> {
                bg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
                textColor = MaterialTheme.colorScheme.onSurface
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            }
            else -> return@AppButton
        }
        Box(
            modifier = baseModifier
                .clip(shape)
                .background(bg, shape)
                .border(border.width, border.brush, shape)
                .clickable(enabled = enabled) { onClick() }
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
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
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