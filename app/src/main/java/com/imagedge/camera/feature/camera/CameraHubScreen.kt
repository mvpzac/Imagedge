package com.imagedge.camera.feature.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.navigation.LocalNavClearance
import com.imagedge.camera.ui.components.ActionRow
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.guidance.HelpSheet
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.SmileySansFamily
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.domain.camera.ConnectPurpose
import com.imagedge.camera.feature.connection.ConnectionViewModel

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 相机工作台（批次 B）：状态卡 → 两个同级任务入口 → 次级连接入口 → 引导卡
 *     version: 2.0
 * </pre>
 */

/** 引导卡标识。文案改版就把 v1 换掉，否则旧的「已知道了」会一直压着新说明 */
private const val CONNECT_GUIDE_ID = "camera-connect-v1"

/**
 * 相机工作台的接线（设计 §8.7）：拿 ViewModel、按生命周期收状态、把意图翻译成回调。
 * 页面本体是下面的 `CameraHubScreen`，它不认识 ViewModel，也不认识 NavController。
 */
@Composable
fun CameraHubRoute(
    onOpenPhotos: () -> Unit = {},
    onOpenRemote: () -> Unit = {},
    onOpenConnect: (ConnectPurpose) -> Unit = {},
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val transferActive by viewModel.transferActive.collectAsStateWithLifecycle()

    CameraHubScreen(
        phase = state.phase,
        cameraModel = state.cameraModel,
        errorMessage = state.errorMessage,
        capabilities = capabilities,
        transferActive = transferActive,
        onOpenPhotos = onOpenPhotos,
        onOpenRemote = onOpenRemote,
        onOpenConnect = onOpenConnect,
        onDisconnect = { viewModel.disconnect() }
    )
}

/**
 * 相机工作台。
 *
 * 顺序照设计 §3.3 的线框：标题栏 → 状态卡 → 「你想做什么？」两个同级入口 →
 * 只在必要时出现的引导卡。原来的超大品牌字 hero 撤下了：首页是工具页，
 * 第一屏应该回答「相机现在能干什么」，不是展示 logo；品牌只留一行小字。
 *
 * 两个任务入口**默认同等级**（都不带 PRIMARY 表面）：先让用户选目标，
 * 而不是替用户选。未连接时它们禁用并说明原因，而不是点了没反应或跳去连接——
 * 连接入口在下面的次级位置，批次 D 会把它收成完整向导。
 */
@Composable
fun CameraHubScreen(
    phase: ConnectionPhase,
    cameraModel: String?,
    errorMessage: String?,
    capabilities: CameraCapabilities,
    transferActive: Boolean,
    onOpenPhotos: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenConnect: (ConnectPurpose) -> Unit,
    onDisconnect: () -> Unit
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val connected = phase == ConnectionPhase.CONNECTED

    AppScreenFrame(
        topBar = {
            AppPageHeader(
                title = stringResource(R.string.tab_camera),
                large = true,
                actions = {
                    AppIconButton(
                        icon = Lucide.CircleQuestionMark,
                        contentDescription = stringResource(R.string.home_help_action),
                        onClick = { showHelp = true }
                    )
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 底部为悬浮导航让位：高度实测下发，写死数值在大字模式下会挡住最后一行
                .padding(bottom = LocalNavClearance.current)
                .padding(horizontal = Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.L)
        ) {
            Text(
                text = stringResource(R.string.home_brand_line),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = SmileySansFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CameraStatusCard(
                phase = phase,
                cameraModel = cameraModel,
                errorMessage = errorMessage,
                capabilities = capabilities,
                transferActive = transferActive,
                onDisconnect = onDisconnect
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                GroupTitle(stringResource(R.string.home_ask))
                // 同级、同表面：不给任何一个入口加 PRIMARY 强度，避免替用户做选择。
                // 批次 D：没连上时点它不是「按不动」也不是被自动弹去连接，而是带着目标
                // 进连接向导，成功后正好落回这件事（设计 §2「传照片→连接向导或照片」）
                ActionRow(
                    title = stringResource(R.string.home_task_photos),
                    description = if (connected)
                        stringResource(R.string.home_task_photos_desc)
                    else stringResource(R.string.home_task_need_camera),
                    icon = Lucide.Download,
                    onClick = { if (connected) onOpenPhotos else onOpenConnect(ConnectPurpose.Photos) }
                )
                ActionRow(
                    title = stringResource(R.string.home_task_remote),
                    description = if (connected)
                        stringResource(R.string.home_task_remote_desc)
                    else stringResource(R.string.home_task_need_camera),
                    icon = Lucide.Camera,
                    onClick = { if (connected) onOpenRemote else onOpenConnect(ConnectPurpose.Remote) }
                )
            }

            if (!connected) {
                // 原来这里是「扫码连接 / 手动连接」两个文字入口加一段内联 IP 输入框，
                // 扫码还是半屏弹窗——三样东西说的是同一件事，且都只到「发起连接」为止。
                // 批次 D 把它们收进向导：这里只留一条路，进去以后再分扫码/其他
                AppLink(
                    text = stringResource(R.string.wizard_title),
                    onClick = { onOpenConnect(ConnectPurpose.Browse) }
                )
            }
        }
    }

    if (showHelp) {
        HelpSheet(
            title = stringResource(R.string.home_help_title),
            steps = listOf(
                stringResource(R.string.home_guide_step1),
                stringResource(R.string.home_guide_step2),
                stringResource(R.string.home_guide_step3)
            ),
            onDismiss = { showHelp = false }
        )
    }
}

