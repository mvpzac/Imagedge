package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/** 空态：图标（可选）+ 标题 + 说明 + 可选操作按钮 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    desc: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        if (icon != null) {
            LucideIcon(icon, contentDescription = null, size = 32.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (desc != null) {
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.XL)
            )
        }
        if (actionLabel != null && onAction != null) {
            AppButton(
                text = actionLabel,
                onClick = onAction,
                fullWidth = false,
                modifier = Modifier.padding(top = Spacing.S)
            )
        }
    }
}

/** 导出/转码进行中：菊花 + 文案 */
@Composable
fun ProcessingView(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 结果消息：成功/失败两态 */
@Composable
fun ResultMessage(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = modifier
    )
}

/** 横幅状态条（断连/警告）：容器色底 + 文案 + 可选操作 */
@Composable
fun StatusBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(Radius.Card),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.L, vertical = Spacing.M),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        actionLabel,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 三步引导条（首页未连接时显示） */
@Composable
fun StepsGuideCard(steps: List<String>, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Radius.Card),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            steps.forEachIndexed { index, step ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(icon = Lucide.Info, size = 24.dp, iconSize = 12.dp)
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
