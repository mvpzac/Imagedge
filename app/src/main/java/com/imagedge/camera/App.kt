package com.imagedge.camera

import android.app.Application
import com.imagedge.camera.core.common.AppLog
import dagger.hilt.android.HiltAndroidApp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 应用入口（Hilt 依赖注入根）
 *     version: 1.1
 * </pre>
 */
@HiltAndroidApp
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 日志分级（金标安全标准 6.2.1）：debug 包输出 D/I 详细日志便于真机诊断，
        // release 包只保留 W/E，防止 SSID/IP/协议指令经 logcat 泄露
        AppLog.verbose = BuildConfig.DEBUG
    }
}
