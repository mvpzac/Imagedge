package com.imagedge.camera.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 导航动作的唯一出口（批次 B）：Tab 恢复/去重、子页跳转
 *     version: 1.0
 * </pre>
 */

/**
 * 切到某个一级入口。
 *
 * 三条语义都刻意保留：
 * - `popUpTo(start) { saveState = true }`：各 Tab 的位置与状态互相独立，
 *   从照片切去设置再切回来，不该回到相册顶部；
 * - `restoreState = true`：读回上次离开时的状态；
 * - `launchSingleTop = true`：不堆叠同一个目的地。
 *
 * **已经在该 Tab 上时直接返回**：此时 navigate 仍会执行 popUpTo，
 * 表现为「点一下当前 Tab 就把我弹回顶层」——这正是规范禁止的行为
 * （重复点击不发网络请求、不清选择、不返回顶部）。
 */
fun NavHostController.selectTab(tab: TabDestination) {
    if (currentDestination?.route == tab.route) return
    // 先取 id：navigate { } 内的接收者是 NavOptionsBuilder，在那里读 graph 会解析到别处
    val startId = graph.findStartDestination().id
    navigate(tab.route) {
        popUpTo(startId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 进入子页面（遥控、传输、查看器、编辑器…）。
 *
 * 子页**不**清 Tab 的状态栈：从照片列表进大图再返回，滚动位置必须还在。
 * `launchSingleTop` 只为防连点开出两个同样的页面。
 */
fun NavHostController.openSubDestination(route: String) {
    navigate(route) { launchSingleTop = true }
}

/** 当前所处的一级入口；在子页时为 null（底栏据此隐藏） */
@Composable
fun NavHostController.currentTab(): TabDestination? {
    val entry by currentBackStackEntryAsState()
    return TabDestination.fromRoute(entry?.destination?.route)
}
