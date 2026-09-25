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
import kotlinx.coroutines.flow.StateFlow
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
