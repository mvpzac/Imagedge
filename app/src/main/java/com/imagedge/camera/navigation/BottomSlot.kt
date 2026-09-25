package com.imagedge.camera.navigation

import com.imagedge.camera.data.transfer.TransferBarContent
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

/**
 * 页面自己声明的底部占位。[Navigation] 是默认值，即悬浮导航胶囊。
 *
 * 传输小条**不**在这里：它不是某个页面占的位，而是从全局队列状态算出来的事实，
 * 由 [bottomBarOf] 与页面占位一起裁决（见 [AppRoot]）。让它也来 claim 会多出一个
 * 「谁把它挤掉了、什么时候还回来」的状态，而那正是底部条位最容易出 bug 的地方。
 */
enum class BottomSlotOwner { Navigation, SelectionBar }

/**
 * 底部条位的唯一持有者。
 *
 * 为什么需要一个全局的「谁占着底部」而不是各画各的：设计 §4.3 明确禁止
 * 「底部导航 + 任务条 + 保存按钮」三层叠加。三条各自 `align(Bottom)` 的话，
 * 它们只会互相盖住，谁都不知道该让位——互斥必须是**一处**的决定。
 *
 * 页面进入选择态时 [claim]，退出或离页时 [release]；
 * [AppRoot] 用 [bottomLayoutOf] 把「页面占了什么」与「有没有传输任务」合成**一个**结论。
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

/** 屏幕底部这一帧该画什么。两条可以共存（设计 §8.3「其他Tab时 TransferMiniBar＋导航」），
 *  但**三条不行**：任务条绝不会和选择态保存条同时出现。 */
data class BottomLayout(
    val showNav: Boolean,
    val showMiniBar: Boolean
) {
    companion object {
        val Empty = BottomLayout(showNav = false, showMiniBar = false)
    }
}

/**
 * 底部该画什么——整个应用只有这一处做这个决定。
 *
 * 三条规则，逐条都有它的理由：
 * - **选择态独占**：PhotosScreen 自己画 [SelectionActionBar] 占住底部，
 *   此时既不画导航也不画任务条。这正是设计禁止的「导航 + 任务条 + 保存按钮」三层叠加，
 *   所以互斥必须是**一处**的决定，三条各自 `align(Bottom)` 只会互相盖住；
 * - **只在一级入口出现**：二级页（编辑器、大图、遥控）的内容区自己贴着系统底部安全区，
 *   没有任何东西为条位预留高度，浮一条上去就是压住最后一行——那正是批次 C 验收要排除的事。
 *   传输页本身就在看同一批任务，再摆一条也只是重复；
 * - **无任务即消失**：`miniBar` 为 null（没在传、也没有没看过的失败）时这一条不存在。
 *
 * @param pageOwner 页面自己声明的占位（[BottomSlotOwner.SelectionBar] 或默认值）
 * @param miniBar 传输小条该说什么；null 表示此刻没有值得打扰用户的事
 * @param onTabPage 当前是否停在四个一级入口（子页为 false）
 */
fun bottomLayoutOf(
    pageOwner: BottomSlotOwner,
    miniBar: TransferBarContent?,
    onTabPage: Boolean
): BottomLayout = when {
    pageOwner == BottomSlotOwner.SelectionBar -> BottomLayout.Empty
    !onTabPage -> BottomLayout.Empty
    else -> BottomLayout(showNav = true, showMiniBar = miniBar != null)
}
