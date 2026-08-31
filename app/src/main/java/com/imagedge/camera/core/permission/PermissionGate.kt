package com.imagedge.camera.core.permission

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.imagedge.camera.R
import com.imagedge.camera.ui.feedback.SnackbarController

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 权限门禁——使用功能前检查权限，未授予时顶部弹窗诚实说明用途。
 *     version: 1.0
 * </pre>
 */
object PermissionGate {

    /** 是否已授予 */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 检查权限；未授予时顶部弹窗说明所需权限与用途，并可触发申请。
     * @param ask 申请回调（UI 层 launcher），null 则仅提示
     * @return true = 已授予，可直接使用
     */
    fun check(
        context: Context,
        permission: String,
        snackbar: SnackbarController,
        ask: (() -> Unit)? = null
    ): Boolean {
        if (isGranted(context, permission)) return true
        val entry = AppPermissions.find(permission) ?: return false
        snackbar.show(
            context.getString(
                R.string.permission_denied_hint,
                context.getString(entry.labelRes),
                context.getString(entry.purposeRes)
            )
        )
        ask?.invoke()
        return false
    }
}
