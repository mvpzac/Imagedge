package com.imagedge.camera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.glass.GlassLevel
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.glassSurface
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/** 按钮内容内边距：垂直 14dp 与文字行高凑约 48dp 目标高度；水平 24dp 保非全宽时不贴边 */
private val ButtonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/** 统一按钮：PRIMARY 主色实心 / SECONDARY 描边 / GHOST 文字 */
enum class AppButtonType { PRIMARY, SECONDARY, GHOST }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    type: AppButtonType = AppButtonType.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: Int? = null,
    fullWidth: Boolean = true
) {
    val shape = RoundedCornerShape(Radius.Control)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                LucideIcon(leadingIcon, contentDescription = null, size = 18.dp)
                Spacer(Modifier.width(Spacing.S))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    val baseModifier = if (fullWidth) modifier.fillMaxWidth() else modifier

    // 玻璃模式：有背景的按钮（PRIMARY / SECONDARY）改为玻璃表面 + 透明按钮本体，
    // 这样既保留 Material3 的点击涟漪、最小触摸目标与无障碍语义，
    // 又让按钮本身呈现为一块折射页面背景的玻璃。
    // GHOST 是纯文字按钮，没有可折射的「面」，保持原样。
    val backdrop = LocalGlassBackdrop.current
    val glassLevel = rememberGlassLevel()
    val useGlass = backdrop != null && glassLevel.warrantsBackdropCapture()

    when (type) {
        AppButtonType.PRIMARY -> {
            if (useGlass) {
                GlassButtonShell(
                    modifier = baseModifier,
                    backdrop = backdrop,
                    level = glassLevel,
                    shape = shape,
                    surfaceColor = MaterialTheme.colorScheme.primary
                ) {
                    Button(
                        onClick = onClick,
                        enabled = enabled,
                        shape = shape,
                        contentPadding = ButtonContentPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth()
                    ) { content() }
                }
            } else {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    shape = shape,
                    contentPadding = ButtonContentPadding,
                    modifier = baseModifier
                ) { content() }
            }
        }
        AppButtonType.SECONDARY -> {
            if (useGlass) {
                GlassButtonShell(
                    modifier = baseModifier,
                    backdrop = backdrop,
                    level = glassLevel,
                    shape = shape,
                    surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    OutlinedButton(
                        onClick = onClick,
                        enabled = enabled,
                        shape = shape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        contentPadding = ButtonContentPadding,
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth()
                    ) { content() }
                }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    enabled = enabled,
                    shape = shape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    contentPadding = ButtonContentPadding,
                    modifier = baseModifier
                ) { content() }
            }
        }
        AppButtonType.GHOST -> TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = ButtonContentPadding,
            modifier = baseModifier
        ) { content() }
    }
}

/**
 * 玻璃按钮外壳：在按钮底下铺一层玻璃，按钮本体设为透明。
 * 降级时 glassSurface 直接返回普通背景，观感与改造前一致。
 */
@Composable
private fun GlassButtonShell(
    modifier: Modifier,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    level: com.imagedge.camera.ui.glass.GlassLevel,
    shape: androidx.compose.ui.graphics.Shape,
    surfaceColor: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.glassSurface(
            backdrop = backdrop,
            level = level,
            shape = shape,
            surfaceColor = surfaceColor
        )
    ) { content() }
}
