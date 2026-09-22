package com.imagedge.camera.core.permission

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.imagedge.camera.ui.feedback.SnackbarController

/**
 * 返回一个“下载即将开始”时调用的通知权限请求器。
 *
 * 通知权限不是下载的硬前置：即使用户拒绝，也不中断传输；只是不能在
 * 通知栏持续展示进度。因此这个函数只负责解释并发起系统请求，不返回门禁结果。
 */
@Composable
fun rememberNotificationPermissionRequester(
    snackbarController: SnackbarController,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝时仍允许下载，不额外打扰 */ }

    return remember(context, snackbarController, launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionGate.check(
                    context = context,
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    snackbar = snackbarController,
                    ask = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
        }
    }
}
