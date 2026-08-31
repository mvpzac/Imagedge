package com.imagedge.camera.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.data.model.ConnectionState
import com.imagedge.camera.data.model.ConnectionStateHolder
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.data.remote.wifi.CameraWifiManager
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
    private val haptics: Haptics
) : ViewModel() {

    val state: StateFlow<ConnectionState> = stateHolder.state

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
                        cameraModel = result.deviceModel
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
