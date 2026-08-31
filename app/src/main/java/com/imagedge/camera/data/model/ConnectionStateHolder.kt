package com.imagedge.camera.data.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 连接状态共享持有者——主页 / 设置页（手动 IP）等多入口连接
 *              共用同一份状态，任一入口连接/断开，所有页面实时同步。
 *     version: 1.0
 * </pre>
 */
@Singleton
class ConnectionStateHolder @Inject constructor() {

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun update(state: ConnectionState) {
        _state.value = state
    }

    fun update(transform: (ConnectionState) -> ConnectionState) {
        _state.value = transform(_state.value)
    }
}
