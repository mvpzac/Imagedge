package com.imagedge.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * 二级页统一标题栏。
 *
 * 为什么不用 TopAppBar：M3 TopAppBar 自带独立底色（elevation 色）与 64dp 高度，
 * 在本应用里表现为「标题区一块色 + 标题重心偏低」，与页面背景割裂。
 * 此组件：背景透明（与页面同色，无色块）、高度 statusBars+56dp（更紧凑、标题更靠上）、
 * 标题统一 headlineSmall。所有二级/三级页共用，保证完全一致。
 *
 * **返回钮**：液态玻璃质感——半透明圆底 + hairline 描边 + 主色箭头，
 * 像一块悬浮的小玻璃；玻璃不可用（低版本/省电）时同样观感（半透明不依赖背景内容）。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            HeaderBackButton(onClick = onBack)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

/**
 * 标题栏返回钮。
 *
 * 抽出来是为了让新旧两套标题栏共用同一颗按钮：`AppPageHeader`（批次 A 的新骨架）
 * 不再自己吃系统边距，但观感必须与这里完全一致——两处各写一份的话，
 * 圆底透明度迟早会对不上。
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
