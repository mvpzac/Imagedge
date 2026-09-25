package com.imagedge.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 完成页结果面板（设计 §6）：结果 + 产物位置 + 后续动作
 *     version: 1.0
 * </pre>
 */

/**
 * 一条任务完成后的落点说明。
 *
 * 为什么不是 [ResultMessage]：`ResultMessage` 只有一句话，而导出成功真正要回答的是
 * 三个问题——成没成、**东西在哪**、下一步做什么。v1 的三拼完成页只写了「完成」，
 * 用户找不到文件，也接不上任何后续动作（代码注释里就记着这个缺陷）。
 *
 * 普通不透明表面：结果与路径是必须读准的信息，不赌背景模糊（UI 规范 §3.2）。
 * 成功与失败共用同一个位置与同一套图标+文字，避免「失败时整块换样式」让人以为换了页面。
 */
@Composable
fun TaskResultPanel(
    ok: Boolean,
    headline: String,
    location: String? = null,
    note: String? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Card))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            LucideIcon(
                if (ok) Lucide.CircleCheck else Lucide.CircleX,
                contentDescription = null,
                size = 22.dp,
                tint = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.XS)
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (location != null) {
                    Text(
                        text = stringResource(R.string.result_location, location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        if (primaryLabel != null && onPrimary != null) {
            AppButton(text = primaryLabel, onClick = onPrimary)
        }
        // 次级动作保持文字形态：完成页不该出现第二个实心主按钮（UI 规范 §2 原则 2）
        if (secondaryLabel != null && onSecondary != null) {
            AppLink(text = secondaryLabel, onClick = onSecondary)
        }
    }
}
