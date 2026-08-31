package com.imagedge.camera.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.imagedge.camera.R
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
        startForeground(NOTIFICATION_ID, buildNotification(null))
        // 观察下载队列：更新通知；队列空闲（空/全部完成/全部失败）时自动停止
        serviceScope.launch {
            downloadManager.tasks.collect { tasks ->
                updateNotification(tasks)
                val allSettled = tasks.all { it.state == DownloadState.DONE || it.state == DownloadState.FAILED }
                if (tasks.isEmpty() || allSettled) {
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
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
            .setOngoing(active != null)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                if (active != null && active.state == DownloadState.DOWNLOADING) active.progress else 0,
                false
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "download_progress"
    }
}
