package com.imagedge.camera.core.permission

import android.Manifest
import android.os.Build
import com.imagedge.camera.R

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/30
 *     desc   : 应用权限清单（诚实披露 + 按需申请）。
 *              危险权限在首次启动统一申请；被拒后使用相关功能时顶部弹窗说明用途。
 *              普通权限系统安装即授予，仅用于权限说明页的诚实披露。
 *     version: 1.0
 * </pre>
 */
object AppPermissions {

    /**
     * 权限条目
     * @param permission 系统权限名
     * @param labelRes 权限名称（中文短标签）
     * @param purposeRes 用途说明（诚实、具体）
     * @param minSdk 生效的最低 API（默认全版本）
     * @param maxSdk 生效的最高 API
     */
    data class PermissionEntry(
        val permission: String,
        val labelRes: Int,
        val purposeRes: Int,
        val minSdk: Int = 1,
        val maxSdk: Int = Int.MAX_VALUE
    ) {
        fun appliesTo(sdk: Int): Boolean = sdk in minSdk..maxSdk
    }

    /**
     * 危险权限（用户可拒绝，需运行时申请）。
     * 首次启动统一申请；拒绝后使用对应功能时顶部弹窗说明。
     */
    val dangerous: List<PermissionEntry> = listOf(
        PermissionEntry(
            Manifest.permission.CAMERA,
            R.string.permission_camera_label,
            R.string.permission_camera_purpose
        ),
        PermissionEntry(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            R.string.permission_nearby_wifi_label,
            R.string.permission_nearby_wifi_purpose,
            minSdk = Build.VERSION_CODES.TIRAMISU
        ),
        PermissionEntry(
            Manifest.permission.POST_NOTIFICATIONS,
            R.string.permission_notification_label,
            R.string.permission_notification_purpose,
            minSdk = Build.VERSION_CODES.TIRAMISU
        ),
        PermissionEntry(
            Manifest.permission.BLUETOOTH_CONNECT,
            R.string.permission_bluetooth_connect_label,
            R.string.permission_bluetooth_connect_purpose,
            minSdk = Build.VERSION_CODES.S
        ),
        PermissionEntry(
            Manifest.permission.BLUETOOTH_SCAN,
            R.string.permission_bluetooth_scan_label,
            R.string.permission_bluetooth_scan_purpose,
            minSdk = Build.VERSION_CODES.S
        ),
        PermissionEntry(
            Manifest.permission.ACCESS_FINE_LOCATION,
            R.string.permission_location_label,
            R.string.permission_location_purpose,
            maxSdk = Build.VERSION_CODES.S_V2
        )
    )

    /**
     * 普通权限（系统安装即授予，无需申请，不能被用户关闭）。
     * 权限说明页诚实列出，让用户知道应用声明了什么。
     */
    val normal: List<PermissionEntry> = listOf(
        PermissionEntry(
            Manifest.permission.INTERNET,
            R.string.permission_internet_label,
            R.string.permission_internet_purpose
        ),
        PermissionEntry(
            Manifest.permission.ACCESS_NETWORK_STATE,
            R.string.permission_network_state_label,
            R.string.permission_network_state_purpose
        ),
        PermissionEntry(
            Manifest.permission.CHANGE_NETWORK_STATE,
            R.string.permission_change_network_label,
            R.string.permission_change_network_purpose
        ),
        PermissionEntry(
            Manifest.permission.ACCESS_WIFI_STATE,
            R.string.permission_wifi_state_label,
            R.string.permission_wifi_state_purpose
        ),
        PermissionEntry(
            Manifest.permission.CHANGE_WIFI_STATE,
            R.string.permission_change_wifi_label,
            R.string.permission_change_wifi_purpose
        ),
        PermissionEntry(
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            R.string.permission_multicast_label,
            R.string.permission_multicast_purpose
        ),
        PermissionEntry(
            Manifest.permission.FOREGROUND_SERVICE,
            R.string.permission_foreground_label,
            R.string.permission_foreground_purpose
        ),
        PermissionEntry(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            R.string.permission_location_coarse_label,
            R.string.permission_location_coarse_purpose,
            maxSdk = Build.VERSION_CODES.S_V2
        )
    )

    /** 当前系统下需要运行时申请、且尚未授予的权限 */
    fun ungranted(sdk: Int, granted: (String) -> Boolean): List<String> =
        dangerous.filter { it.appliesTo(sdk) && !granted(it.permission) }.map { it.permission }

    /** 按权限名查条目（含普通权限） */
    fun find(permission: String): PermissionEntry? =
        (dangerous + normal).firstOrNull { it.permission == permission }
}
