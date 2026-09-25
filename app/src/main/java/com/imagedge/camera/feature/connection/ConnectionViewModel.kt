package com.imagedge.camera.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.data.guidance.GuidanceStore
import com.imagedge.camera.data.model.CameraCapabilities
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.data.model.ConnectionState
import com.imagedge.camera.data.model.ConnectionStateHolder
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.wifi.CameraWifiManager
import com.imagedge.camera.data.transfer.DownloadManager
import com.imagedge.camera.ui.feedback.Haptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 连接状态 ViewModel（主页状态机，交互规格 6.1）
 *     version: 1.0
 * </pre>
 */

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: CameraRepository,
    private val cameraWifiManager: CameraWifiManager,
    private val stateHolder: ConnectionStateHolder,
    downloadManager: DownloadManager,
    private val guidanceStore: GuidanceStore,
    private val haptics: Haptics
) : ViewModel() {

    val state: StateFlow<ConnectionState> = stateHolder.state

    /**
     * 相机当次上报的能力（由 0x9209 描述符派生，含 UNKNOWN / UNSUPPORTED 之分）。
     *
     * 首页状态卡要说「快门能不能用」只能转述这个，不能按型号猜——
     * 同一台机器换连接方式、换功能模式，档位表就不一样。
     */
    val capabilities: StateFlow<CameraCapabilities> = repository.capabilities

    /** 是否有传输正在进行。断开相机前必须说明影响，所以这个信号得在首页可读 */
    val transferActive: StateFlow<Boolean> = downloadManager.hasActiveDownload

    // ── 连接向导的阶段机（批次 D）────────────────────────────────────────

    private val _stage = MutableStateFlow(WizardStage.PrepareCamera)
    val stage: StateFlow<WizardStage> = _stage.asStateFlow()

    private val _path = MutableStateFlow(ConnectPath.Qr)

    /**
     * 扫码配网的观测结果。
     *
     * [joined] 是**粘住**的：QrScanViewModel 在步骤离开组合时会 release()，
     * 而 release 在成功态下刻意保留存活的配网请求（释放即断开热点，见
     * docs/sony-protocol-notes.md §3），同时把 UI 状态清回 Idle。
     * 如果向导跟着瞬时状态走，「热点已连上」会在下一步显示成「还没开始」。
     */
    private val _hotspot = MutableStateFlow(HotspotObservation())

    /** 一次尝试的完整输入 + 算出来的步骤与出口 */
    val wizard: StateFlow<WizardView> = combine(_stage, _path, _hotspot, state, capabilities) {
            stage, path, hotspot, connection, caps ->
        wizardViewOf(stage, path, hotspot, connection, caps.descriptorRead)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, wizardViewOf(
        stage = WizardStage.PrepareCamera,
        path = ConnectPath.Qr,
        hotspot = HotspotObservation(),
        connection = ConnectionState(),
        descriptorRead = false
    ))

    /** 扫码步骤把它那个 VM 的状态报上来（单向：向导不抢配网实现的所有权） */
    fun onHotspotObserved(qr: QrScanUiState) {
        _hotspot.update { hotspotAfter(it, qr) }
    }

    /** 会话连上了就把阶段推到成功页；只认用户**已经**走到连接阶段的情况，
     *  后台自己重连成功不该把用户从别的页抢走（设计 §2 导航约束） */
    fun onSessionConnected() {
        if (_stage.value == WizardStage.Connect) _stage.value = WizardStage.Success
    }

    /** 准备页 → 扫码 */
    fun startScan() {
        _path.value = ConnectPath.Qr
        _stage.value = WizardStage.ScanQr
    }

    /** 发起会话连接（扫码配网成功后走这条，热点步骤已完成） */
    fun startSession(host: String? = null) {
        _stage.value = WizardStage.Connect
        connect(host)
    }

    /**
     * 准备页 → 「手机已经在相机热点上」/ 手动 IP。
     * 两者都不发起配网请求，所以 Wi-Fi 那一步是「不适用」而不是「已完成」。
     */
    fun startDirectSession(host: String?) {
        _path.value = ConnectPath.ManualIp
        startSession(host)
    }

    /** 回到准备页（不清已连上的热点：重扫一次是白等） */
    fun backToPrepare() {
        _stage.value = WizardStage.PrepareCamera
    }

    /** 准备页的继续按钮：热点还在就直接进连接，不再要求扫一遍 */
    fun continueFromPrepare() {
        if (_hotspot.value.joined) startSession() else startScan()
    }

    /** 重跑失败的 Wi-Fi 步骤 = 回到扫码，并把扫码 VM 清干净（由界面调它的 reset） */
    fun retryWifi() {
        _hotspot.value = HotspotObservation()
        _stage.value = WizardStage.ScanQr
    }

    fun retrySession() {
        connect()
    }

    /**
     * 取消本次连接请求。
     *
     * 只在**没**连上热点时回收配网请求：热点请求一旦成功就必须留着（留着才不断），
     * 那种情况下离开向导交给会话层，用户要断开走工作台的「断开」。
     */
    fun cancelAttempt() {
        if (!_hotspot.value.joined) cameraWifiManager.releaseNetworkRequest()
        _hotspot.value = HotspotObservation()
        _stage.value = WizardStage.PrepareCamera
    }

    // ── 引导与连接动作 ─────────────────────────────────────────────────

    /**
     * 引导卡是否该出现。按机型记忆「已看过」；
     * 「任务成功过」不在这里判——那是传输完成事件的事（批次 C），首页没有这个信号。
     */
    fun shouldShowGuide(guideId: String): Boolean =
        guidanceStore.shouldShow(guideId, state.value.cameraModel)

    fun dismissGuide(guideId: String) {
        guidanceStore.markDismissed(guideId, state.value.cameraModel)
    }

    /**
     * 连接相机（自动网关发现 + 通道路由；[host] 为手动指定 IP 时的兜底）
     */
    fun connect(host: String? = null) {
        if (stateHolder.state.value.phase == ConnectionPhase.CONNECTING) return
        viewModelScope.launch {
            stateHolder.update { it.copy(phase = ConnectionPhase.CONNECTING, errorMessage = null) }
            try {
                val result = repository.connect(host)
                stateHolder.update {
                    ConnectionState(
                        phase = ConnectionPhase.CONNECTED,
                        channelType = result.channelType,
                        cameraModel = result.identity.model
                    )
                }
                haptics.thud()
            } catch (e: Exception) {
                stateHolder.update { ConnectionState(ConnectionPhase.ERROR, errorMessage = e.message ?: "连接失败") }
                haptics.double()
            }
        }
    }

    /** 断开连接（含释放扫码配网建立的相机热点连接请求） */
    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            cameraWifiManager.releaseNetworkRequest()
            cameraWifiManager.unbindProcessNetwork()
            stateHolder.update { ConnectionState(ConnectionPhase.DISCONNECTED) }
        }
    }
}
