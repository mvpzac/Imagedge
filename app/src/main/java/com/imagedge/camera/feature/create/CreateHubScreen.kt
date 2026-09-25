package com.imagedge.camera.feature.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.EntryCard
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.navigation.LocalNavClearance
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.SmileySansFamily
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-30
 *     desc   : 创作首页——四个工具入口平级；离线可用，素材都从本机选
 *     version: 1.2
 * </pre>
 */

/**
 * 创作 Tab 首页（设计 §4.7）。
 *
 * 标题就是 Tab 名「创作」——原来这里写的是「相册编辑」，而底栏那个 Tab 叫「创作」，
 * 同一个地方两个名字。副标题负责说清这一屏不需要相机：素材从手机里选就能开始。
 */
@Composable
fun CreateHubScreen(
    onOpenLivePhoto: () -> Unit = {},
    onOpenEdit: () -> Unit = {},
    onOpenTriptych: () -> Unit = {},
    onOpenExifFrame: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    AppScreenFrame(
        topBar = {
            AppPageHeader(
                title = stringResource(R.string.tab_create),
                onBack = onBack,
                large = true
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 最后一行要能完整停在悬浮导航上方：让位量实测下发，写死会在大字模式下挡住入口
                .padding(bottom = LocalNavClearance.current)
                .padding(horizontal = Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.L)
        ) {
            Text(
                text = stringResource(R.string.create_subtitle),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = SmileySansFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                title = stringResource(R.string.edit_hub_triptych_title),
                desc = stringResource(R.string.edit_hub_triptych_desc),
                onClick = onOpenTriptych
            )
            EntryCard(
                icon = Lucide.Camera,
                title = stringResource(R.string.edit_hub_frame_title),
                desc = stringResource(R.string.edit_hub_frame_desc),
                onClick = onOpenExifFrame
            )
        }
    }
}
