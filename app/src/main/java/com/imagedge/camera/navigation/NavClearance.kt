package com.imagedge.camera.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.UiSize

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 悬浮导航栏占掉的底部高度（批次 B）：由 AppRoot 实测胶囊后下发，页面只读取
 *     version: 1.0
 * </pre>
 */

/** 胶囊自己距屏幕底部的边距（FloatingNavBar 画的时候用同一个值，别各写一份） */
internal val NavBottomMargin = 16.dp

/** 胶囊与最后一行内容之间保留的呼吸 */
private val NavClearanceBreathing = 16.dp

/** 首帧还没量到胶囊高度时的兜底：按下限算，宁可多一点也不要最后一行被压住 */
internal val NavClearanceFallback =
    UiSize.NavigationMin + NavBottomMargin + NavClearanceBreathing

/**
 * 实测胶囊高度 → 页面要留的底部高度。
 *
 * 三段都要算：胶囊本身 + 胶囊离屏幕底边的边距 + 一点呼吸。
 * 只算胶囊的话，页面内容停在的位置正好就是胶囊上沿，最后一行会**贴死**在玻璃边缘
 * （200% 字体下实测就是这样，差的那 16dp 被胶囊自己的底边距吃掉了）。
 */
internal fun navClearanceFor(capsuleHeight: Dp): Dp =
    capsuleHeight + NavBottomMargin + NavClearanceBreathing

/**
 * Tab 页底部要为悬浮导航让出的高度。
 *
 * 为什么要页面自己留：导航胶囊画在页面**之外**（`AppRoot` 的悬浮层），Scaffold 的
 * bottomBar 槽是空的，内容不会被顶到胶囊上方——这正是想要的：列表项从胶囊底下滑过，
 * 玻璃才有东西可折射。代价是滚动内容的最后一段会被胶囊压住，必须自己留出高度。
 *
 * 为什么是「量出来的」而不是一个常量：胶囊高度只设了下限
 * （[UiSize.NavigationMin]），系统字体 200% 时图标 + 标签会把它顶高，
 * 写死的留白在那一刻就小于胶囊本身，列表最后一行永远滑不进可视区——
 * 而这件事只在开大字时才发生，最容易漏测。
 *
 * 默认值 0.dp：不在悬浮导航之下的页面（二级页没有底栏）不该吃这段留白。
 */
val LocalNavClearance = compositionLocalOf { 0.dp }
