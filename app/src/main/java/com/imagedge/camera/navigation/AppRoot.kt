package com.imagedge.camera.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.imagedge.camera.data.transfer.TransferMiniBarStore
import com.imagedge.camera.feature.transfer.TransferMiniBar
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.glass.GlassBackdropLayer
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.LocalGlassLevel
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.imagedge.camera.ui.theme.Radius
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 应用根：全局玻璃背景源、全局浮层、底栏可见性（批次 B）
 *     version: 2.0
 * </pre>
 */

/**
 * 根 composable。
 *
 * 只保留**跨页面**的东西：玻璃背景源、顶部横幅、底栏可见性。
 * 路由表在 [AppNavHost]，目的地定义在 [AppDestination]，导航语义在 [NavigationActions]，
 * 之前它们挤在一个 500 行的文件里，改任何一处都要先在一堆无关逻辑里找位置。
 */
@Composable
fun AppRoot(
    snackbarController: SnackbarController,
    bottomSlot: BottomSlotHost,
    transferMiniBar: TransferMiniBarStore
) {
    val navController = rememberNavController()
    // 路由只在这里读一次：底栏要不要画、是不是停在一级入口，都问的是同一件事
    val route = navController.currentRoute()
    val currentTab = TabDestination.fromRoute(route)
    // 底部条位互斥：页面占位时（选择态）悬浮导航与任务条一起让位，
    // 否则三条各自贴底只会互相盖住（设计 §4.3 禁止三层叠加）
    val slotOwner by bottomSlot.owner.collectAsStateWithLifecycle()
    val miniBar by transferMiniBar.bar.collectAsStateWithLifecycle(initialValue = null)
    val bottomLayout = bottomLayoutOf(
        pageOwner = slotOwner,
        miniBar = miniBar,
        onTabPage = currentTab != null
    )
    val showBottomBar = bottomLayout.showNav

    // 全局轻提示：任意页面 show() 一条消息，这里统一以顶部滑入弹窗呈现
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackbarController) {
        snackbarController.messages.collect { bannerMessage = it }
    }
    LaunchedEffect(bannerMessage) {
        if (bannerMessage != null) {
            delay(BANNER_VISIBLE_MS)
            bannerMessage = null
        }
    }

    // 液态玻璃（双背景源，各管一层，互不包含故无递归）：
    // - pageBackdrop：采集「页面背景层」（光晕），供页面内卡片/按钮/开关折射
    // - navBackdrop：采集「NavHost 页面内容」，供悬浮导航栏折射 —— 滚动时内容从
    //   导航栏底下滑过并透出，才是真正的悬浮玻璃（而不是折射一张静态背景）
    // 仅在玻璃可用时才做图层采集——离屏渲染有固定开销，不支持的设备不该白付。
    val glassLevel = rememberGlassLevel()
    val pageBackdrop = rememberLayerBackdrop()
    val navBackdrop = rememberLayerBackdrop()
    val captureBackdrop = glassLevel.warrantsBackdropCapture()
    // 子页没有底栏，没人消费就不必采集整个 NavHost：省掉最重页面上一次全屏离屏渲染
    val captureNavBackdrop = captureBackdrop && showBottomBar

    // 导航胶囊 + 传输小条实测高度换算出的底部留白，Tab 页自己吃（见 LocalNavClearance）
    var navClearance by remember { mutableStateOf(NavClearanceFallback) }
    var miniBarClearance by remember { mutableStateOf(0.dp) }
    val bottomClearance = if (!showBottomBar) 0.dp else {
        // 两条同时贴底：任务条画在胶囊上方，页面要一次让出「胶囊 + 任务条 + 各自的边距」
        navClearance + if (bottomLayout.showMiniBar) miniBarClearance else 0.dp
    }

    CompositionLocalProvider(LocalGlassLevel provides glassLevel) {
        Box(modifier = Modifier.fillMaxSize()) {
            GlassBackdropLayer(backdrop = if (captureBackdrop) pageBackdrop else null)

            // 内容区填满全屏（含状态栏高度，但不预留导航栏高度）—— 这样滚动时
            // 列表项才能真正从底部导航栏底下滑过，被悬浮玻璃折射出来。
            // **不**在这里给所有页面补状态栏 padding：那件事由各页骨架负责（批次 A 的约定）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (captureBackdrop) Color.Transparent
                        else MaterialTheme.colorScheme.background
                    )
            ) {
                CompositionLocalProvider(
                    LocalGlassBackdrop provides if (captureBackdrop) pageBackdrop else null,
                    LocalNavClearance provides bottomClearance
                ) {
                    AppNavHost(
                        navController = navController,
                        bottomSlot = bottomSlot,
                        snackbarController = snackbarController,
                        navBackdrop = if (captureNavBackdrop) navBackdrop else null
                    )
                }
            }

            // 底部这一摞：任务条在上、导航胶囊在下，一起贴在屏幕底边。
            // 刻意**不**放进 Scaffold bottomBar——页面内容要能延伸到导航栏下方，
            // 滚动时从胶囊底下滑过，玻璃才有东西可折射（代价就是要自己量让位高度）。
            if (showBottomBar) {
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    // 传输小条只在一级入口出现，且选择态整摞都不画（bottomLayoutOf 已裁决）。
                    // 它不进 navBackdrop 的采集层：那条背景是「页面内容」，
                    // 把它自己采进去就是自己折射自己（已知坑 2 的递归）。
                    val bar = miniBar
                    if (bottomLayout.showMiniBar && bar != null) {
                        TransferMiniBar(
                            content = bar,
                            onOpen = { navController.openSubDestination(Route.TRANSFER) },
                            onClearanceChanged = { miniBarClearance = it },
                            modifier = Modifier.padding(bottom = Spacing.M)
                        )
                    }
                    FloatingNavBar(
                        backdrop = if (captureNavBackdrop) navBackdrop else null,
                        glassLevel = glassLevel,
                        selected = currentTab,
                        onSelect = { navController.selectTab(it) },
                        onClearanceChanged = { navClearance = it }
                    )
                }
            }
            TopBanner(
                message = bannerMessage,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

/** 顶部滑入弹窗（替代底部 Snackbar）：圆角卡片 + 从顶部滑入/滑出 */
@Composable
private fun TopBanner(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        message?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(Radius.Container),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .statusBarsPadding()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/** 横幅停留时长：够读完一句错误说明，又不至于挡住下面的操作 */
private const val BANNER_VISIBLE_MS = 2500L
