package com.imagedge.camera.feature.connection

import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.data.model.ConnectionState

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 连接向导的阶段机（批次 D）：纯函数，把「现在走到哪、能做什么」算成一个结论
 *     version: 1.0
 * </pre>
 */

/** 向导的四个阶段（设计 §4.2 的三段步骤头 + 成功页） */
enum class WizardStage { PrepareCamera, ScanQr, Connect, Success }

/**
 * 本次走哪条路径连上相机。
 *
 * [ManualIp] 包含「手机已经在相机热点上」——那条路我们**没有**发起过配网请求，
 * 所以 Wi-Fi 那一步只能是「不适用」，不能显示成已完成（那是在邀功）。
 */
enum class ConnectPath { Qr, ManualIp }

/** 一条步骤的状态。[Unknown] 与 [Failed] 必须分开：未知 ≠ 不支持（docs/HANDOFF.md 已知坑 14） */
enum class StepState { Pending, Running, Done, Failed, Skipped, Unknown }

/** 一次连接尝试的输入。全部是普通值类型，所以这一层能在 JVM 上直接测 */
data class ConnectAttempt(
    val stage: WizardStage,
    val path: ConnectPath,
    /** 扫码配网请求的结果。[hotspotJoined] 一旦为真就保持为真——请求成功后
     *  QrScanViewModel 会回到 Idle（它的 release 必须保留存活请求），
     *  拿瞬时状态当步骤状态会把「已连上热点」读成「还没开始」 */
    val hotspotPending: Boolean,
    val hotspotJoined: Boolean,
    val hotspotError: String?,
    val sessionPhase: ConnectionPhase,
    val sessionError: String?,
    /** 0x9209 描述符是否真的读回来过。没读过就是未知，不是不支持 */
    val descriptorRead: Boolean
)

/** 连接页要逐条显示的三步（设计 §4.2「实际后台阶段细化为加入 Wi-Fi、连接服务、验证目标能力」） */
data class ConnectSteps(
    val wifi: StepState,
    val session: StepState,
    val verify: StepState,
    val wifiReason: String? = null,
    val sessionReason: String? = null
)

/**
 * 把一次尝试算成三条步骤的状态。
 *
 * 刻意**不算百分比**：PTP/IP 握手与 0x9209 读取都是单次阻塞往返，没有任何可线性化的
 * 进度来源，画一根进度条就是假进度（设计 §4.2「不显示假进度」）。
 */
fun stepsOf(attempt: ConnectAttempt): ConnectSteps {
    val wifi = when {
        attempt.path == ConnectPath.ManualIp -> StepState.Skipped
        attempt.hotspotJoined -> StepState.Done
        attempt.hotspotError != null -> StepState.Failed
        attempt.hotspotPending -> StepState.Running
        else -> StepState.Pending
    }
    val session = when (attempt.sessionPhase) {
        ConnectionPhase.CONNECTING -> StepState.Running
        ConnectionPhase.CONNECTED -> StepState.Done
        ConnectionPhase.ERROR -> StepState.Failed
        // 还没发起：热点都没连上时它是「等前面」，热点连上了才是「待执行」——
        // 两者在界面上都是灰的，但原因不同，所以不合并成一个值
        ConnectionPhase.DISCONNECTED -> StepState.Pending
    }
    val verify = when {
        attempt.sessionPhase != ConnectionPhase.CONNECTED -> StepState.Pending
        attempt.descriptorRead -> StepState.Done
        else -> StepState.Unknown
    }
    return ConnectSteps(
        wifi = wifi,
        session = session,
        verify = verify,
        wifiReason = attempt.hotspotError,
        sessionReason = attempt.sessionError
    )
}

/**
 * 这一步现在能不能重跑。
 *
 * 只有失败或从未发起的步骤可以重试；正在跑的步骤再点一次就是并发发起两次连接
 * （UI 规范 §7 防重）。验证步不给重试按钮：0x9209 是连接过程中顺带读的，
 * 单独重读要改通道层，界面上说「未知」并留给遥控页/工作台的「检查是否可用」就够了。
 */
fun canRetrySession(steps: ConnectSteps): Boolean =
    steps.session == StepState.Failed || steps.session == StepState.Pending

fun canRetryWifi(steps: ConnectSteps): Boolean =
    steps.wifi == StepState.Failed

/**
 * 当前阶段可用的出口（设计 §9 批次 D 的验收：权限拒绝、无二维码、已有 Wi-Fi、超时、取消都有出口）。
 *
 * 每条都必须**真的能点**，不是文案里写「请检查…」然后什么都不给。
 */
data class WizardExits(
    /** 回到「相机准备」阶段（扫码/手动都能退回，成功页不给） */
    val canGoBack: Boolean,
    /** 取消本次连接请求：离开向导，并回收还没成功的配网请求 */
    val canCancel: Boolean,
    /** 展开「其他连接方式」（没有二维码 / 扫码权限被拒 / 热点超时 时的替代路径） */
    val canUseOtherPath: Boolean,
    /** 重跑失败的 Wi-Fi 步骤或会话步骤 */
    val retry: RetryTarget?
)

enum class RetryTarget { Wifi, Session }

/**
 * 该给哪个出口，全部由算出来的步骤状态决定，不由界面自己判断。
 *
 * 「没有二维码」在准备阶段就给（相机可能压根不显示二维码）；扫码权限被拒后也给
 * （[ScanQr] 阶段留着这条，就是规范要求的「拒绝后 → 其他连接方式」）。
 */
fun exitsOf(attempt: ConnectAttempt, steps: ConnectSteps): WizardExits = when (attempt.stage) {
    WizardStage.PrepareCamera -> WizardExits(
        canGoBack = false,
        canCancel = true,
        canUseOtherPath = true,
        retry = null
    )
    WizardStage.ScanQr -> WizardExits(
        canGoBack = true,
        canCancel = true,
        canUseOtherPath = true,
        retry = if (canRetryWifi(steps)) RetryTarget.Wifi else null
    )
    WizardStage.Connect -> WizardExits(
        canGoBack = true,
        canCancel = true,
        canUseOtherPath = true,
        retry = when {
            canRetryWifi(steps) -> RetryTarget.Wifi
            canRetrySession(steps) -> RetryTarget.Session
            else -> null
        }
    )
    WizardStage.Success -> WizardExits(
        canGoBack = false,
        canCancel = false,
        canUseOtherPath = false,
        retry = null
    )
}


/** 向导要显示的一份完整结论：输入 → 步骤 → 出口，一次算完 */
data class WizardView(
    val attempt: ConnectAttempt,
    val steps: ConnectSteps,
    val exits: WizardExits
)

/** 扫码配网请求的观测值（放在这里以便 [wizardViewOf] 与测试共用同一个构造口径） */
data class HotspotObservation(
    val pending: Boolean = false,
    val joined: Boolean = false,
    val error: String? = null
)

fun wizardViewOf(
    stage: WizardStage,
    path: ConnectPath,
    hotspot: HotspotObservation,
    connection: ConnectionState,
    descriptorRead: Boolean
): WizardView {
    val attempt = ConnectAttempt(
        stage = stage,
        path = path,
        hotspotPending = hotspot.pending,
        hotspotJoined = hotspot.joined,
        hotspotError = hotspot.error,
        sessionPhase = connection.phase,
        sessionError = connection.errorMessage,
        descriptorRead = descriptorRead
    )
    val steps = stepsOf(attempt)
    return WizardView(attempt, steps, exitsOf(attempt, steps))
}

/**
 * 步骤头当前亮在第几段（1..3 = 相机准备 / 手机连接 / 确认连接）。
 *
 * 「手机连接」和「确认连接」共用 Connect 这一个阶段：会话连上之前亮在第 2 段，
 * 会话连上、开始看能力时亮在第 3 段。分成两个阶段会让用户以为还有一步要点，
 * 而实际上那只是同一次等待里的两行。
 */
fun wizardPhaseIndex(attempt: ConnectAttempt): Int = when (attempt.stage) {
    WizardStage.PrepareCamera -> 1
    WizardStage.ScanQr -> 2
    WizardStage.Connect -> if (attempt.sessionPhase == ConnectionPhase.CONNECTED) 3 else 2
    WizardStage.Success -> 3
}

/** 手动 IP 那一步的输入结论 */
sealed interface ManualHost {
    /** 留空 = 让通道层自己发现网关（相机 AP 模式固定 192.168.122.1） */
    data object AutoGateway : ManualHost

    data class Ip(val host: String) : ManualHost

    /** 写错了要当场说，不能把 "192.168.abc" 原样丢给 socket 再等一个看不懂的超时 */
    data class Invalid(val raw: String) : ManualHost
}

private val IPV4 = Regex("""^(\d{1,3})(\.\d{1,3}){3}$""")

fun parseManualHost(raw: String): ManualHost {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ManualHost.AutoGateway
    val match = IPV4.matchEntire(trimmed) ?: return ManualHost.Invalid(trimmed)
    val octets = trimmed.split('.').map { it.toInt() }
    if (octets.any { it > 255 }) return ManualHost.Invalid(trimmed)
    return ManualHost.Ip(trimmed)
}

/**
 * 扫码配网观测值的归约（纯函数，因为「已连上」这条事实最容易在页面切换时被抹掉）。
 *
 * [HotspotObservation.joined] 一旦为真就保持为真：QrScanViewModel 在离开组合时会把
 * 自己的状态清回 Idle（它必须清，否则二次进入不干净），但配网请求**故意**留着——
 * 释放即断开相机热点。跟着瞬时状态走，连接页会把「已加入热点」显示成「还没开始」。
 */
fun hotspotAfter(current: HotspotObservation, qr: QrScanUiState): HotspotObservation =
    when (qr) {
        is QrScanUiState.Connecting -> current.copy(pending = true, error = null)
        is QrScanUiState.Success -> HotspotObservation(pending = false, joined = true, error = null)
        is QrScanUiState.Error -> current.copy(pending = false, error = qr.message)
        QrScanUiState.Idle -> current.copy(pending = false)
    }
