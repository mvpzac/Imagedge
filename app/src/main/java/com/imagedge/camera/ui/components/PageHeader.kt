package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R

/**
 * 二级页统一标题栏。
 *
 * 为什么不用 TopAppBar：M3 TopAppBar 自带独立底色（elevation 色）与 64dp 高度，
 * 在本应用里表现为「标题区一块色 + 标题重心偏低」，与页面背景割裂。
 * 此组件：背景透明（与页面同色，无色块）、高度 statusBars+56dp（更紧凑、标题更靠上）、
 * 标题统一 headlineSmall。四个二级页（相册/下载/遥控/LUT）共用，保证完全一致。
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
            IconButton(onClick = onBack) {
                LucideIcon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.viewer_back),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}
