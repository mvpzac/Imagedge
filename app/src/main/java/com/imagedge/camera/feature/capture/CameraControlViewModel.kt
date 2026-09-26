package com.imagedge.camera.feature.capture

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.lifecycle.ViewModel
import com.imagedge.camera.core.common.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.data.ble.BleShutterState
import com.imagedge.camera.data.ble.SonyBleShutter
import com.imagedge.camera.image.ExposureAnalysis
import com.imagedge.camera.image.ExposureStats
import com.imagedge.camera.image.FrameGate
import com.imagedge.camera.data.capture.CaptureJob
import com.imagedge.camera.data.capture.CaptureMachine
import com.imagedge.camera.data.capture.CapturePhase
import com.imagedge.camera.data.capture.CaptureRoute
import com.imagedge.camera.data.capture.HeldKey
import com.imagedge.camera.data.capture.IntervalScheduler
import com.imagedge.camera.data.guidance.GuidanceStore
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CameraIdentity
import com.imagedge.camera.data.model.CameraSettings
import com.imagedge.camera.data.model.CapabilityDetail
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.data.model.AspectMarker
import com.imagedge.camera.data.model.GridMode
import com.imagedge.camera.data.model.MonitoringSettings
import com.imagedge.camera.data.model.MonitoringSettingsStore
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.CameraSnapshot
import com.imagedge.camera.data.remote.ChannelConnectionState
import com.imagedge.camera.data.remote.LiveViewRepository
import com.imagedge.camera.data.transfer.AutoSaveLedger
import com.imagedge.camera.data.transfer.DownloadManager
import com.imagedge.camera.data.transfer.TransferPolicy
import com.imagedge.camera.data.transfer.TransferPolicyStore
import com.imagedge.camera.feature.capture.monitoring.ViewfinderSnapshotWriter
import com.imagedge.camera.ui.feedback.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-27
 *     desc   : 相机控制 ViewModel（连接 / 拍照 / 参数调节）
 *     version: 2.0 —— 参数控制全面改为能力驱动（T0）：不再有任何硬编码可写档位
 * </pre>
 */

/**
 * 单个拍摄参数在界面上的呈现。
 *
 * 三态由相机能力快照决定：可写且有档位 → 选择器；只读/不支持 → 只读文本；
 * 未知（未探测或读取超时）→ 禁用并解释。[editable] 为 false 时本 ViewModel 不下发命令，
 * [CameraRepository] 层还有同一份校验兜底——「界面禁用」与「不会发命令」必须是同一件事。
 */
data class ParamUiState(
    val currentLabel: String?,
    val options: List<Pair<String, Long>>,
    val detail: CapabilityDetail,
    val editable: Boolean
)

/** 控制状态 */
data class ControlState(
    val isConnected: Boolean = false,
    val connecting: Boolean = false,
    val taking: Boolean = false,
    val message: String? = null,
    /** 相机身份（型号 / 固件 / 传输方式 / 功能模式），未连接时为 [CameraIdentity.UNKNOWN] */
    val identity: CameraIdentity = CameraIdentity.UNKNOWN,
    /** 能力快照是否陈旧（本轮尚未成功探测）——此时只可展示，不可据此下发命令 */
    val capabilitiesStale: Boolean = true,
    /** 各拍摄参数的呈现状态（键为能力项，未探测时为空表） */
    val params: Map<CameraCapability, ParamUiState> = emptyMap(),
    /** PTP 遥控拍摄是否可用（BLE 快门是另一条独立通路，不由此项决定） */
    val captureAvailable: Boolean = false,
    /**
     * 遥控拍摄的能力态（WRITABLE / UNKNOWN / UNSUPPORTED）。
     *
     * 与 [captureAvailable] 并存是故意的：前者说**能不能按**，后者说**为什么不能按**。
     * 只留一个布尔就会把「这个模式没实测过」显示成「这台相机不支持」。
     */
    val captureSupport: CapabilityState = CapabilityState.UNKNOWN,
    /**
     * 当前拍摄任务（T3）。null = 从未拍过或已清空。
     *
     * 界面据此显示倒计时、阶段与「已确认/仅已发送」的区别——
     * 之前那句「已拍摄（BLE）」在超时路径上也是假的。
     */
    val capture: CaptureJob? = null,
    /** 是否忙（有活跃任务或间隔拍摄在跑）。忙时快门一律拒绝 */
    val busy: Boolean = false,
    /** 间隔拍摄是否在跑 */
    val intervalRunning: Boolean = false,
    /** 取景是否暂停（暂停 = 采集 Job 已取消，画面定格在最后一帧） */
    val viewfinderPaused: Boolean = false,
    /** 取景截图正在写盘：期间屏蔽重复触发，避免连着建出多个条目 */
    val snapshotting: Boolean = false
)

/**
 * LiveView 取景目标尺寸（3:2）。
 *
 * 从 1280×854 下调到 960×640：单帧内存从 4.4MB 降到 2.4MB（-45%）。
 * 取景用途不需要更高分辨率（真机 960 宽已足够判断构图与对焦），
 * 而每帧都是新分配的软件位图，分辨率直接决定 GC 压力。
 */
private const val LIVEVIEW_TARGET_WIDTH = 960
private const val LIVEVIEW_TARGET_HEIGHT = 640

/**
 * LiveView 最小出帧间隔（约 20fps 上限）。
 *
 * 相机推流约 18~30fps，但手机端没必要全收：每帧都要解码 + 分配位图 + 触发重组，
 * 全速接收会让 GC 与 UI 线程持续高负载。20fps 对取景观感无差别，却把分配速率砍掉三成。
 */
private const val LIVEVIEW_MIN_FRAME_INTERVAL_MS = 50L

/**
 * 节流：保证两次发射之间至少间隔 [periodMs]，超出部分丢弃。
 *
 * 与 [kotlinx.coroutines.flow.conflate] 的区别：conflate 只在**下游来不及消费**时
 * 丢弃中间值（背压处理），本操作符是主动限速，从源头减少位图分配。
 */
private fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = flow {
    var lastEmitAt = 0L
    collect { value ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEmitAt >= periodMs) {
            lastEmitAt = now
            emit(value)
        }
    }
}

@HiltViewModel
class CameraControlViewModel @Inject constructor(
    private val liveViewRepository: LiveViewRepository,
    private val cameraRepository: CameraRepository,
    private val bleShutter: SonyBleShutter,
    private val downloadManager: DownloadManager,
    private val haptics: Haptics,
    private val monitoringStore: MonitoringSettingsStore,
    private val snapshotWriter: ViewfinderSnapshotWriter,
    private val transferStore: TransferPolicyStore,
    private val autoSaveLedger: AutoSaveLedger,
    private val guidanceStore: GuidanceStore
) : ViewModel() {

    private val _state = MutableStateFlow(ControlState())
    val state: StateFlow<ControlState> = _state.asStateFlow()

    /** 监看偏好（旋转/镜像/网格/比例标记）。直接暴露存储的流：嵌入预览与工作台共用同一份 */
    val monitoringSettings: StateFlow<MonitoringSettings> = monitoringStore.settings

    /** 传输策略（范围/尺寸/续传/拍后自动保存）。遥控页与下载页读的是同一份 */
    val transferPolicy: StateFlow<TransferPolicy> = transferStore.policy

    /** 拍后自动保存开关。开启本身不拉取任何数据，只允许后续的新文件进入队列 */
    fun setAutoSaveAfterCapture(enabled: Boolean) =
        transferStore.update { it.copy(autoSaveAfterCapture = enabled) }

    private fun updateMonitoring(transform: (MonitoringSettings) -> MonitoringSettings) =
        monitoringStore.update(transform)

    /** 显示级旋转：切到下一个 90° 档位（只改监看显示，不动相机文件朝向） */
    fun rotateView() = updateMonitoring { it.copy(rotation = it.rotation.rotate90Clockwise()) }

    fun setMirrored(mirrored: Boolean) = updateMonitoring { it.copy(mirrored = mirrored) }

    /** 网格档位循环（NONE → 三分 → 四分 → NONE）。与比例标记是两个独立开关 */
    fun cycleGrid() = updateMonitoring {
        it.copy(gridMode = when (it.gridMode) {
            GridMode.NONE -> GridMode.THIRD
            GridMode.THIRD -> GridMode.QUARTER
            GridMode.QUARTER -> GridMode.NONE
        })
    }

    /** 比例标记档位循环（NONE → 16:9 → 17:9 → 4:3 → 3:2 → 1:1 → 2.35:1 → NONE） */
    fun cycleAspectMarker() = updateMonitoring {
        val order = AspectMarker.entries
        it.copy(aspectMarker = order[(order.indexOf(it.aspectMarker) + 1) % order.size])
    }

    /** BLE 快门连接状态（Disconnected/Scanning/Connecting/Connected） */
    val bleState = bleShutter.state

    /** 相机实时状态（ff02 通知：对焦/快门/录像，三态；断线后为未知而非「未录像」） */
    val cameraStatus = bleShutter.cameraStatus

    /** 已认知的相册内容指纹集合，用于「拍摄后增量拉取」差异对比 */
    private val lastKnownThumbKeys = mutableSetOf<String>()

    /**
     * 当前拍摄任务。
     *
     * 用普通字段而不是 StateFlow：它只被本 ViewModel 的协程读写，界面读的是
     * [ControlState.capture] 这份快照。两处各自演进就会出现「界面显示的任务与正在发的命令不一致」。
     */
    private var job: CaptureJob? = null

    /** 手势是否已按下半按（决定 shutterUp 是该拍还是该忽略） */
    private var gestureFocused = false

    private var jobSeq = 0

    private var intervalFlow: Job? = null

    @Volatile
    private var intervalRunning = false

    /**
     * 相册基线是否已就绪（P1-14）。
     *
     * [init] 里的 captureEvents 收集在 ViewModel 创建时就开始，而 [lastKnownThumbKeys]
     * 要等 [connect] 中 `listMedia()`（可达 10~30s）返回后才填充。这个窗口期内若收到
     * CaptureComplete，差集 = 整张相册 → `enqueueAll(全部)`，整卡模式下就是几千个任务
     * 瞬间入队（下载风暴 + UI 卡死）。基线未就绪时直接跳过自动拉回。
     */
    @Volatile
    private var baselineReady = false

    init {
        // 相机端自己按快门的场景（App 没发起拍摄）：仅在用户开启自动保存时拉回，并过账本去重
        viewModelScope.launch {
            cameraRepository.captureEvents.collect { _ -> autoSaveCameraSideFiles() }
        }
        // 参数双向同步：相机端拨盘/菜单改动参数 → 0xC203/0x4006 事件推送 →
        // 300ms 合并去抖 → 重读 0x9209 回显（手机显示始终以相机实际状态为准）。
        // 换镜头/换拍摄模式导致的档位变化同样经这条路径刷新。
        viewModelScope.launch {
            cameraRepository.propEvents
                .debounce(300)
                .collect { refreshCameraState() }
        }
        // 断线（保活失败 / 事务超时自愈 forceClose / 用户断开）→ 立即回到「未知」。
        // 否则界面会继续显示上一轮的可写档位，用户点下去才发现命令全部失败。
        viewModelScope.launch {
            cameraRepository.connectionState.collect { channelState ->
                val connected = channelState == ChannelConnectionState.CONNECTED
                _state.update { it.copy(isConnected = connected) }
                if (!connected) {
                    applySnapshot(
                        CameraSnapshot(
                            CameraIdentity.UNKNOWN,
                            CameraSettings(),
                            CameraCapabilities.UNKNOWN
                        )
                    )
                    // 断线时正在跑的拍摄必须落到终态：否则界面会挂着一个永远等不到确认的任务，
                    // 而间隔循环会在断掉的通道上继续按快门
                    intervalRunning = false
                    intervalFlow?.cancel()
                    intervalFlow = null
                    job?.takeIf { it.phase.isActive }?.let { current ->
                        val step = CaptureMachine.onDisconnected(current)
                        job = step.job
                        sendReleases(step.releases)
                        publishCapture()
                    }
                }
            }
        }
    }

    /** 开始扫描蓝牙遥控相机（需蓝牙权限已授予、相机端已开启「蓝牙遥控」） */
    fun startBleScan() = bleShutter.startScan()

    /** 断开蓝牙遥控 */
    fun disconnectBle() = bleShutter.disconnect()

    /** 蓝牙权限被拒绝时给用户明确提示（避免静默失败） */
    /**
     * 关掉那条常驻结果说明。
     *
     * 设计 §4.6 要求「拍后保存限制与保存结果用 StatusBanner，不闪过」——不闪过就意味着
     * 它得留在屏上直到用户主动收掉，所以这里需要一个真的能收掉的入口，
     * 而不是等下一次操作把它覆盖掉（那会让用户以为消息又变成新结果）。
     */
    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun notifyBlePermissionDenied() {
        _state.update { it.copy(message = "蓝牙权限未授予，无法连接蓝牙遥控——请在系统设置中允许") }
    }

    /**
     * 快门：BLE 已连接时走蓝牙（低延迟可靠，拍摄成功率高）；
     * 否则降级 PTP InitiateCapture（ZV-E10 上固件存在已知怪癖，可能超时）。
     */
    /**
     * 快门操作提示该不该说（设计 §4.6「首次或操作模式变化时」+ §5「记录按机型与模式分开」）。
     *
     * 机型与操作模式**一起进键**：换了机器要重新说，同一台机器换了拍摄模式也要重新说——
     * 那时「按住会怎样」这件事本身变了。用户点过「知道了」就压制，直到下一次变化。
     */
    fun shouldShowShutterHint(model: String, mode: Int): Boolean =
        guidanceStore.shouldShow(SHUTTER_GUIDE_ID, "$model#$mode")

    fun dismissShutterHint() {
        val identity = _state.value.identity
        guidanceStore.markDismissed(SHUTTER_GUIDE_ID, "${identity.model}#${identity.mode}")
    }

    /**
     * 快门按下（手势开始）：半按对焦（0x07）。
     * 与物理快门两段式一致：按下对焦，抬起（[shutterUp]）拍摄。
     * BLE 未连接时降级 PTP 拍摄（一次性触发）。
     */
    fun shutterDown() {
        if (bleShutter.state.value is BleShutterState.Connected) {
            // 间隔拍摄跑着的时候不接受手动快门：两路同时按快门就是叠加
            if (CaptureMachine.isBusy(job) || intervalRunning) {
                _state.update { it.copy(message = "相机正在拍摄中，未叠加快门") }
                return
            }
            haptics.thud()
            // 手势路径先建任务并记下半按已按下——抬起时 runBleCapture 复用同一个任务，
            // 释放命令才不会发给一个界面上看不见的新任务
            val started = CaptureMachine.onHalfPressSent(
                CaptureMachine.begin(nextJobId(), CaptureRoute.BLE, System.currentTimeMillis())
            )
            job = started
            gestureFocused = true
            publishCapture()
            viewModelScope.launch { bleShutter.halfPress() }
            _state.update { it.copy(message = "对焦中…（松开拍摄）") }
        } else {
            captureNow(countdownMs = 0L)
        }
    }

    /**
     * 快门抬起（手势结束）：全按拍摄（0x09）→ 回位（0x08）→ 半按抬起（0x06，结束对焦）。
     * 码表（alpharemote ButtonCode）：0x07 半按按下 / 0x06 半按松开；0x09 全按按下 / 0x08 全按松开。
     * 缺 0x06 会导致对焦状态残留（状态胶囊一直亮对焦）。
     */
    fun shutterUp() {
        if (bleShutter.state.value !is BleShutterState.Connected) return
        if (!gestureFocused) return
        gestureFocused = false
        viewModelScope.launch { runBleCapture(halfAlreadyPressed = true) }
    }

    /**
     * 程序化拍摄（倒计时按钮、间隔拍摄、遥控触发都走这里）。
     *
     * @param countdownMs 本地倒计时；期间一个命令都不发出去，取消它就是「什么都没发生」
     */
    fun captureNow(countdownMs: Long = 0L) {
        if (CaptureMachine.isBusy(job)) {
            _state.update { it.copy(message = "上一次拍摄还没结束，未叠加快门") }
            return
        }
        val ble = bleShutter.state.value is BleShutterState.Connected
        viewModelScope.launch {
            if (countdownMs > 0L && !awaitCountdown(countdownMs)) return@launch
            if (ble) runBleCapture(halfAlreadyPressed = false) else runPtpCapture()
        }
    }

    /**
     * 本地倒计时。返回 false 表示在发命令之前就被取消了。
     *
     * 这是「倒计时取消不拍照」唯一需要保证的地方：这段时间里函数不会碰任何通道。
     */
    private suspend fun awaitCountdown(countdownMs: Long): Boolean {
        val endsAt = System.currentTimeMillis() + countdownMs
        job = CaptureJob(
            id = nextJobId(),
            route = if (bleShutter.state.value is BleShutterState.Connected) CaptureRoute.BLE else CaptureRoute.PTP,
            phase = CapturePhase.COUNTING_DOWN,
            startedAt = System.currentTimeMillis(),
            countdownEndsAt = endsAt
        )
        publishCapture()
        while (true) {
            delay(COUNTDOWN_TICK_MS)
            val current = job ?: return false
            val ticked = CaptureMachine.tick(current, System.currentTimeMillis())
            job = ticked
            publishCapture()
            if (ticked.phase != CapturePhase.COUNTING_DOWN) return true
        }
    }

    /** 取消当前拍摄。倒计时中取消 = 没拍过；已触发则尽最大努力释放按键 */
    fun cancelCapture() {
        val current = job ?: return
        intervalRunning = false
        intervalFlow?.cancel()
        intervalFlow = null
        val step = CaptureMachine.cancel(current)
        job = step.job
        sendReleases(step.releases)
        publishCapture()
        _state.update {
            it.copy(
                message = if (current.countingDown) "已取消，未拍摄"
                else "已取消本次拍摄，快门按键已释放"
            )
        }
    }

    /**
     * BLE 拍摄的完整序列。
     *
     * 释放命令写在 `finally` 里（[releaseAndSettle]）：正常结束、超时、异常、协程被取消
     * 都走同一个出口。之前这段是平铺的 launch，被离页打断时快门会留在按下状态。
     *
     * @param halfAlreadyPressed 手势路径已由 [shutterDown] 发过半按，这里不能重复按
     */
    private suspend fun runBleCapture(halfAlreadyPressed: Boolean) {
        // 复用倒计时或手势已建的任务；没有才新建。丢掉原任务会让界面上的倒计时序号凭空跳号
        val base = job?.takeIf { it.phase.isActive }
            ?: CaptureMachine.begin(nextJobId(), CaptureRoute.BLE, System.currentTimeMillis())
        var current = base
        try {
            if (!halfAlreadyPressed) {
                bleShutter.halfPress()
                current = CaptureMachine.onHalfPressSent(current)
                job = current
                delay(EXPOSURE_SETTLE_MS)
            }
            bleShutter.shutterPress()
            current = CaptureMachine.onShutterPressSent(current)
            job = current
            publishCapture()

            // 等相机 ff02 回 shutter=true。命令发出去不等于拍下来了，这句是本次改动的核心。
            val triggered = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                bleShutter.cameraStatus.first { it.shutter == true }
            } != null
            if (!triggered) {
                current = CaptureMachine.onNotConfirmed(current).job
                job = current
                _state.update { it.copy(message = "快门命令已发出，但相机未确认拍摄完成") }
                haptics.double()
                return
            }
            current = CaptureMachine.onCameraConfirmed(current)
            job = current
            delay(EXPOSURE_SETTLE_MS)
            bleShutter.shutterRelease()
            delay(EXPOSURE_SETTLE_MS)
            bleShutter.halfRelease()
            current = CaptureMachine.onKeysReleased(current)
            job = current
            publishCapture()
            // 静默 PTP 让相机安心写卡（保存目的地设为「手机+拍摄装置」时这一步明显更慢）
            cameraRepository.silencePtp(WRITE_CARD_SETTLE_MS)
            awaitNewFile(WRITE_CARD_SETTLE_MS)
        } finally {
            releaseAndSettle()
        }
    }

    /**
     * 收尾：把仍按住的键送回相机，并把还在活跃态的任务落到终态。
     *
     * 刻意不挂起——它跑在 `finally` 里，协程可能已经被取消，任何 suspend 调用都会立刻抛出
     * 并让后面的释放命令发不出去。断线时 [SonyBleShutter.enqueue] 会丢弃命令，
     * 那是通道层的既成事实，状态机这边至少保证任务不落空。
     */
    private fun releaseAndSettle() {
        val current = job ?: return
        val releases = current.outstandingReleases
        if (releases.isNotEmpty()) sendReleases(releases)
        if (current.phase.isActive) {
            job = CaptureMachine.onNoFile(current).job
        }
        gestureFocused = false
        publishCapture()
    }

    /** PTP 降级通道：InitiateCapture 是一次性触发，确认来自 CaptureComplete 事件 */
    private suspend fun runPtpCapture() {
        job = CaptureMachine.begin(nextJobId(), CaptureRoute.PTP, System.currentTimeMillis())
        publishCapture()
        _state.update { it.copy(taking = true) }
        try {
            val sent = runCatching { cameraRepository.takePicture() }.isSuccess
            if (!sent) {
                val failed = CaptureMachine.onRefused(job ?: return)
                job = failed.job
                haptics.double()
                return
            }
            job = CaptureMachine.onShutterPressSent(job!!)
            publishCapture()
            // 相机回了 CaptureComplete 才算「拍到了」；没等到就如实报未确认
            val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                cameraRepository.captureEvents.first()
            } != null
            job = if (confirmed) {
                CaptureMachine.onCameraConfirmed(job!!)
            } else {
                CaptureMachine.onNotConfirmed(job!!).job
            }
            publishCapture()
            if (confirmed) {
                job = CaptureMachine.onKeysReleased(job!!)
                awaitNewFile(WRITE_CARD_SETTLE_MS)
            } else {
                _state.update { it.copy(message = "快门命令已发出，但相机未确认拍摄完成") }
                haptics.double()
            }
        } finally {
            _state.update { it.copy(taking = false) }
            publishCapture()
        }
    }

    /**
     * 重扫相册，把新照片按策略交给下载队列。
     *
     * @param settleMs 先给相机写卡的时间，再去比对差集——早一步扫到的结果是「没有新照片」
     */
    private suspend fun awaitNewFile(settleMs: Long) {
        if (settleMs > 0L) delay(settleMs)
        val before = job ?: return
        val items = runCatching { cameraRepository.listMedia() }.getOrNull()
        val newItems = items?.filter { it.thumbKey !in lastKnownThumbKeys }.orEmpty()
        items?.let {
            lastKnownThumbKeys.clear()
            lastKnownThumbKeys.addAll(it.map { item -> item.thumbKey })
        }
        if (newItems.isEmpty()) {
            job = CaptureMachine.onNoFile(before).job
            // 智能手机连接 + BLE 快门的已知限制：拍下的照片不会进 0xF10001 待传集
            _state.update {
                it.copy(message = "相机已确认拍摄，但内容集里没有新照片——智能手机连接下需在相机端「发送到智能手机」选片")
            }
            publishCapture()
            return
        }
        // 只有用户主动开了自动保存才入队；且必须过账本，重复事件不重复落盘
        val policy = transferStore.policy.value
        if (policy.autoSaveAfterCapture) {
            val fresh = autoSaveLedger.claimAll(newItems.map { item -> item.thumbKey })
            if (fresh.isNotEmpty()) {
                downloadManager.enqueueAll(newItems.filter { it.thumbKey in fresh.toSet() })
                // 入队 ≠ 落盘。这里说「已保存」就是抢在事实前面报喜（新手手册 §4：
                // 确认照片落盘后才说「已保存到手机」），存没存成要到传输页看那一条的结果
                _state.update { it.copy(message = "已加入下载队列 ${fresh.size} 张，存进相册后会在传输页显示") }
            } else {
                _state.update { it.copy(message = "这些照片此前已加入过下载，未重复保存") }
            }
        } else {
            // 手册 §4 要的是下一步，不是一个状态括号：照片在相机上，怎么进手机得说清。
            // 也绝不能写成「已保存」——一张都还没落到手机里
            _state.update {
                it.copy(
                    message = "已拍摄 ${newItems.size} 张，还在相机上；" +
                        "自动保存没开，请在相机「发送到智能手机」里选片后保存到手机"
                )
            }
        }
        job = CaptureMachine.onFileSeen(job ?: before).job
        publishCapture()
        haptics.thud()
    }

    /** 把状态机算出的释放命令发给相机 */
    private fun sendReleases(keys: List<HeldKey>) {
        keys.forEach { key ->
            when (key) {
                HeldKey.FULL_PRESS -> bleShutter.shutterRelease()
                HeldKey.HALF_PRESS -> bleShutter.halfRelease()
            }
        }
    }

    private fun nextJobId(): Int = ++jobSeq

    private fun publishCapture() {
        val current = job
        _state.update {
            it.copy(
                capture = current,
                busy = current?.phase?.isActive == true || intervalRunning,
                intervalRunning = intervalRunning
            )
        }
    }

    // ── 间隔拍摄 ─────────────────────────────────────────────────────

    /**
     * 开启/停止间隔拍摄。
     *
     * 调度按「上次完成 + 间隔」而不是按时钟排期：相机写卡慢时按时钟排期会撞上还在忙的设备，
     * 表现就是突然连拍。忙时一律不起下一张，宁可少拍。
     */
    fun setIntervalShooting(running: Boolean, intervalMs: Long, shotLimit: Int) {
        if (running == intervalRunning) return
        if (!running) {
            stopInterval()
            return
        }
        if (intervalMs < INTERVAL_MIN_MS || shotLimit <= 0) {
            _state.update { it.copy(message = "间隔参数超出允许范围") }
            return
        }
        if (CaptureMachine.isBusy(job)) {
            _state.update { it.copy(message = "等当前这张结束后才能开始间隔拍摄") }
            return
        }
        intervalRunning = true
        publishCapture()
        intervalFlow = viewModelScope.launch {
            var taken = 0
            var lastFinished: Long? = null
            while (isActive && intervalRunning && taken < shotLimit) {
                val now = System.currentTimeMillis()
                if (IntervalScheduler.shouldStart(now, lastFinished, intervalMs, CaptureMachine.isBusy(job))) {
                    taken++
                    _state.update { it.copy(message = "间隔拍摄 $taken/$shotLimit") }
                    val ble = bleShutter.state.value is BleShutterState.Connected
                    if (ble) runBleCapture(halfAlreadyPressed = false) else runPtpCapture()
                    lastFinished = System.currentTimeMillis()
                    job = null
                    publishCapture()
                } else {
                    delay(INTERVAL_POLL_MS)
                }
            }
            intervalRunning = false
            if (taken >= shotLimit) _state.update { it.copy(message = "间隔拍摄完成，共 $taken 张") }
            publishCapture()
        }
    }

    /** 停止间隔拍摄：先停循环，再取消当前那张（当前那张会负责释放按键） */
    private fun stopInterval() {
        intervalRunning = false
        intervalFlow?.cancel()
        intervalFlow = null
        if (job != null) cancelCapture() else publishCapture()
    }

    /** 录像开始/停止切换（按下 0x0F + 松开 0x0E；切换后短暂静默 PTP 让相机写视频） */
    fun recordToggle() {
        haptics.thud()
        bleShutter.record()
        cameraRepository.silencePtp(5000)
    }

    /**
     * 取景截图：把当前预览帧存到本机相册。
     *
     * 只读已收到的这一帧，**不向相机发任何命令**，也不进下载队列——
     * 它记录的是监看信号，不是相机文件（详见 [ViewfinderSnapshotWriter] 的边界说明）。
     */
    fun captureSnapshot() {
        val frame = currentFrame
        if (frame == null) {
            _state.update { it.copy(message = "还没有可截图的取景画面") }
            return
        }
        if (_state.value.snapshotting) return
        val width = frame.width
        val height = frame.height
        _state.update { it.copy(snapshotting = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { snapshotWriter.write(frame) }
            _state.update {
                it.copy(
                    snapshotting = false,
                    message = if (result.isSuccess) "取景截图已保存（${width}×$height）"
                    else "取景截图保存失败：${result.exceptionOrNull()?.message}"
                )
            }
            if (result.isSuccess) haptics.thud() else haptics.double()
        }
    }

    // ── 取景帧（单点采集）─────────────────────────────────────────────

    /**
     * 当前取景帧。
     *
     * 由本 ViewModel 用**唯一一个**采集 Job 喂给 [frame]，而不是把 cold flow 丢给界面：
     * `LiveViewRepository` 是 @Singleton 且只持有一个 `LiveViewClient`，若遥控页的嵌入预览
     * 与监看工作台各自 collect 一次，就会同时开两条 60152 流打到同一个客户端上——
     * 表现为画面互相干扰或其中一条静默断流。
     *
     * StateFlow 天然合流：解码或渲染跟不上帧率时永远只显示最新帧，不积压陈帧。
     */
    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    /** 「取景截图」的数据源。不预先复制位图：解码出的帧此后只读 */
    val currentFrame: Bitmap? get() = _frame.value

    // ── 曝光辅助（T2）───────────────────────────────────────────────

    /**
     * 直方图与斑马纹的统计，null = 没开或还没有可分析的画面。
     *
     * 三条约束照差距文档 T2 原样落地：**停用时零分析**（gate 关掉就一次都不算）、
     * **最多 5Hz**（20fps 的帧每 4 帧最多取 1 帧）、**不留队列**
     * （就在采集协程里同步算，算不过来的帧直接被 throttleLatest 丢掉）。
     * 像素缓冲按帧尺寸复用，不每帧新建——960×640 的 IntArray 是 2.4MB，
     * 每秒分配 5 次就是给 GC 打工。
     */
    private val _exposureStats = MutableStateFlow<ExposureStats?>(null)
    val exposureStats: StateFlow<ExposureStats?> = _exposureStats.asStateFlow()

    private val exposureGate = FrameGate { System.currentTimeMillis() }
    private var analysisPixels: IntArray? = null
    private var analysisWidth = 0
    private var analysisHeight = 0

    fun setExposureAids(on: Boolean) {
        exposureGate.enabled = on
        if (!on) _exposureStats.value = null
    }

    /** 关掉辅助时别把上一轮的读数留在屏幕上——它会看起来像实时数据 */
    private fun analyseExposure(frame: Bitmap) {
        if (!exposureGate.admit()) return
        val width = frame.width
        val height = frame.height
        val buffer = analysisPixels
        val pixels = if (buffer != null && analysisWidth == width && analysisHeight == height) {
            buffer
        } else {
            IntArray(width * height).also {
                analysisPixels = it; analysisWidth = width; analysisHeight = height
            }
        }
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        val stride = ExposureAnalysis.sampleStride(width, height)
        _exposureStats.value = ExposureStats(
            histogram = ExposureAnalysis.histogram(pixels, width, height, stride),
            highlightRatio = ExposureAnalysis.highlightRatio(pixels, width, height, stride)
        )
    }

    private var viewfinderVisible = false
    private var viewfinderPaused = false
    private var viewfinderJob: Job? = null

    /** 页面可见性（遥控页按 ON_START/ON_STOP 驱动）。退后台必须真的停采 */
    fun setViewfinderVisible(visible: Boolean) {
        if (viewfinderVisible == visible) return
        viewfinderVisible = visible
        syncViewfinderCollection()
    }

    /**
     * 暂停取景（监看工作台工具栏）。
     *
     * 停的是**采集**——取消 Job，60152 socket 随之关闭；不是只在 UI 层冻住画面。
     * 只停渲染的话相机仍在持续推流，射频与耗电都没省下来。最后一帧留在 [frame] 里
     * 供定格显示，符合「暂停取景」的直觉。
     */
    fun setViewfinderPaused(paused: Boolean) {
        if (viewfinderPaused == paused) return
        viewfinderPaused = paused
        _state.update { it.copy(viewfinderPaused = paused) }
        syncViewfinderCollection()
    }

    /** 电脑遥控/智能手机连接两种模式相机都开放 LiveView（60152）；解码失败帧静默跳过 */
    private fun syncViewfinderCollection() {
        val shouldCollect = viewfinderVisible && !viewfinderPaused
        if (shouldCollect && viewfinderJob == null) {
            viewfinderJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    liveViewRepository.liveViewFrames()
                        .throttleLatest(LIVEVIEW_MIN_FRAME_INTERVAL_MS)
                        .collect { jpeg ->
                            // 解码内联在 collect 里：形成天然背压，不会堆出无界队列
                            decodeScaled(jpeg, LIVEVIEW_TARGET_WIDTH, LIVEVIEW_TARGET_HEIGHT)
                                ?.let { frame ->
                                    _frame.value = frame
                                    // 采集已经在 Dispatchers.Default 上，分析就地做：
                                    // 再 launch 一次只是多一个调度点，还可能堆出并发分析
                                    analyseExposure(frame)
                                }
                        }
                } catch (e: Exception) {
                    // 流异常只停止取景，不崩，也不清空当前帧——定格比黑屏有用
                    AppLog.w("liveview", "LiveView 流异常（停止取景）：${e.message}")
                }
            }
        } else if (!shouldCollect) {
            viewfinderJob?.cancel()
            viewfinderJob = null
        }
    }

    /**
     * 下采样解码 LiveView JPEG 帧（失败返回 null 静默跳过）。
     *
     * **必须用软件位图**（P1-8）：原先的 ALLOCATOR_HARDWARE 把像素分配在独立
     * GraphicBuffer 池，既不受 Bitmap.recycle() 控制，也不随 Java GC 及时回收。
     * 18fps × 1280×854×4B ≈ **79MB/秒**的分配速率，取景开一会儿就会耗尽
     * GraphicBuffer → 画面卡死乃至进程被系统杀掉。
     * 软件位图挂在 Java 对象上、GC 可回收，配合上面的 [throttleLatest] 限流即可长期稳定。
     *
     * 未采用 inBitmap 复用：Compose 的 Image 可能仍在绘制上一帧，复用同一块内存
     * 会造成画面撕裂，权衡后选择「降分辨率 + 限流 + 可 GC 的软件位图」这一更稳的组合。
     */
    private fun decodeScaled(jpeg: ByteArray, targetW: Int, targetH: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(jpeg))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val srcW = info.size.width
            val srcH = info.size.height
            if (srcW > 0 && srcH > 0) {
                val scale = minOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
                if (scale < 1f) {
                    decoder.setTargetSize((srcW * scale).toInt(), (srcH * scale).toInt())
                }
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        AppLog.d("liveview", "帧解码失败（静默跳过）：${e.message}")
        null
    }

    /**
     * 进入控制面板工作态：真正建立相机连接，再建相册基线并探测能力。
     *
     * 参数走 PTP DeviceProp、快门走 BLE/PTP、LiveView 走 60152 裸流，
     * 均不依赖索尼 Web API（ZV-E10 无此服务，相关代码已于 2026-08-29 清除）。
     *
     * 修复（P1-1）：原实现**从不调用** `cameraRepository.connect()`，只是把
     * isConnected 直接置 true —— 一个纯粹的假状态。相机实际没连上时，
     * 参数读取静默返回，用户看到空白面板且没有任何错误提示，无法判断到底是
     * 没连上还是相机不支持。
     */
    fun connect() {
        if (_state.value.connecting) return
        viewModelScope.launch {
            _state.update { it.copy(connecting = true, message = null) }
            val result = runCatching { cameraRepository.connect() }
                .onFailure { AppLog.w("control", "连接相机失败：${it.message}") }
                .getOrNull()
            val ok = result != null
            _state.update {
                it.copy(
                    connecting = false,
                    isConnected = ok,
                    message = if (ok) null
                    else "连接相机失败：请确认手机已连上相机 WiFi，且相机已进入对应模式"
                )
            }
            if (!ok) return@launch
            // 先同步通道**自我声明**的能力（不发 0x9209 往返）：遥控拍摄不依赖描述符，
            // 而下面的相册基线扫描可达 10~30s，等它跑完再同步会让快门一直压在禁用态
            applySnapshot(
                CameraSnapshot(
                    cameraRepository.identity.value,
                    CameraSettings(),
                    cameraRepository.capabilities.value
                )
            )
            // 基线相册内容（供拍摄后增量拉取）+ 探测参数能力并回显
            runCatching { cameraRepository.listMedia() }.getOrNull()?.let { items ->
                lastKnownThumbKeys.clear()
                lastKnownThumbKeys.addAll(items.map { it.thumbKey })
                // P1-14：基线就绪后才允许「拍摄后自动拉回」，否则首个 CaptureComplete
                // 会把整张相册当成新照片灌进下载队列
                baselineReady = true
            }
            refreshCameraState()
        }
    }

    /**
     * 离开遥控页时终止 BLE 扫描/配对/GATT 与取景采集，
     * 不断开仍供相册使用的 Wi-Fi/PTP 会话。
     *
     * **先停拍摄再断 BLE**：GATT 一关，释放命令就发不出去了（SonyBleShutter.enqueue 在未连接时
     * 直接丢弃），所以必须在 disconnect 之前把还按着的键送回。
     */
    fun leaveScreen() {
        intervalRunning = false
        intervalFlow?.cancel()
        intervalFlow = null
        cancelCapture()
        bleShutter.disconnect()
        // 停采并交还这一帧的内存（960×640 软件位图约 2.4MB，没页面在看就不该留着）
        viewfinderVisible = false
        viewfinderPaused = false
        syncViewfinderCollection()
        _frame.value = null
        _state.update {
            it.copy(isConnected = false, taking = false, viewfinderPaused = false, intervalRunning = false)
        }
    }

    override fun onCleared() {
        // UI 正常导航会调 leaveScreen；此处覆盖进程内非正常销毁路径。
        bleShutter.disconnect()
        super.onCleared()
    }

    // ── 参数下发（全部经能力校验）──────────────────────────────────────

    /** 设置 ISO（原始值：低 24 位 = ISO，0x00FFFFFF = Auto） */
    fun setIso(raw: Long) = writeParam(CameraCapability.ISO, raw, "ISO") { cameraRepository.setIso(it) }

    /** 设置光圈（原始值 = f 值 ×100） */
    fun setFNumber(raw: Long) =
        writeParam(CameraCapability.F_NUMBER, raw, "光圈") { cameraRepository.setFNumber(it) }

    /** 设置快门（原始值：高 16 分子 / 低 16 分母） */
    fun setShutterSpeed(raw: Long) =
        writeParam(CameraCapability.SHUTTER_SPEED, raw, "快门") { cameraRepository.setShutterSpeed(it) }

    /** 设置白平衡 */
    fun setWhiteBalance(code: Long) =
        writeParam(CameraCapability.WHITE_BALANCE, code, "白平衡") { cameraRepository.setWhiteBalance(it) }

    /** 设置曝光补偿（有符号 EV×1000） */
    fun setExposureBias(raw: Long) =
        writeParam(CameraCapability.EXPOSURE_BIAS, raw, "曝光补偿") { cameraRepository.setExposureBias(it) }

    /** 设置照相模式 */
    fun setShootMode(raw: Long) =
        writeParam(CameraCapability.EXPOSURE_PROGRAM_MODE, raw, "照相模式") {
            cameraRepository.setExposureProgramMode(it)
        }

    /**
     * 参数下发唯一入口。
     *
     * 能力未确认可写（未知 / 只读 / 不支持 / 快照陈旧 / 相机没给可选档位）时**不发命令**，
     * 只提示原因。不做「先乐观更新 UI 再等相机」——相机拒绝时界面会留下一个假值。
     */
    private fun writeParam(
        capability: CameraCapability,
        raw: Long,
        label: String,
        send: suspend (Long) -> Boolean
    ) {
        val param = _state.value.params[capability]
        if (param?.editable != true) {
            val state = param?.detail?.state ?: CapabilityState.UNKNOWN
            AppLog.w("control", "界面阻止下发 $label：能力状态=$state（${param?.detail?.note}）")
            _state.update { it.copy(message = "$label 当前不可调整") }
            return
        }
        viewModelScope.launch {
            val ok = runCatching { send(raw) }.getOrDefault(false)
            _state.update {
                it.copy(
                    message = if (ok) "$label 已设为 ${CameraCapabilities.labelOf(capability, raw)}"
                    else "$label 设置失败（相机未响应或已断开）"
                )
            }
            refreshCameraState()
        }
    }

    // ── 能力探测与回显 ─────────────────────────────────────────────────

    /**
     * 探测能力并回显参数（一次 0x9209 往返同时得到能力快照与当前值）。
     *
     * 探测失败时回落到仓库当前快照（未连接即全 UNKNOWN），界面随之禁用全部控件——
     * 绝不保留上一轮的「可写」态。
     */
    private suspend fun refreshCameraState() {
        val snapshot = runCatching { cameraRepository.refreshCapabilities() }
            .onFailure { AppLog.w("control", "读取相机能力失败：${it.message}") }
            .getOrNull()
            ?: CameraSnapshot(
                cameraRepository.identity.value,
                CameraSettings(),
                cameraRepository.capabilities.value
            )
        applySnapshot(snapshot)
    }

    private fun applySnapshot(snapshot: CameraSnapshot) {
        val capabilities = snapshot.capabilities
        _state.update {
            it.copy(
                identity = snapshot.identity,
                capabilitiesStale = capabilities.stale,
                captureAvailable = capabilities.canWrite(CameraCapability.CAPTURE),
                captureSupport = capabilities.stateOf(CameraCapability.CAPTURE),
                params = PARAMETERS.associateWith { capability ->
                    paramOf(capabilities, capability, snapshot.settings)
                }
            )
        }
    }

    private fun paramOf(
        capabilities: CameraCapabilities,
        capability: CameraCapability,
        settings: CameraSettings
    ): ParamUiState {
        val raw = when (capability) {
            CameraCapability.ISO -> settings.isoRaw
            CameraCapability.F_NUMBER -> settings.fNumberRaw
            CameraCapability.SHUTTER_SPEED -> settings.shutterRaw
            CameraCapability.EXPOSURE_PROGRAM_MODE -> settings.exposureProgramMode
            CameraCapability.WHITE_BALANCE -> settings.whiteBalance
            CameraCapability.EXPOSURE_BIAS -> settings.exposureBias
            CameraCapability.CAPTURE -> null
        }
        val options = capabilities.optionsFor(capability)
        return ParamUiState(
            currentLabel = raw?.let { CameraCapabilities.labelOf(capability, it) },
            options = options,
            detail = capabilities.detail(capability),
            // 可写但相机没给档位（既无枚举表也无取值范围）时同样禁用：
            // 没有相机确认过的取值可发，硬凑一份档位表就是「未经验证的可写参数」
            editable = capabilities.canWrite(capability) && options.isNotEmpty()
        )
    }

    /**
     * 相机端按下快门（App 没发起拍摄）时的自动保存。
     *
     * 只服务这一个场景，因此**不碰** [job]：App 发起的拍摄由状态机自己收尾，
     * 这里再改一次会把两条路搅在一起。自动路径必须过 [AutoSaveLedger]——
     * CaptureComplete 与内容事件可能各来一次，队列的既有去重只覆盖进行中的任务，
     * 完成后重复入队会真的多写一份文件。
     */
    private suspend fun autoSaveCameraSideFiles() {
        if (!transferStore.policy.value.autoSaveAfterCapture) return
        // App 自己发起的拍摄由状态机收尾（含去重与提示），这里再处理一次就是双份消息
        if (CaptureMachine.isBusy(job)) return
        // 基线未就绪：此刻的差集等于「整张相册」，不能据此入队（详见 baselineReady 注释）
        if (!baselineReady) {
            AppLog.d("control", "相册基线尚未就绪，跳过本次自动保存")
            return
        }
        val items = runCatching { cameraRepository.listMedia() }.getOrNull() ?: return
        val newItems = items.filter { it.thumbKey !in lastKnownThumbKeys }
        lastKnownThumbKeys.clear()
        lastKnownThumbKeys.addAll(items.map { it.thumbKey })
        if (newItems.isEmpty()) return
        val fresh = autoSaveLedger.claimAll(newItems.map { it.thumbKey }).toSet()
        val pending = newItems.filter { it.thumbKey in fresh }
        if (pending.isEmpty()) {
            AppLog.d("control", "收到 ${newItems.size} 个新对象，但都已自动保存过，未重复落盘")
            return
        }
        downloadManager.enqueueAll(pending)
        _state.update { it.copy(message = "已自动保存 ${pending.size} 张到下载队列") }
    }

    private companion object {
        /** 快门操作提示的引导键（改版就换后缀，旧的「已看过」不该压制新说明） */
        const val SHUTTER_GUIDE_ID = "shutter-sequence-v1"

        /** 倒计时刷新步长：100ms 足够让界面读数不跳字，又不至于每帧都重算 */
        const val COUNTDOWN_TICK_MS = 100L

        /** 快门触发后留给曝光的时间，也是回位与抬半按之间的间隔（沿用 T0 实测经验值） */
        const val EXPOSURE_SETTLE_MS = 500L

        /** 等相机 ff02 回 shutter=true 的上限。超时即判「未确认」，不再报「已拍摄」 */
        const val CONFIRM_TIMEOUT_MS = 3000L

        /** 拍后静默 PTP、等写卡的时间（相机「保存目的地=手机+拍摄装置」时明显更慢） */
        const val WRITE_CARD_SETTLE_MS = 5000L

        /** 间隔拍摄调度轮询步长 */
        const val INTERVAL_POLL_MS = 200L

        /** 间隔下限：低于这个值就是在跟相机写卡抢通道 */
        const val INTERVAL_MIN_MS = 1000L

        /** 遥控面板展示的参数（固定顺序）；CAPTURE 不是参数，单独由 captureAvailable 表达 */
        val PARAMETERS = listOf(
            CameraCapability.EXPOSURE_PROGRAM_MODE,
            CameraCapability.ISO,
            CameraCapability.F_NUMBER,
            CameraCapability.SHUTTER_SPEED,
            CameraCapability.WHITE_BALANCE,
            CameraCapability.EXPOSURE_BIAS
        )
    }
}
