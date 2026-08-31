package com.imagedge.camera

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import coil.imageLoader
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.core.permission.AppPermissions
import com.imagedge.camera.data.model.MediaSessionCache
import com.imagedge.camera.data.remote.CameraRepository
import com.imagedge.camera.feature.root.RootScreen
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
 *     desc   : 主 Activity（Compose 根，3-Tab 导航宿主）
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
            val dynamicColor by themeController.dynamicColor.collectAsState()
            val brandColor by themeController.brandColor.collectAsState()
            // 首次进入：统一申请运行所需权限（拒绝后不影响其他功能，用到时顶部弹窗说明）
            RequestRequiredPermissions()
            ImagedgeTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                },
                dynamicColor = dynamicColor,
                brandColor = brandColor
            ) {
                // UI 锁定：全屏等比缩放到设计基准宽（394dp），跨机型保持版式不变
                DesignScaleLocked {
                    RootScreen(snackbarController = snackbarController)
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

/** 首次进入统一申请运行所需权限（按 SDK 版本动态筛选，已授予的跳过） */
@Composable
private fun RequestRequiredPermissions() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            AppLog.i("permission", "用户未授予：${denied.joinToString()}")
        }
    }
    LaunchedEffect(Unit) {
        val needed = AppPermissions.ungranted(
            android.os.Build.VERSION.SDK_INT
        ) { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            AppLog.i("permission", "首次启动申请权限：$needed")
            launcher.launch(needed.toTypedArray())
        }
    }
}
