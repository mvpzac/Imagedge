package com.imagedge.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import coil.imageLoader
import com.imagedge.camera.data.model.MediaSessionCache
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.navigation.AppRoot
import com.imagedge.camera.navigation.BottomSlotHost
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.theme.DesignScaleLocked
import com.imagedge.camera.ui.theme.ImagedgeTheme
import com.imagedge.camera.ui.theme.ThemeController
import com.imagedge.camera.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 主 Activity（Compose 根，四入口导航宿主）
 *     version: 1.2
 * </pre>
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @javax.inject.Inject
    lateinit var themeController: ThemeController

    @javax.inject.Inject
    lateinit var snackbarController: SnackbarController

    @javax.inject.Inject
    lateinit var bottomSlot: BottomSlotHost

    @javax.inject.Inject
    lateinit var cameraRepository: CameraRepository

    @javax.inject.Inject
    lateinit var sessionCache: MediaSessionCache

    override fun onStart() {
        super.onStart()
        // 功耗标准：回前台恢复 PTP 保活
        cameraRepository.setAppInBackground(false)
    }

    override fun onStop() {
        super.onStop()
        // 功耗标准：退后台暂停 PTP 保活（活跃下载时 DownloadManager 会豁免）
        cameraRepository.setAppInBackground(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themeController.mode.collectAsState()
            ImagedgeTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                }
            ) {
                // UI 锁定：全屏等比缩放到设计基准宽（394dp），跨机型保持版式不变
                DesignScaleLocked {
                    AppRoot(
                        snackbarController = snackbarController,
                        bottomSlot = bottomSlot
                    )
                }
            }
        }
    }

    /**
     * 公平运行内存机制响应（T/TAF 358 + 金标公平内存适配）：
     * 收到系统内存预警后及时释放缓存，避免触达查杀阈值导致进程被结束、现场丢失。
     * LiveView 流本身已由 collectAsStateWithLifecycle 在退后台时断开，此处负责图片缓存。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI 隐藏（退后台）或系统运行内存吃紧时，清空图片内存缓存；
        // 磁盘缓存保留，回前台重新加载时从磁盘快速恢复，不产生新的网络/IO 压力
        if (level == TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_RUNNING_LOW) {
            imageLoader.memoryCache?.clear()
            // 相册网格/大图查看器共享的缩略图位图缓存一并释放。
            // 这是进程内最大的一块原生内存占用（数百张位图），不释放会直接顶到查杀阈值。
            // 只丢弃引用，不 recycle —— 位图可能仍被屏幕上未销毁的 Image 持有。
            sessionCache.clearThumbnails()
        }
    }
}
