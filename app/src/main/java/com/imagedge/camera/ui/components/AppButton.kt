package com.imagedge.camera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

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
    val iconTint = when (type) {
        AppButtonType.PRIMARY -> MaterialTheme.colorScheme.onPrimary
        AppButtonType.SECONDARY -> MaterialTheme.colorScheme.primary
        AppButtonType.GHOST -> MaterialTheme.colorScheme.primary
    }
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                LucideIcon(leadingIcon, contentDescription = null, size = 18.dp, tint = iconTint)
                Spacer(Modifier.width(Spacing.S))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    val baseModifier = if (fullWidth) modifier.fillMaxWidth() else modifier
    when (type) {
        AppButtonType.PRIMARY -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = baseModifier
        ) { content() }
        AppButtonType.SECONDARY -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = baseModifier
        ) { content() }
        AppButtonType.GHOST -> TextButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            modifier = baseModifier
        ) { content() }
    }
}
