package com.imagedge.camera.feature.connection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.navigation.LocalNavClearance
import com.imagedge.camera.ui.components.ActionRow
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppTextField
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.StatusBanner
import com.imagedge.camera.ui.guidance.ContextHint
import com.imagedge.camera.ui.guidance.GuideCard
import com.imagedge.camera.ui.guidance.GuideContent
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 连接向导（批次 D）：相机准备 → 手机连接 → 确认连接，一条子流程走完
 *     version: 1.0
 * </pre>
 */

/** 引导卡标识。文案改版就换 v，否则旧的「已知道了」会一直压着新说明 */
private const val PREPARE_GUIDE_ID = "camera-connect-prepare-v1"

/**
 * 连接向导。
 *
 * 批次 D 之前，连接这件事散在三处：相机工作台的「扫码连接」半屏弹窗、弹窗里叠的
 * 系统权限请求、以及工作台下面那段内联手动 IP。设计 §4.2 要求收成一个完整子页，
 * 并且**不许在扫码弹窗上继续叠权限/手动连接面板**——叠层的问题不是难看，
 * 是每一层都有自己的关闭手势，用户关掉一层以为自己退出了整个连接。
 *
 * 三条硬约定：
 * - 扫码的实现原样保留（[QrScanStep]），只是从弹窗变成页面里的一步；
 * - 步骤状态全部由 [stepsOf] 从真实信号算出来，**不画百分比**：
 *   PTP 握手与 0x9209 读取都是单次阻塞往返，没有可线性化的进度来源；
 * - 五种走不通的情况都必须有能点的出口：权限拒绝、相机没出二维码、
 *   手机已经连着别的 Wi-Fi、配网超时、用户取消。
 *
 * @param purpose 用户是为了什么进来的（决定成功页那一个继续按钮去哪）
 * @param onBack 离开向导。系统返回、标题返回、页面里的「取消」三条路走同一个函数
 */
@Composable
fun ConnectWizardScreen(
    purpose: ConnectPurpose,
    onBack: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenRemote: () -> Unit,
    viewModel: ConnectionViewModel = hiltViewModel(),
    scanViewModel: QrScanViewModel = hiltViewModel()
) {
    val wizard by viewModel.wizard.collectAsStateWithLifecycle()
    val connection by viewModel.state.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val scanState by scanViewModel.state.collectAsStateWithLifecycle()
    var showOtherPaths by rememberSaveable { mutableStateOf(false) }

    // 扫码那一步把它的配网结果报给阶段机（单向，向导不抢配网实现的所有权）。
    // consumeSuccess 是一次性的：旋转/重组不能重复发起会话连接。
    LaunchedEffect(scanState) {
        viewModel.onHotspotObserved(scanState)
        if (scanState is QrScanUiState.Success && scanViewModel.consumeSuccess()) {
            viewModel.startSession()
        }
    }
    LaunchedEffect(connection.phase) {
        if (connection.phase == ConnectionPhase.CONNECTED) viewModel.onSessionConnected()
    }

    val leave = {
        viewModel.cancelAttempt()
        onBack()
    }

    AppScreenFrame(
        topBar = {
            AppPageHeader(
                title = stringResource(R.string.wizard_title),
                onBack = leave
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.L)
                .padding(bottom = LocalNavClearance.current),
            verticalArrangement = Arrangement.spacedBy(Spacing.L)
        ) {
            StepHeader(phase = wizardPhaseIndex(wizard.attempt))

            when (wizard.attempt.stage) {
                WizardStage.PrepareCamera -> PrepareCameraStage(
                    hotspotAlreadyJoined = wizard.attempt.hotspotJoined,
                    viewModel = viewModel,
                    scanViewModel = scanViewModel,
                    showOtherPaths = showOtherPaths,
                    onShowOtherPaths = { showOtherPaths = true },
                    onLeave = leave
                )

                WizardStage.ScanQr -> QrScanStep(
                    onSessionStart = { viewModel.startSession() },
                    onNeedOtherPaths = {
                        showOtherPaths = true
                        viewModel.backToPrepare()
                    },
                    onBack = { viewModel.backToPrepare() },
                    viewModel = scanViewModel
                )

                WizardStage.Connect -> ConnectStage(
                    view = wizard,
                    onRetrySession = { viewModel.retrySession() },
                    onRetryWifi = {
                        scanViewModel.reset()
                        viewModel.retryWifi()
                    },
                    onNeedOtherPaths = {
                        showOtherPaths = true
                        viewModel.backToPrepare()
                    },
                    onBack = { viewModel.backToPrepare() },
                    onLeave = leave
                )

                WizardStage.Success -> SuccessStage(
                    model = connection.cameraModel,
                    purpose = purpose,
                    shutterState = capabilities.stateOf(CameraCapability.CAPTURE),
                    onOpenPhotos = onOpenPhotos,
                    onOpenRemote = onOpenRemote
                )
            }
        }
    }
}

/** 三段步骤头。不是三个能点的芯片——用户不能跳步，亮成可点的样子就是骗点 */
@Composable
private fun StepHeader(phase: Int) {
    val labels = listOf(
        stringResource(R.string.wizard_phase_prepare),
        stringResource(R.string.wizard_phase_join),
        stringResource(R.string.wizard_phase_verify)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        labels.forEachIndexed { index, label ->
            val reached = index + 1 <= phase
            val current = index + 1 == phase
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.Tag))
                    .background(
                        if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .padding(vertical = Spacing.S),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${index + 1} $label",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = when {
                        current -> MaterialTheme.colorScheme.primary
                        reached -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/**
 * 第 1 段「相机准备」：先说相机上要做的事，再给一个主操作。
 *
 * 主按钮按状态型页面的规矩压在下 1/3（UI 规范 §1）：这段唯一要做的决定就是
 * 「相机准备好了没有」，别的入口一律降级为文字。
 */
@Composable
private fun PrepareCameraStage(
    hotspotAlreadyJoined: Boolean,
    viewModel: ConnectionViewModel,
    scanViewModel: QrScanViewModel,
    showOtherPaths: Boolean,
    onShowOtherPaths: () -> Unit,
    onLeave: () -> Unit
) {
    GuideCard(
        guide = GuideContent(
            id = PREPARE_GUIDE_ID,
            locationLabel = stringResource(R.string.home_guide_on_camera),
            title = stringResource(R.string.home_guide_title),
            body = stringResource(R.string.home_guide_body),
            actionLabel = stringResource(R.string.home_guide_dismiss)
        ),
        onAction = { viewModel.dismissGuide(PREPARE_GUIDE_ID) }
    )

    if (hotspotAlreadyJoined) {
        // 从第 2/3 段退回来时热点还活着（释放即断开，见 sony-protocol-notes §3）。
        // 这时还催用户去扫码，是让他把已经拿到的东西再丢一次
        StatusBanner(
            message = stringResource(R.string.wizard_hotspot_still_on),
            actionLabel = stringResource(R.string.wizard_continue_connect),
            onAction = { viewModel.startSession() },
            isError = false
        )
    }

    Spacer(Modifier.height(Spacing.XXL))

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        AppButton(
            text = stringResource(R.string.wizard_qr_ready),
            onClick = {
                scanViewModel.reset()
                viewModel.startScan()
            },
            leadingIcon = Lucide.QrCode
        )
        AppLink(
            text = stringResource(R.string.wizard_no_qr),
            onClick = onShowOtherPaths
        )
        if (showOtherPaths) {
            OtherConnectionPaths(
                onConnectNow = { viewModel.startDirectSession(null) },
                onConnectTo = { host -> viewModel.startDirectSession(host) }
            )
        }
        AppLink(text = stringResource(R.string.wizard_cancel), onClick = onLeave)
    }
}

/**
 * 「其他连接方式」：没有二维码、扫码权限被拒、手机已经连在相机热点上，都走这里。
 *
 * 两条都不发起配网请求，所以它们的 Wi-Fi 步骤在连接页显示为「不适用」而不是「已完成」。
 */
@Composable
private fun OtherConnectionPaths(
    onConnectNow: () -> Unit,
    /** 参数为 null = 留空，让通道层自己发现网关 */
    onConnectTo: (String?) -> Unit
) {
    var host by rememberSaveable { mutableStateOf("") }
    var invalidHost by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        GroupTitle(stringResource(R.string.wizard_other_title))
        ContextHint(stringResource(R.string.wizard_other_desc))
        // 手机已经连着相机热点（自己从系统设置连的，或上一次扫码留下的）：
        // 直接发起会话，不必再扫一遍。这是「已有 Wi-Fi」那条出口的落点
        ActionRow(
            title = stringResource(R.string.wizard_already_on_hotspot),
            description = stringResource(R.string.wizard_already_on_hotspot_desc),
            icon = Lucide.Wifi,
            onClick = onConnectNow
        )
        AppTextField(
            value = host,
            onValueChange = { host = it },
            label = stringResource(R.string.settings_ip_label)
        )
        Text(
            text = invalidHost?.let { stringResource(R.string.wizard_ip_invalid, it) }
                ?: stringResource(R.string.home_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (invalidHost != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 次级：这一屏的主操作是「二维码已显示」，两个 PRIMARY 是规范违规（§2 原则 2）
        AppButton(
            text = stringResource(R.string.home_manual_connect),
            onClick = {
                invalidHost = when (val parsed = parseManualHost(host)) {
                    is ManualHost.Ip -> {
                        onConnectTo(parsed.host)
                        null
                    }

                    ManualHost.AutoGateway -> {
                        onConnectTo(null)
                        null
                    }

                    is ManualHost.Invalid -> parsed.raw
                }
            },
            // 写错的 IP 不发起连接：把 "192.168.abc" 丢给 socket 只会等回一个看不懂的超时
            type = AppButtonType.SECONDARY
        )
    }
}

/**
 * 第 2/3 段：逐条显示「加入热点 / 连接服务 / 验证能力」。
 *
 * 失败说明**跟着失败那一步**显示，不飘在页面顶上：用户要能看出是哪一步断的，
 * 才知道下一步该做什么（UI 规范 §7「原因 + 下一步」）。
 */
@Composable
private fun ConnectStage(
    view: WizardView,
    onRetrySession: () -> Unit,
    onRetryWifi: () -> Unit,
    onNeedOtherPaths: () -> Unit,
    onBack: () -> Unit,
    onLeave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        StepLine(
            state = view.steps.wifi,
            label = stringResource(R.string.wizard_step_wifi),
            doneText = stringResource(R.string.wizard_step_wifi_done),
            runningText = stringResource(R.string.wizard_step_wifi_running),
            skippedText = stringResource(R.string.wizard_step_wifi_skipped),
            reason = view.steps.wifiReason
        )
        StepLine(
            state = view.steps.session,
            label = stringResource(R.string.wizard_step_session),
            runningText = stringResource(R.string.wizard_step_session_running),
            reason = view.steps.sessionReason
        )
        StepLine(
            state = view.steps.verify,
            label = stringResource(R.string.wizard_step_verify),
            unknownText = stringResource(R.string.wizard_step_verify_unknown),
            // 描述符没读回来不是失败：能力未知在遥控页还能「检查是否可用」，
            // 在这里把它写成红色失败会诱导用户重连，而重连正是可能丢句柄的那个动作
            unknownHint = stringResource(R.string.wizard_step_verify_unknown_hint)
        )

        Spacer(Modifier.height(Spacing.L))

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            when (view.exits.retry) {
                RetryTarget.Session -> AppButton(
                    text = stringResource(R.string.wizard_retry_step),
                    onClick = onRetrySession,
                    enabled = view.steps.session != StepState.Running
                )

                RetryTarget.Wifi -> AppButton(
                    text = stringResource(R.string.wizard_rescan),
                    onClick = onRetryWifi
                )

                null -> Unit
            }
            if (view.exits.canUseOtherPath) {
                AppLink(
                    text = stringResource(R.string.wizard_no_qr),
                    onClick = onNeedOtherPaths
                )
            }
            if (view.exits.canGoBack) {
                AppLink(text = stringResource(R.string.wizard_back), onClick = onBack)
            }
            if (view.exits.canCancel) {
                AppLink(text = stringResource(R.string.wizard_cancel), onClick = onLeave)
            }
        }
    }
}

/** 一行步骤：状态图标 + 名称 + 进行/失败说明。没有百分比，也没有假进度条 */
@Composable
private fun StepLine(
    state: StepState,
    label: String,
    doneText: String? = null,
    runningText: String? = null,
    skippedText: String? = null,
    unknownText: String? = null,
    unknownHint: String? = null,
    reason: String? = null
) {
    val detail = when (state) {
        StepState.Done -> doneText
        StepState.Running -> runningText
        StepState.Skipped -> skippedText
        StepState.Unknown -> unknownText
        StepState.Failed -> reason
        StepState.Pending -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Card))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(Spacing.L),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        LucideIcon(
            iconOf(state),
            contentDescription = null,
            size = 20.dp,
            tint = colorOf(state)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == StepState.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state == StepState.Unknown && unknownHint != null) {
                Text(
                    text = unknownHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun iconOf(state: StepState): Int = when (state) {
    StepState.Done -> Lucide.CircleCheck
    StepState.Failed -> Lucide.CircleX
    StepState.Running -> Lucide.Download
    StepState.Unknown -> Lucide.CircleQuestionMark
    StepState.Skipped -> Lucide.ChevronRight
    StepState.Pending -> Lucide.Info
}

@Composable
private fun colorOf(state: StepState) = when (state) {
    StepState.Failed -> MaterialTheme.colorScheme.error
    StepState.Done -> MaterialTheme.colorScheme.primary
    StepState.Unknown -> MaterialTheme.colorScheme.tertiary
    StepState.Pending, StepState.Skipped -> MaterialTheme.colorScheme.onSurfaceVariant
    StepState.Running -> MaterialTheme.colorScheme.onSurface
}

/**
 * 成功页：机型 + 可用目标 + 一个继续按钮。
 *
 * 刻意不自动跳转（设计 §4.2「从按钮进入目标，避免后台连上后突然跳转」）：
 * 连上就跳页，等于在用户还没决定要做什么的时候替他决定了。
 */
@Composable
private fun SuccessStage(
    model: String?,
    purpose: ConnectPurpose,
    shutterState: CapabilityState,
    onOpenPhotos: () -> Unit,
    onOpenRemote: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.L)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LucideIcon(
                Lucide.CircleCheck,
                contentDescription = null,
                size = 24.dp,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(Spacing.M))
            Text(
                text = model?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.wizard_success_unknown_model),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
        }
        ContextHint(stringResource(R.string.wizard_success_targets))

        ActionRow(
            title = stringResource(R.string.wizard_target_photos),
            description = stringResource(R.string.wizard_target_photos_desc),
            icon = Lucide.Images,
            onClick = onOpenPhotos
        )
        // 遥控按当次 0x9209 上报说话：UNKNOWN 不能说成「不支持」，
        // 一次读取超时就永久判死一个控件是规范禁止的（已知坑 14）
        ActionRow(
            title = stringResource(R.string.wizard_target_remote),
            icon = Lucide.Camera,
            enabled = shutterState != CapabilityState.UNSUPPORTED,
            disabledReason = if (shutterState == CapabilityState.UNSUPPORTED)
                stringResource(R.string.wizard_target_remote_unsupported) else null,
            description = when (shutterState) {
                CapabilityState.UNKNOWN -> stringResource(R.string.wizard_target_remote_unknown)
                CapabilityState.READ_ONLY -> stringResource(R.string.wizard_target_remote_readonly)
                else -> null
            },
            onClick = onOpenRemote
        )

        Spacer(Modifier.height(Spacing.XXL))

        // 有明确目标时只给那一个按钮（§2：不用模糊的「完成」）；
        // 从工作台「连接相机」进来的没有目标，上面两行就是它的出口
        when (purpose) {
            ConnectPurpose.Photos -> AppButton(
                text = stringResource(R.string.wizard_continue_photos),
                onClick = onOpenPhotos,
                leadingIcon = Lucide.Images
            )

            ConnectPurpose.Remote -> AppButton(
                text = stringResource(R.string.wizard_continue_remote),
                onClick = onOpenRemote,
                leadingIcon = Lucide.Camera,
                enabled = shutterState != CapabilityState.UNSUPPORTED
            )

            ConnectPurpose.Browse -> Unit
        }
    }
}
