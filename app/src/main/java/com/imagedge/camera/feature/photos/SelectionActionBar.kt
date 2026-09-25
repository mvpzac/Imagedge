package com.imagedge.camera.feature.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 选择态底栏（批次 C）：数量 + 取消 + 一个主操作，占据悬浮导航的位置
 *     version: 1.0
 *     说明：设计 §4.3 要求选择时底栏位置换成这一条，避免「导航 + 任务条 + 保存按钮」三层叠加。
 *           所以调用方在选择态**不画** FloatingNavBar，两者互斥由页面负责。
 * </pre>
 */

/**
 * 选择操作栏。
 *
 * 普通不透明 surface + 一条轻分隔，不用玻璃：这是**要花钱的动作**（把文件写进系统相册），
 * 可读性不能赌背景模糊（UI 规范 §3.2）。
 *
 * 提交中按钮禁用并改文案，**队列受理后才清选择**：入队要落 Room，
 * 落库失败时如果选择已经被清掉，用户就再也找不回这批待办了。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    submitting: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = UiSize.TouchMin)
                    .padding(horizontal = Spacing.L, vertical = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.photos_selected_count, selectedCount, selectedCount
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() }
                        .weight(1f)
                )
                AppLink(
                    text = stringResource(R.string.photos_cancel_selection),
                    onClick = onCancel
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.L)
                    .padding(bottom = Spacing.L),
                verticalArrangement = Arrangement.spacedBy(Spacing.XS)
            ) {
                AppButton(
                    text = if (submitting) {
                        stringResource(R.string.photos_saving)
                    } else {
                        pluralStringResource(
                            R.plurals.photos_save_action, selectedCount, selectedCount
                        )
                    },
                    onClick = onSave,
                    // 没选东西时不是「点了没反应」，而是把原因写在按钮下面
                    enabled = selectedCount > 0 && !submitting
                )
                if (selectedCount == 0) {
                    Text(
                        text = stringResource(R.string.photos_pick_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
