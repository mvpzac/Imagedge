package com.imagedge.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.glass.glassReactive
import com.imagedge.camera.ui.theme.Spacing

/**
 * 标题栏返回钮。
 *
 * 新旧两套标题栏共用同一颗按钮：`AppPageHeader`（批次 A 的新骨架）不再自己吃系统边距，
 * 但观感必须与旧页面完全一致——两处各写一份的话，圆底透明度迟早会对不上。
 *
 * **48dp 触控目标**（UI 规范 §8.1），内部视觉 36dp：视觉缩小但触摸区撑满，
 * 避免「看得见点不中」。
 */
@Composable
fun HeaderBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(start = Spacing.S)
            .size(48.dp)
            .clip(CircleShape)
            .glassReactive(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                // 只有半透明圆底、没有描边（与 AppIconButton 共用同一套观感）
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            LucideIcon(
                Lucide.ArrowLeft,
                contentDescription = stringResource(R.string.viewer_back),
                size = 20.dp,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
