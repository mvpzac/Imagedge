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
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.navigation.LocalNavClearance
import com.imagedge.camera.ui.components.ActionRow
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppTextField
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.guidance.GuideCard
import com.imagedge.camera.ui.guidance.GuideContent
import com.imagedge.camera.ui.guidance.HelpSheet
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.SmileySansFamily
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.feature.connection.ConnectionViewModel
import com.imagedge.camera.feature.connection.QrScanDialog

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
    onOpenPhotos: () -> Unit = {},
    onOpenRemote: () -> Unit = {},
    snackbarController: SnackbarController,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val transferActive by viewModel.transferActive.collectAsStateWithLifecycle()

    // 扫码连接半屏弹窗（对齐系统扫码的交互形态）
    var showQrSheet by rememberSaveable { mutableStateOf(false) }
    // 手动连接展开 + IP 输入（留空则自动网关发现）
    var showManual by rememberSaveable { mutableStateOf(false) }
    var manualIp by rememberSaveable { mutableStateOf("") }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    val connected = state.phase == ConnectionPhase.CONNECTED
    val isConnecting = state.phase == ConnectionPhase.CONNECTING

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
                phase = state.phase,
                cameraModel = state.cameraModel,
                errorMessage = state.errorMessage,
                capabilities = capabilities,
                transferActive = transferActive,
                onDisconnect = { viewModel.disconnect() }
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.M)
            ) {
                GroupTitle(stringResource(R.string.home_ask))
                // 同级、同表面：不给任何一个入口加 PRIMARY 强度，避免替用户做选择
                ActionRow(
                    title = stringResource(R.string.home_task_photos),
                    description = if (connected)
                        stringResource(R.string.home_task_photos_desc) else null,
                    icon = Lucide.Download,
                    enabled = connected,
                    disabledReason = stringResource(R.string.home_task_need_camera),
                    onClick = onOpenPhotos
                )
                ActionRow(
                    title = stringResource(R.string.home_task_remote),
                    description = if (connected)
                        stringResource(R.string.home_task_remote_desc) else null,
                    icon = Lucide.Camera,
                    enabled = connected,
                    disabledReason = stringResource(R.string.home_task_need_camera),
                    onClick = onOpenRemote
                )
            }

            // 次级连接入口：批次 D 之前保持可达，但不与上面的任务入口抢层级
            if (!connected) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.S)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
                        AppLink(
                            text = stringResource(R.string.home_btn_qr),
                            onClick = { showQrSheet = true }
                        )
                        AppLink(
                            text = stringResource(R.string.home_btn_manual),
                            onClick = { showManual = !showManual }
                        )
                    }
                    if (showManual) {
                        AppTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = stringResource(R.string.settings_ip_label)
                        )
                        Text(
                            text = stringResource(R.string.home_manual_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppButton(
                            text = stringResource(R.string.home_manual_connect),
                            onClick = { viewModel.connect(manualIp.ifBlank { null }) },
                            enabled = !isConnecting,
                            type = AppButtonType.SECONDARY
                        )
                    }
                }
            }

            // 引导卡：不透明普通表面，且「已知道了」按机型记忆，不重复骚扰
            if (!connected && viewModel.shouldShowGuide(CONNECT_GUIDE_ID)) {
                GuideCard(
                    guide = GuideContent(
                        id = CONNECT_GUIDE_ID,
                        locationLabel = stringResource(R.string.home_guide_on_camera),
                        title = stringResource(R.string.home_guide_title),
                        body = stringResource(R.string.home_guide_body),
                        actionLabel = stringResource(R.string.home_guide_dismiss)
                    ),
                    onAction = { viewModel.dismissGuide(CONNECT_GUIDE_ID) }
                )
            }
        }
    }

    if (showQrSheet) {
        QrScanDialog(
            onDismiss = { showQrSheet = false },
            onConnected = {
                showQrSheet = false
                // 扫码配网成功后自动连接相机（无需再手动点「连接」）
                viewModel.connect()
            },
            snackbarController = snackbarController
        )
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

