package com.imagedge.camera.feature.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.AppPage
import com.imagedge.camera.ui.components.EntryCard
import com.imagedge.camera.ui.components.Lucide

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 相册编辑中枢页——编辑调节（几何+调色）与各专项工具平级入口
 *     version: 1.1
 * </pre>
 */

@Composable
fun EditHubScreen(
    onOpenLivePhoto: () -> Unit = {},
    onOpenEdit: () -> Unit = {},
    onOpenTriptych: () -> Unit = {},
    onOpenExifFrame: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    // 页面骨架统一走 AppPage（边距 16dp、可滚动、避让导航栏），与其它工具页一致
    AppPage(title = stringResource(R.string.edit_title), onBack = onBack) {
        EntryCard(
            icon = Lucide.ArrowLeftRight,
            title = stringResource(R.string.livephoto_title),
            desc = stringResource(R.string.edit_hub_livephoto_desc),
            onClick = onOpenLivePhoto
        )
        EntryCard(
            icon = Lucide.Sparkles,
            title = stringResource(R.string.edit_photo_title),
            desc = stringResource(R.string.edit_hub_edit_desc),
            onClick = onOpenEdit
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
