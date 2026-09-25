package com.imagedge.camera.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.imagedge.camera.feature.photos.PhotosScreen
import com.imagedge.camera.feature.photos.PhotoViewerScreen
import com.imagedge.camera.feature.camera.CameraHubScreen
import com.imagedge.camera.feature.control.RemoteShootingScreen
import com.imagedge.camera.feature.create.CreateHubScreen
import com.imagedge.camera.feature.transfer.TransferScreen
import com.imagedge.camera.feature.edit.ExifFrameScreen
import com.imagedge.camera.feature.edit.LiveTriptychScreen
import com.imagedge.camera.feature.edit.PhotoEditScreen
import com.imagedge.camera.feature.edit.VideoToLivePhotoScreen
import com.imagedge.camera.feature.profile.CameraProfileScreen
import com.imagedge.camera.feature.settings.PermissionScreen
import com.imagedge.camera.feature.settings.SettingsScreen
import com.imagedge.camera.ui.feedback.SnackbarController
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 目的地注册表：只负责「哪个路由渲染哪个页面、回调指向哪里」
 *     version: 1.0
 * </pre>
 */

/**
 * 路由注册。
 *
 * 这里刻意不做任何业务判断（玻璃、横幅、底栏可见性都在 [AppRoot]）：
 * 一张表能一眼看出「有哪些页面、谁能到谁」，混进状态逻辑后就再也看不出来了。
 *
 * @param navBackdrop 页面内容的采集层，供悬浮导航栏折射；null 表示不采集
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    snackbarController: SnackbarController,
    bottomSlot: BottomSlotHost,
    modifier: Modifier = Modifier,
    navBackdrop: LayerBackdrop? = null
) {
    NavHost(
        navController = navController,
        startDestination = TabDestination.CAMERA.route,
        modifier = modifier.fillMaxSize().then(
            if (navBackdrop != null) Modifier.layerBackdrop(navBackdrop) else Modifier
        )
    ) {
        composable(TabDestination.CAMERA.route) {
            CameraHubScreen(
                // 任务入口直达目标页，而不是绕一圈「首页上的连接卡」
                onOpenPhotos = { navController.selectTab(TabDestination.PHOTOS) },
                onOpenRemote = { navController.openSubDestination(Route.REMOTE) },
                snackbarController = snackbarController
            )
        }
        // 照片 TAB 直接就是照片页：中枢那两张入口卡只是把「换个范围」伪装成「换一页」，
        // 用户要先选进哪个相册才看得到照片，而范围随时可换（设计 §2）
        composable(TabDestination.PHOTOS.route) {
            PhotosScreen(
                onOpenViewer = { index -> navController.openSubDestination(Route.photoViewer(index)) },
                onOpenTransfer = { navController.openSubDestination(Route.TRANSFER) },
                onGoConnect = { navController.selectTab(TabDestination.CAMERA) },
                snackbarController = snackbarController,
                bottomSlot = bottomSlot
            )
        }
        composable(TabDestination.CREATE.route) {
            CreateHubScreen(
                onOpenLivePhoto = { navController.openSubDestination(Route.LIVE_PHOTO) },
                onOpenEdit = { navController.openSubDestination(Route.PHOTO_EDIT) },
                onOpenTriptych = { navController.openSubDestination(Route.LIVE_TRIPTYCH) },
                onOpenExifFrame = { navController.openSubDestination(Route.EXIF_FRAME) },
                // 一级入口没有「返回上一级」：底栏就是它的来路。
                // 必须传 null 而不是 {}——空 lambda 仍会画出返回箭头，点下去什么都不做
                onBack = null
            )
        }
        composable(TabDestination.SETTINGS.route) {
            SettingsScreen(
                onOpenPermissions = { navController.openSubDestination(Route.PERMISSIONS) },
                onOpenProfiles = { navController.openSubDestination(Route.PROFILES) }
            )
        }

        composable(Route.PHOTO_VIEWER) {
            PhotoViewerScreen(
                onBack = { navController.popBackStack() },
                snackbarController = snackbarController
            )
        }
        composable(Route.PHOTO_EDIT) { PhotoEditScreen(onBack = { navController.popBackStack() }) }
        composable(Route.LIVE_PHOTO) {
            VideoToLivePhotoScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.LIVE_TRIPTYCH) {
            LiveTriptychScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.EXIF_FRAME) { ExifFrameScreen(onBack = { navController.popBackStack() }) }
        composable(Route.PERMISSIONS) { PermissionScreen(onBack = { navController.popBackStack() }) }
        composable(Route.PROFILES) {
            CameraProfileScreen(
                onBack = { navController.popBackStack() },
                snackbarController = snackbarController
            )
        }
        composable(Route.REMOTE) {
            RemoteShootingScreen(
                onBack = { navController.popBackStack() },
                snackbarController = snackbarController
            )
        }
        composable(Route.TRANSFER) {
            TransferScreen(
                onBack = { navController.popBackStack() },
                onGoAlbum = { navController.selectTab(TabDestination.PHOTOS) }
            )
        }
    }
}
