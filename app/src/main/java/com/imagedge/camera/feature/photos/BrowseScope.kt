package com.imagedge.camera.feature.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 浏览范围行与范围面板（批次 C）：范围是业务动作，不是筛选芯片
 *     version: 1.0
 * </pre>
 */

/**
 * 标题下的范围行：说清楚「现在列出来的是哪一批」，点击打开 [BrowseScopeSheet]。
 *
 * 为什么不给它一枚筛选芯片（设计 §4.3）：芯片在同一排里意味着「换个显示方式」，
 * 而选片集 ↔ 整卡是切 PTP 功能模式（0x9210），会让既有对象句柄失效——
 * 那是业务动作，长得像筛选就会让人以为点了没代价。
 */
@Composable
fun BrowseScopeRow(
    scopeLabel: String,
    note: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Tag))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Text(
            text = scopeLabel,
            style = MaterialTheme.typography.titleSmall
        )
        LucideIcon(
            lucide = Lucide.ChevronDown,
            contentDescription = stringResource(R.string.photos_scope_open_hint),
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 范围面板。
 *
 * 传输进行中**仍然可以打开**——用户有权知道现在为什么不能切；
 * 但两个选项都禁用并就地写明原因（设计 §4.3「传输中保持可打开面板查看说明，切换项禁用」），
 * 而不是等用户点下去再弹一条 2.5 秒的 toast。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScopeSheet(
    current: BrowseMode,
    switchBlocked: Boolean,
    blockedReason: String,
    onSelect: (BrowseMode) -> Unit,
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
                .padding(horizontal = Spacing.L)
                .padding(bottom = Spacing.XXL),
            verticalArrangement = Arrangement.spacedBy(Spacing.S)
        ) {
            Text(
                text = stringResource(R.string.photos_scope_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.photos_scope_sheet_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ScopeOption(
                label = stringResource(R.string.photos_scope_selection),
                note = stringResource(R.string.photos_scope_selection_note),
                selected = current == BrowseMode.SELECTION,
                enabled = !switchBlocked,
                blockedReason = blockedReason,
                onClick = { onSelect(BrowseMode.SELECTION) }
            )
            ScopeOption(
                label = stringResource(R.string.photos_scope_full_card),
                // 不写「支持/不支持」：这条通道能不能完整枚举本机型没有实测证据，
                // 切过去失败时维持原范围并说明，比现在宣称结论诚实
                note = stringResource(R.string.photos_scope_full_card_note),
                selected = current == BrowseMode.FULL_CARD,
                enabled = !switchBlocked,
                blockedReason = blockedReason,
                onClick = { onSelect(BrowseMode.FULL_CARD) }
            )
        }
    }
}

@Composable
private fun ScopeOption(
    label: String,
    note: String,
    selected: Boolean,
    enabled: Boolean,
    blockedReason: String,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Card))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f * alpha)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f * alpha),
                RoundedCornerShape(Radius.Card)
            )
            .clickable(
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(Spacing.L),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                LucideIcon(
                    lucide = Lucide.Check,
                    contentDescription = null,
                    size = 18.dp,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                )
            }
        }
        Text(
            text = if (enabled) note else blockedReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
        )
    }
}

/** 禁用态与 AppButton / ActionRow 同一档，不各写一个数 */
private const val DISABLED_ALPHA = 0.38f
