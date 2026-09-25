package com.imagedge.camera.feature.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraTransport
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.ui.components.AppIconButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.glass.GlassCard
import com.imagedge.camera.ui.glass.glassDialog
import com.imagedge.camera.ui.glass.glassDialogContainerColor
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 相机工作台状态卡（批次 B）：机型为标题、状态为副行、能力只转述相机上报
 *     version: 1.0
 * </pre>
 */

/**
 * 相机工作台的状态卡。
 *
 * 为什么是**功能私有**组件而不是 `ui/components` 的一员（设计 §6）：它要懂
 * [ConnectionPhase] 与 [CapabilityState] 的语义差别，通用容器只会把这些压成
 * 一个 `title: String` 参数，然后每个调用点各自猜该填什么。
 *
 * 三件事就是这块卡的全部职责：
 * 1. **机型当标题，状态当副行**——用户认的是自己那台 ZV-E10，不是 `CONNECTED` 这个枚举；
 * 2. **能力摘要只转述相机当次上报的结果**，`UNKNOWN`（没读到）与 `UNSUPPORTED`
 *    （读了，不支持）必须分开写，把探测失败说成不支持是本项目反复踩的坑；
 * 3. **通道 / 固件 / 功能模式收进「连接详情」**，首屏不摆 PTP/IP、0x9210 这种词。
 *
 * 这也是本页唯一的一张玻璃卡：§3.2 给工作台的预算是「最多一张主玻璃状态卡，
 * 其余入口普通表面」，两个任务入口都走不透明表面。
 */
@Composable
fun CameraStatusCard(
    phase: ConnectionPhase,
    cameraModel: String?,
    errorMessage: String?,
    capabilities: CameraCapabilities,
    transferActive: Boolean,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    val connected = phase == ConnectionPhase.CONNECTED

    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.L),
            verticalArrangement = Arrangement.spacedBy(Spacing.S)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.XS)
                ) {
                    Text(
                        text = when {
                            connected -> cameraModel?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.home_status_connected)
                            else -> stringResource(statusTitle(phase))
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.XS)
                    ) {
                        StatusMark(phase)
                        Text(
                            // 失败时副行就是原因本身（异常文案，不是资源），没有原因才退回通用说明
                            text = errorMessage ?: stringResource(statusSubtitle(phase)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 「更多」里才放断开：首屏不该给一个破坏性动作留主位
                if (connected) {
                    Box {
                        AppIconButton(
                            icon = Lucide.MoreVertical,
                            contentDescription = stringResource(R.string.home_more),
                            onClick = { menuOpen = true }
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_connection_details)) },
                                onClick = {
                                    menuOpen = false
                                    showDetails = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_btn_disconnect)) },
                                onClick = {
                                    menuOpen = false
                                    confirmDisconnect = true
                                }
                            )
                        }
                    }
                }
            }

            // 已连接时才谈能力：没连接时谈「快门未确认」是在说一件还没开始的事
            if (connected) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                    LabeledValue(
                        label = stringResource(R.string.home_cap_transfer_label),
                        value = stringResource(R.string.home_cap_transfer_value)
                    )
                    LabeledValue(
                        label = stringResource(R.string.home_cap_shutter_label),
                        value = stringResource(shutterCapability(capabilities))
                    )
                }
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            modifier = Modifier.glassDialog(),
            containerColor = glassDialogContainerColor(),
            title = { Text(stringResource(R.string.home_connection_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                    LabeledValue(
                        stringResource(R.string.home_detail_channel),
                        channelLabel(capabilities.identity.transport)
                    )
                    LabeledValue(
                        stringResource(R.string.home_detail_firmware),
                        capabilities.identity.firmware.ifBlank {
                            stringResource(R.string.home_detail_unknown)
                        }
                    )
                    LabeledValue(
                        stringResource(R.string.home_detail_mode),
                        modeLabel(capabilities.identity.mode)
                    )
                }
            },
            confirmButton = {
                AppLink(
                    text = stringResource(R.string.home_close),
                    onClick = { showDetails = false }
                )
            }
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            modifier = Modifier.glassDialog(),
            containerColor = glassDialogContainerColor(),
            title = { Text(stringResource(R.string.home_btn_disconnect)) },
            // 传输中断开是真的会丢任务，所以按当前状态说不同的话，不用一句通用文案糊过去
            text = {
                Text(
                    stringResource(
                        if (transferActive) R.string.home_disconnect_body_busy
                        else R.string.home_disconnect_body_idle
                    )
                )
            },
            confirmButton = {
                AppLink(
                    text = stringResource(R.string.home_btn_disconnect),
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                    color = MaterialTheme.colorScheme.error
                )
            },
            dismissButton = {
                AppLink(
                    text = stringResource(R.string.home_cancel),
                    onClick = { confirmDisconnect = false }
                )
            }
        )
    }
}

/** 状态标记：图标 + 文字双保险，语义色只是加强，不单独承担区分 */
@Composable
private fun StatusMark(phase: ConnectionPhase) {
    when (phase) {
        ConnectionPhase.CONNECTING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        ConnectionPhase.CONNECTED -> LucideIcon(
            Lucide.CircleCheck,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            size = 16.dp
        )
        ConnectionPhase.ERROR -> LucideIcon(
            Lucide.TriangleAlert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            size = 16.dp
        )
        ConnectionPhase.DISCONNECTED -> LucideIcon(
            Lucide.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 16.dp
        )
    }
}

/** 一行「标签 — 值」：能力摘要与连接详情共用同一种排法，值右对齐成一列状态 */
@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            // 右对齐：能力值是一列状态，左对齐会和标签挤成一句读不清的话
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

private fun statusTitle(phase: ConnectionPhase): Int = when (phase) {
    ConnectionPhase.CONNECTED -> R.string.home_status_connected
    ConnectionPhase.CONNECTING -> R.string.home_status_connecting
    ConnectionPhase.ERROR -> R.string.home_status_error
    ConnectionPhase.DISCONNECTED -> R.string.home_status_disconnected
}

private fun statusSubtitle(phase: ConnectionPhase): Int = when (phase) {
    // 机型已经占了标题，副行说「下一步做什么」而不是再重复一遍状态
    ConnectionPhase.CONNECTED -> R.string.home_status_connected_sub
    ConnectionPhase.CONNECTING -> R.string.home_status_connecting_sub
    ConnectionPhase.ERROR -> R.string.home_status_error_sub
    ConnectionPhase.DISCONNECTED -> R.string.home_status_disconnected_sub
}

/**
 * 快门能力的一句话。
 *
 * 三态分开：`UNKNOWN` 是**没读到**（未连接、0x9209 超时、非 PTP 通道），
 * `UNSUPPORTED` 是读了且不支持。把前者写成后者，用户会以为自己的相机不行。
 */
private fun shutterCapability(capabilities: CameraCapabilities): Int {
    return when (capabilities.stateOf(CameraCapability.CAPTURE)) {
        CapabilityState.WRITABLE -> R.string.home_cap_shutter_ready
        CapabilityState.READ_ONLY -> R.string.home_cap_shutter_readonly
        CapabilityState.UNSUPPORTED -> R.string.home_cap_shutter_unsupported
        CapabilityState.UNKNOWN -> R.string.home_cap_shutter_unknown
    }
}

private fun channelLabel(transport: CameraTransport?): String = when (transport) {
    CameraTransport.PTP_IP -> "PTP/IP"
    CameraTransport.UPNP -> "UPnP"
    null -> "—"
}

private fun modeLabel(mode: Int): String = when (mode) {
    CameraIdentity.MODE_REMOTE_CONTROL -> "RemoteControl"
    CameraIdentity.MODE_CONTENTS_TRANSFER -> "ContentsTransfer"
    else -> "—"
}
