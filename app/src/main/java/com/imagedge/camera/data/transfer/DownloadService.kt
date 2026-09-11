package com.imagedge.camera.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.imagedge.camera.R
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 下载前台服务——保活 + 通知栏显示下载进度
 *     version: 1.0
 * </pre>
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var downloadManager: DownloadManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        // minSdk 29 → 直接用带 type 的三参重载，显式声明 dataSync
        startForeground(NOTIFICATION_ID, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // 观察下载队列：更新通知；队列空闲（空/全部完成/全部失败）时自动停止
        serviceScope.launch {
            downloadManager.tasks.collect { tasks ->
                updateNotification(tasks)
                val allSettled = tasks.all { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }
                if (tasks.isEmpty()) {
                    stopSelf()
                } else if (allSettled) {
                    // 用 DETACH 而不是直接 stopSelf：默认会连通知一起撤掉，
                    // 用户根本看不到「传输完成/失败」的结果（P0 体验缺陷）。
                    // detach 后通知转为普通通知，由 stopSelf 结束前台服务。
                    runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * P2-7：显式声明 NOT_STICKY。Service 默认返回 START_STICKY，被系统杀死后
     * 会用 null intent 自动重建——重建后 onCreate 里的 tasks.collect 发现队列为空
     * 又立刻 stopSelf，白白拉起一次前台服务（无意义的通知闪烁 + 启动开销）。
     * 下载队列状态完全由 DownloadManager 单例持有，服务只是「有活干时的展示层」，
     * 没必要让系统自动重建。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    /**
     * Android 15+（API 35）对 dataSync 前台服务有 6 小时/天 的运行上限（P0）。
     *
     * 系统在超时前回调本方法，此后**必须**停止前台服务：继续运行会被判为违规
     * （应用被强制停止，后台传输任务一起丢）。这里主动降级——撤下前台状态、
     * 留一条「传输已暂停」的可点击通知，用户回到前台重新入队即可续传。
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(TAG, "前台服务达到 dataSync 时限（6 小时预算），暂停传输前台服务")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching {
            notificationManager.notify(TIMEOUT_NOTIFICATION_ID, buildPausedNotification())
        }
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(task: DownloadTask?): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_camera)
            .setContentTitle(task?.filename ?: "正在传输照片")
            .setContentText(task?.let { "${it.progress}%" } ?: "准备中…")
            .applyContentIntent()
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 「已暂停」通知：前台服务降级后保留，提示用户回到应用继续 */
    private fun buildPausedNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_camera)
            .setContentTitle("传输已暂停")
            .setContentText("后台传输达到系统时限，回到 Imagedge 可继续传输")
            .applyContentIntent()
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    /**
     * 通知点击回到应用（P0）：原先没有任何 contentIntent，
     * 用户看到传输进度却点不开 App，只能自己去桌面找图标。
     */
    private fun NotificationCompat.Builder.applyContentIntent(): NotificationCompat.Builder {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        if (intent == null) return this
        val pending = PendingIntent.getActivity(
            this@DownloadService,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return setContentIntent(pending)
    }

    private fun updateNotification(tasks: List<DownloadTask>) {
        val active = tasks.firstOrNull {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
        }
        val pending = tasks.count { it.state == DownloadState.QUEUED }
        val title = active?.filename ?: "传输完成"
        val text = when {
            active != null && active.state == DownloadState.DOWNLOADING ->
                "下载中 ${active.progress}%" + if (pending > 0) "（还有 $pending 张排队）" else ""
            pending > 0 -> "排队中，共 $pending 张"
            else -> "共 ${tasks.size} 张已处理"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_camera)
            .setContentTitle(title)
            .setContentText(text)
            .applyContentIntent()
            .setOngoing(active != null)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                if (active?.state == DownloadState.DOWNLOADING) active.progress else 0,
                // 真正下载中才显示确定进度；仅排队/无任务时用不确定进度条，
                // 避免界面停在「0%」让人以为卡死
                active?.state != DownloadState.DOWNLOADING
            )
            // 全部结束（无活跃任务）时允许滑动清除，避免常驻一条无意义通知
            .setAutoCancel(active == null)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "download"
        const val NOTIFICATION_ID = 1001
        /** 前台服务超时后的「已暂停」通知，与进度通知分开，避免互相覆盖 */
        const val TIMEOUT_NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "download_progress"
        private const val REQUEST_OPEN_APP = 1
    }
}
