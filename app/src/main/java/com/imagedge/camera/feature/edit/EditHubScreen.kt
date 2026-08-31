package com.imagedge.camera.feature.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.PageHeader

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 相册编辑中枢页——「视频转 LIVE 图」与「LUT 滤镜」两个工具平级入口
 *     version: 1.0
 * </pre>
 */

@Composable
fun EditHubScreen(
    onOpenLivePhoto: () -> Unit = {},
    onOpenLut: () -> Unit = {},
    onOpenTriptych: () -> Unit = {},
    onOpenExifFrame: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(R.string.edit_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EntryCard(
                icon = Lucide.ArrowLeftRight,
                title = stringResource(R.string.livephoto_title),
                desc = stringResource(R.string.edit_hub_livephoto_desc),
                onClick = onOpenLivePhoto
            )
            EntryCard(
                icon = Lucide.Sparkles,
                title = stringResource(R.string.edit_lut_title),
                desc = stringResource(R.string.edit_hub_lut_desc),
                onClick = onOpenLut
            )
            EntryCard(
                icon = Lucide.Images,
                title = "LIVE 图三拼",
                desc = "3 张横屏实况图纵向无缝拼接为一张 9:16 LIVE 图，每段声音独立可控",
                onClick = onOpenTriptych
            )
            EntryCard(
                icon = Lucide.Camera,
                title = "边框水印",
                desc = "品牌 LOGO + 相机参数（型号/焦距/快门/ISO）信息边框，EXIF 自动读取可手动修正",
                onClick = onOpenExifFrame
            )
        }
    }
}

@Composable
private fun EntryCard(icon: Int, title: String, desc: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                LucideIcon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClick) {
                LucideIcon(
                    Lucide.ChevronRight,
                    contentDescription = stringResource(R.string.remote_entry_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
