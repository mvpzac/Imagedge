package com.imagedge.camera.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.imagedge.camera.ui.feedback.SnackbarController
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
fun AppRoot(snackbarController: SnackbarController) {
    val navController = rememberNavController()
    val currentTab = navController.currentTab()
    val showBottomBar = currentTab != null

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

    // 导航胶囊实测高度换算出的底部留白，Tab 页自己吃（见 LocalNavClearance）
    var navClearance by remember { mutableStateOf(NavClearanceFallback) }

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
                    LocalNavClearance provides if (showBottomBar) navClearance else 0.dp
                ) {
                    AppNavHost(
                        navController = navController,
                        snackbarController = snackbarController,
                        navBackdrop = if (captureNavBackdrop) navBackdrop else null
                    )
                }
            }

            // 悬浮玻璃导航栏：叠在外层 Box 底部（**不**放进 Scaffold bottomBar），
            // 这样页面内容才能真正延伸到导航栏下方
            if (showBottomBar) {
                FloatingNavBar(
                    backdrop = if (captureNavBackdrop) navBackdrop else null,
                    glassLevel = glassLevel,
                    selected = currentTab,
                    onSelect = { navController.selectTab(it) },
                    onClearanceChanged = { navClearance = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
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
