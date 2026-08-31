package com.imagedge.camera.core.common

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 轻量日志工具（:core 为纯 JVM 模块，Android 上经反射桥接 android.util.Log）
 *     version: 1.1
 * </pre>
 */

/**
 * 统一日志工具
 *
 * - Android：logcat TAG 为 `CamRemote-<tag>`（如 CamRemote-ptp / CamRemote-upnp / CamRemote-webapi）
 *   真机诊断：`adb logcat -s CamRemote:*`（全部模块）或 `adb logcat -s CamRemote-ptp:*`（单模块）
 * - JVM 单测：退回标准输出
 */
object AppLog {

    private const val PREFIX = "CamRemote"

    /**
     * 详细日志开关：控制 D/I 级是否输出。
     * 默认关闭 —— release 包不输出详细日志（金标安全标准 6.2.1 日志分级：
     * 防止 logcat 泄露 SSID/IP/协议指令等敏感信息）；W/E 级始终输出。
     * App 启动时按 BuildConfig.DEBUG 置 true。
     */
    @Volatile
    var verbose: Boolean = false

    /** 反射获取 android.util.Log 的 d/i/w/e 方法（:core 无 Android 依赖，运行时探测） */
    private val androidLogMethods: Map<Char, Any?> = run {
        val logClass = runCatching { Class.forName("android.util.Log") }.getOrNull()
        mapOf(
            'D' to logClass?.getMethod("d", String::class.java, String::class.java),
            'I' to logClass?.getMethod("i", String::class.java, String::class.java),
            'W' to logClass?.getMethod("w", String::class.java, String::class.java),
            'E' to logClass?.getMethod("e", String::class.java, String::class.java)
        )
    }

    fun d(tag: String, message: String) = log('D', tag, message)

    fun i(tag: String, message: String) = log('I', tag, message)

    fun w(tag: String, message: String) = log('W', tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log('E', tag, message)
        throwable?.printStackTrace()
    }

    private fun log(level: Char, tag: String, message: String) {
        // 详细日志门控：release 下 D/I 不输出，避免敏感信息经 logcat 泄露
        if ((level == 'D' || level == 'I') && !verbose) return
        val fullTag = "$PREFIX-$tag"
        val method = androidLogMethods[level]
        if (method != null) {
            // logcat 单条上限约 4000 字符，超长截断
            val truncated = if (message.length > 3800) message.take(3800) + "…(截断)" else message
            runCatching {
                method as java.lang.reflect.Method
                method.invoke(null, fullTag, truncated)
            }.onFailure { println("[$level][$fullTag] $message") }
        } else {
            println("[$level][$fullTag] $message")
        }
    }
}
