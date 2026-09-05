package com.imagedge.camera.feature.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.EntryCard
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.feedback.SnackbarController

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 相册中枢页——「选片集」与「整卡查看」拆为两个并列入口（默认选片集），
 *              整卡查看在传输未完成时拦截并提示；另含相册传输（下载队列）/ 相册编辑（LUT）。
 *     version: 1.1
 * </pre>
 */

@Composable
fun AlbumHubScreen(
    onOpenSelection: () -> Unit = {},
    onOpenFullCard: () -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    onOpenEdit: () -> Unit = {},
    snackbarController: SnackbarController,
    albumViewModel: AlbumViewModel = hiltViewModel()
) {
    val hasActiveDownload by albumViewModel.hasActiveDownload.collectAsStateWithLifecycle()
    val fullCardBusyHint = stringResource(R.string.hub_full_card_busy_hint)

    // 横屏可用滚动：内容超高时能上下滑（竖屏内容通常一屏放下，无滚动感觉）。
    // 底部 96dp 为悬浮玻璃导航栏让位（与 Home/Settings 的 Tab 页约定一致）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_album),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 选片集：默认入口（相机连接后默认走选片集通道）
        EntryCard(
            icon = Lucide.Images,
            title = stringResource(R.string.hub_selection_title),
            desc = stringResource(R.string.hub_selection_desc),
            onClick = onOpenSelection
        )
        // 整卡查看：手动点击才切换通道；传输未完成时拦截并提示
        EntryCard(
            icon = Lucide.HardDrive,
            title = stringResource(R.string.hub_full_card_title),
            desc = stringResource(R.string.hub_full_card_desc),
            onClick = {
                if (hasActiveDownload) {
                    snackbarController.show(fullCardBusyHint)
                } else {
                    onOpenFullCard()
                }
            }
        )
        EntryCard(
            icon = Lucide.Download,
            title = stringResource(R.string.hub_transfer_title),
            desc = stringResource(R.string.hub_transfer_desc),
            onClick = onOpenTransfer
        )
        EntryCard(
            icon = Lucide.SlidersHorizontal,
            title = stringResource(R.string.hub_edit_title),
            desc = stringResource(R.string.hub_edit_desc),
            onClick = onOpenEdit
        )
    }
}
