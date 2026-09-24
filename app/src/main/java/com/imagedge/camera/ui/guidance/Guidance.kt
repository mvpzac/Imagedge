package com.imagedge.camera.ui.guidance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 引导呈现组件（批次 A）：GuideCard / ContextHint / HelpSheet
 *     version: 1.0
 * </pre>
 */

/**
 * 一条引导的内容。
 *
 * 文案由调用方从资源里取好再传进来：组件不认识相机、权限和会话，
 * 也不该为了拼一句提示去查它们。
 *
 * @param id 引导标识，**含版本号**（如 `camera-selection-v1`）。文案改版就换版本号，
 *   旧的「已关闭」才不会继续压制新说明
 */
data class GuideContent(
    val id: String,
    val locationLabel: String,
    val title: String,
    val body: String,
    val actionLabel: String?
)

/**
 * 引导卡：第一次需要完成的步骤。
 *
 * 用**不透明普通表面**，不用玻璃——指导文字是用户当下必须执行的信息，
 * 把可读性寄托在背景模糊上等于赌设备性能（UI 规范 §3.2 的玻璃预算）。
 *
 * 同一屏最多一张展开的引导卡；它不与内容空态重复说同一句话。
 */
@Composable
fun GuideCard(
    guide: GuideContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.Card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.S)
        ) {
            Text(
                text = guide.locationLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = guide.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = guide.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            guide.actionLabel?.let { label ->
                AppLink(text = label, onClick = onAction)
            }
        }
    }
}

/**
 * 情境提示：持续影响当前操作的事实，情境成立期间常驻。
 *
 * 与引导卡的区别是生命周期：引导卡做完一次就该收起，情境提示跟着状态一直挂着。
 * 典型例子是「只显示你在相机上选择发送的照片」——它不是教程，是范围说明。
 */
@Composable
fun ContextHint(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * 帮助面板：复杂步骤，用户主动打开，关闭回到原位置。
 *
 * 按机型列出最多 3–5 步——超过这个数说明这一步该拆成两个动作，而不是继续加行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(
    title: String,
    steps: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.L)
                .padding(bottom = Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            steps.forEachIndexed { index, step ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.M),
                    verticalAlignment = Alignment.Top
                ) {
                    LucideIcon(
                        lucide = Lucide.Info,
                        contentDescription = null,
                        size = 16.dp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
