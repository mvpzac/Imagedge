package com.imagedge.camera.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 底部条位互斥（批次 C）：同一屏只允许一条底部栏占位
 *     version: 1.0
 * </pre>
 */

/** 谁在占底部。[Navigation] 是默认值，即悬浮导航胶囊 */
enum class BottomSlotOwner { Navigation, SelectionBar, TransferMiniBar }

/**
 * 底部条位的唯一持有者。
 *
 * 为什么需要一个全局的「谁占着底部」而不是各画各的：设计 §4.3 明确禁止
 * 「底部导航 + 任务条 + 保存按钮」三层叠加。三条各自 `align(Bottom)` 的话，
 * 它们只会互相盖住，谁都不知道该让位——互斥必须是**一处**的决定。
 *
 * 页面进入选择态时 [claim]，退出或离页时 [release]；
 * [AppRoot] 只在 [BottomSlotOwner.Navigation] 时画悬浮导航。
 */
@Singleton
class BottomSlotHost @Inject constructor() {

    private val _owner = MutableStateFlow(BottomSlotOwner.Navigation)
    val owner: StateFlow<BottomSlotOwner> = _owner.asStateFlow()

    fun claim(owner: BottomSlotOwner) {
        _owner.value = owner
    }

    /** 只在自己的那份还没被抢走时交还，避免 A 页退出把 B 页刚占的位清掉 */
    fun release(owner: BottomSlotOwner) {
        if (_owner.value == owner) _owner.value = BottomSlotOwner.Navigation
    }
}
