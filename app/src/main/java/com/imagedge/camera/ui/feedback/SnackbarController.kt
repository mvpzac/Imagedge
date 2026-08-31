package com.imagedge.camera.ui.feedback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 全局轻提示控制器（规范：成功反馈用 Toast/Snackbar，2~3 秒自动消失）
 *              各页面只负责「发一条消息」，由根布局统一消费并弹出 Snackbar，
 *              避免每个页面各自持有 SnackbarHostState。
 *     version: 1.0
 * </pre>
 */
@Singleton
class SnackbarController @Inject constructor() {

    private val _messages = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4
    )
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 发一条轻提示（失败重试一类的长文案也走这里，Snackbar 会自动换行） */
    fun show(message: String) {
        _messages.tryEmit(message)
    }
}
