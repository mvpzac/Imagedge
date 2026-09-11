package com.imagedge.camera.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.ui.components.AppButtonAlign
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.AppTextField
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.SmileySansFamily
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.components.StepsGuideCard
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.feature.connection.ConnectionViewModel
import com.imagedge.camera.feature.connection.QrScanDialog

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 主页（hero 核心型：超大品牌字 + 状态环 + 主 CTA，交互规格 4.1）
 *     version: 1.0
 * </pre>
 */

/**
 * 主页屏幕
 * 连接状态机：未连接 / 连接中 / 已连接 / 错误（交互规格 6.1）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRemote: () -> Unit = {},
    snackbarController: SnackbarController,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 扫码连接半屏弹窗（对齐系统扫码的交互形态）
    var showQrSheet by rememberSaveable { mutableStateOf(false) }
    // 手动连接展开 + IP 输入（留空则自动网关发现）
    var showManual by rememberSaveable { mutableStateOf(false) }
    var manualIp by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部 96dp 为悬浮玻璃导航栏让位：内容延伸到底（滚动时穿过导航栏被其折射），
            // 但最后一项能停在导航栏上方，不被永久遮挡
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hero 品牌字：得意黑 + primary 色（与底部磁吸指示条同源）
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = SmileySansFamily
            ),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 48.dp)
        )
        Text(
            text = stringResource(R.string.home_brand_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )

        // 状态卡
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 状态标识：图标 + 语义色（规范：状态不能只靠颜色区分，图标 + 文字双保险）
                when (state.phase) {
                    ConnectionPhase.CONNECTED -> LucideIcon(
                        Lucide.CircleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        size = 32.dp
                    )
                    ConnectionPhase.ERROR -> LucideIcon(
                        Lucide.TriangleAlert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        size = 32.dp
                    )
                    ConnectionPhase.DISCONNECTED -> LucideIcon(
                        Lucide.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 32.dp
                    )
                    ConnectionPhase.CONNECTING -> CircularProgressIndicator(
                        modifier = Modifier.size(32.dp)
                    )
                }
                when (state.phase) {
                    ConnectionPhase.CONNECTING -> {
                        Text(stringResource(R.string.home_status_connecting), style = MaterialTheme.typography.titleMedium)
                    }
                    ConnectionPhase.CONNECTED -> {
                        Text(stringResource(R.string.home_status_connected), style = MaterialTheme.typography.titleMedium)
                        state.cameraModel?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.channelType?.let {
                            Text(
                                text = stringResource(R.string.home_channel, channelLabel(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    ConnectionPhase.ERROR -> {
                        Text(stringResource(R.string.home_status_error), style = MaterialTheme.typography.titleMedium)
                        state.errorMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ConnectionPhase.DISCONNECTED -> {
                        Text(stringResource(R.string.home_status_disconnected), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.home_status_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 三步连接引导：未连接或连接失败时显示，连接成功后自动消失
        if (state.phase == ConnectionPhase.DISCONNECTED || state.phase == ConnectionPhase.ERROR) {
            StepsGuideCard(
                steps = listOf(
                    stringResource(R.string.home_guide_step1),
                    stringResource(R.string.home_guide_step2),
                    stringResource(R.string.home_guide_step3)
                ),
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        // ── 主操作区 ──
        // 规范：一屏只有一个主按钮。未连接时主操作 = 扫码连接；
        // 已连接时主操作在「遥控拍摄」卡片上，断开只是次级动作（所以用 SECONDARY）。
        //
        // 按钮一律走 AppButton 的**内置双行/箭头能力**（subtitle + align + trailingIcon），
        // 不再用自定义内容槽拼 Column —— 那会让内边距、对齐与文字层级各写一套。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.XXL),
            verticalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            if (state.phase == ConnectionPhase.CONNECTED) {
                AppButton(
                    text = stringResource(R.string.home_btn_disconnect),
                    subtitle = stringResource(R.string.home_btn_disconnect_desc),
                    onClick = { viewModel.disconnect() },
                    type = AppButtonType.SECONDARY,
                    leadingIcon = null,
                    align = AppButtonAlign.START
                )
            } else {
                AppButton(
                    text = stringResource(R.string.home_btn_qr),
                    subtitle = stringResource(R.string.home_btn_qr_desc),
                    onClick = { showQrSheet = true },
                    leadingIcon = Lucide.QrCode,
                    align = AppButtonAlign.START,
                    trailingIcon = Lucide.ChevronRight
                )
                AppButton(
                    text = stringResource(R.string.home_btn_manual),
                    subtitle = stringResource(R.string.home_btn_manual_desc),
                    onClick = { showManual = !showManual },
                    type = AppButtonType.SECONDARY,
                    leadingIcon = Lucide.Keyboard,
                    align = AppButtonAlign.START,
                    // 展开态用箭头方向表达（不额外画图标，避免多一套视觉语言）
                    trailingIcon = if (showManual) Lucide.ArrowUp else Lucide.ArrowDown
                )
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
                    // 展开面板内的次级动作（规范：不与上面的主按钮同级）
                    AppButton(
                        text = stringResource(R.string.home_manual_connect),
                        onClick = { viewModel.connect(manualIp.ifBlank { null }) },
                        enabled = state.phase != ConnectionPhase.CONNECTING,
                        type = AppButtonType.SECONDARY
                    )
                }
            }
        }

        // 遥控拍摄子分区入口（传输已连接时显示，实际功能在二级页）
        if (state.phase == ConnectionPhase.CONNECTED) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                onClick = onOpenRemote
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.remote_entry_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.remote_entry_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AppLink(
                        text = stringResource(R.string.remote_entry_open),
                        onClick = onOpenRemote
                    )
                }
            }
        }
    }

    // 扫码连接半屏弹窗（从底部弹出、宽度铺满、高度 40%，下滑/点外部收回 → 自动连接相机）
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
}

/** 通道类型显示名 */
private fun channelLabel(type: com.imagedge.camera.data.remote.ChannelType): String = when (type) {
    com.imagedge.camera.data.remote.ChannelType.PTP_IP -> "PTP/IP"
    com.imagedge.camera.data.remote.ChannelType.UPNP -> "UPnP"
}

