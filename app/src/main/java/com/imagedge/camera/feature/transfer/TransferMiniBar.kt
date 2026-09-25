package com.imagedge.camera.feature.transfer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.data.transfer.TransferBarContent
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 全局传输小条（批次 C）：悬浮导航上方那条「还在传 / 传坏了」
 *     version: 1.0
 * </pre>
 */

/**
 * 传输状态的悬浮小条。
 *
 * 容器**不读数据库**（设计 §6）：状态与事件都由调用方给——`AppRoot` 从
 * [com.imagedge.camera.data.transfer.TransferMiniBarStore] 拿到已算好的内容，
 * 这里只负责表达。
 *
 * 普通不透明表面，不给玻璃：这条要说的是「有 2 项传坏了」，
 * 折射再好看也不能把警示读糊（UI 规范 §3.2 的玻璃预算）。
 *
 * **整条一个触点**：尾部那个「查看」是画出来的提示，不是第二个 clickable——
 * 一格两层触点会让用户搞不清点哪才有反应（照片网格同一坑，见 PhotoGridTile）。
 *
 * @param onClearanceChanged 上报实高（含与导航的间距），Tab 页据此给最后一行让位
 *        （与 [com.imagedge.camera.navigation.LocalNavClearance] 同一套「量出来的」约定）
 */
@Composable
fun TransferMiniBar(
    content: TransferBarContent,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onClearanceChanged: (Dp) -> Unit = {}
) {
    val density = LocalDensity.current
    val hasActive = content.activeCount > 0
    val hasFailure = content.unviewedFailureCount > 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.L)
            .clip(RoundedCornerShape(Radius.Container))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onOpen)
            .heightIn(min = UiSize.TouchMin)
            .onSizeChanged { size ->
                val height = with(density) { size.height.toDp() }
                // 条本身 + 与导航胶囊之间那道间距（间距由调用方画在条下面）。
                // 只报条的高度的话，页面留白正好被条与胶囊之间那段吃掉，最后一行贴边。
                onClearanceChanged(height + Spacing.M)
            }
            .padding(horizontal = Spacing.L, vertical = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        // 图标 + 文字双保险：只靠红色区分「在传」与「传坏」，色弱的用户读不出来
        LucideIcon(
            if (hasFailure) Lucide.TriangleAlert else Lucide.Download,
            contentDescription = null,
            size = 20.dp,
            tint = if (hasFailure) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.XS)
        ) {
            Text(
                text = when {
                    hasActive -> stringResource(R.string.transfer_bar_active, content.activeCount)
                    else -> stringResource(R.string.transfer_bar_failed, content.unviewedFailureCount)
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                // 失败必须带下一步（UI 规范 §7）：只说「失败了」等于把问题交回给用户
                text = when {
                    hasActive && hasFailure ->
                        stringResource(R.string.transfer_bar_failure_extra, content.unviewedFailureCount)
                    hasFailure -> stringResource(R.string.transfer_bar_failure_next)
                    else -> stringResource(R.string.transfer_bar_active_next)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 尾部提示：文字 + 箭头，随整条一起被点击
        Text(
            text = stringResource(R.string.transfer_bar_open),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        LucideIcon(
            Lucide.ChevronRight,
            contentDescription = null,
            size = 18.dp,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
