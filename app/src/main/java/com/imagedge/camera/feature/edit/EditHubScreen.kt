package com.imagedge.camera.feature.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.EntryCard
import com.imagedge.camera.ui.components.Lucide
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
                .verticalScroll(rememberScrollState())
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
