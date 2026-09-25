package com.imagedge.camera.feature.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.core.common.AppLog
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.transfer.DownloadHistoryDao
import com.imagedge.camera.data.transfer.DownloadHistoryEntity
import com.imagedge.camera.data.transfer.DownloadManager
import com.imagedge.camera.data.transfer.TransferBatchSummary
import com.imagedge.camera.data.transfer.TransferPolicy
import com.imagedge.camera.data.transfer.TransferPolicyStore
import com.imagedge.camera.data.transfer.batchSummaryOf
import com.imagedge.camera.data.transfer.readablePathOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-08-27
 *     desc   : 传输页状态（队列 + 批次摘要 + 记录），以及「打开已存照片」这条出口
 *     version: 3.0
 * </pre>
 */

/**
 * 页面上一次性提示（不是队列状态）：哪里走不通了、要不要顺手分享给别的应用。
 *
 * [shareUri] 非空 = 这条提示自带「分享」动作。之所以是 Uri 字符串而不是 [android.net.Uri]：
 * 提示要能跨过重组存活，而 Uri 不是 Saveable，界面状态统一用字符串承载更省心。
 */
data class TransferNotice(
    val message: String,
    val shareUri: String? = null
)

/** [TransferViewModel.openSaved] 的三种结局 */
enum class OpenOutcome {
    /** 已经交给系统打开 */
    Opened,

    /** 没有应用认领这个格式：可以改走分享 */
    NoApp,

    /** Uri 读不出来（没权限 / 已被删 / 解析不了）：分享同样会失败，只能报位置 */
    NotReadable
}

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    private val historyDao: DownloadHistoryDao,
    transferPolicyStore: TransferPolicyStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val tasks: StateFlow<List<DownloadTask>> = downloadManager.tasks

    /** 生效中的传输策略（尺寸/续传）。范围不在这里——那是当前浏览模式的事实 */
    val transferPolicy: StateFlow<TransferPolicy> = transferPolicyStore.policy

    /** 传输记录（按结束时间倒序） */
    val history: StateFlow<List<DownloadHistoryEntity>> = historyDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 本批次摘要（设计 §4.5）。规则在 batchSummaryOf 里，是纯函数，有单测。
     *
     * 初值**不能是 null**：摘要卡片是列表的第一项。第一帧没有它、下一帧才插进来时，
     * LazyColumn 把视口锚在「当前第一项」（也就是传输策略）上，刚插进来的摘要就被顶到
     * 屏幕上方——冷启动进传输页看不到批次数字（实测如此）。
     */
    val batchSummary: StateFlow<TransferBatchSummary?> = downloadManager.tasks
        .map { batchSummaryOf(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            batchSummaryOf(downloadManager.tasks.value)
        )

    private val _notice = MutableStateFlow<TransferNotice?>(null)
    val notice: StateFlow<TransferNotice?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * 用户已经站到这些失败面前了 —— 全局任务条该消失。
     *
     * 由页面进入时调用（而不是 ViewModel 构造）：同一个返回栈条目的 VM 会活着跨过返回，
     * 从别的页再进来时 init 不会再跑一次，那样「未查看」就永远清不掉。
     */
    fun markBatchFailuresViewed() = downloadManager.markBatchFailuresViewed()

    /**
     * 重试本批全部未完成项。
     *
     * 用 [DownloadManager.enqueueAllAwait] 而不是 fire-and-forget：按了「重试」却没受理，
     * 界面上必须有一句话说清楚，否则用户只能反复点同一个按钮猜。
     */
    fun retryUnfinished() {
        val items = batchSummary.value?.retryable?.map { it.mediaItem }.orEmpty()
        if (items.isEmpty()) return
        viewModelScope.launch {
            if (!downloadManager.enqueueAllAwait(items)) {
                _notice.value = TransferNotice("重新加入队列失败（可能是存储不可用），任务仍列在下面，可以再试一次")
            } else {
                _notice.value = null
            }
        }
    }

    /** 重试单条失败任务 */
    fun retryTask(task: DownloadTask) {
        viewModelScope.launch {
            if (!downloadManager.enqueueAllAwait(listOf(task.mediaItem))) {
                _notice.value = TransferNotice("「${task.filename}」没能重新加入队列（可能是存储不可用）")
            } else {
                _notice.value = null
            }
        }
    }

    /**
     * 用记录行重建对象并重新下载（任务被清空后唯一的补救路径）。
     *
     * 记录行缺身份字段时这里根本不会被调用（界面不摆那个按钮），
     * 所以不写「拿 0 句柄碰碰运气」的兜底——那会把一条历史变成一次真实的 0x2009。
     */
    fun retryRecord(record: DownloadHistoryEntity) {
        val item = record.toMediaItemOrNull() ?: return
        viewModelScope.launch {
            if (!downloadManager.enqueueAllAwait(listOf(item))) {
                _notice.value = TransferNotice("「${record.filename}」没能重新加入队列（可能是存储不可用）")
            } else {
                _notice.value = null
            }
        }
    }

    /**
     * 打开一条已存到相册的记录 / 任务。
     *
     * 只有拿得到真 Uri 才谈得上打开：[DownloadHistoryEntity.savedPath] 是给人看的路径文字，
     * 反解成 Uri 去 startView 只会开出一个「文件不存在」的系统提示，那比不提供更糟。
     *
     * 三种结果要分开报：**「这台设备打不开这种格式」和「系统不让我读这个文件」是两件不同的事**，
     * 前者该分享给别的应用，后者分享也一样失败。混成一句道歉就是让用户自己去猜。
     */
    fun openSaved(savedUri: String?, filename: String): OpenOutcome {
        val uri = savedUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return OpenOutcome.NotReadable
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            OpenOutcome.Opened
        } catch (e: android.content.ActivityNotFoundException) {
            AppLog.w("transfer", "没有能打开 $filename 的应用：${e.message}")
            OpenOutcome.NoApp
        } catch (e: Exception) {
            AppLog.w("transfer", "打开 $filename 失败：${e.message}")
            OpenOutcome.NotReadable
        }
    }

    /** 打不开时要报给用户的「文件在哪」。查不到就退回 Uri 本身，不留空白 */
    fun readableLocation(savedUri: String?): String =
        savedUri?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.let { readablePathOf(context, it) }
            .orEmpty()

    fun clearFinished() {
        downloadManager.clearFinished()
    }

    /** 取消单个任务（排队中或下载中） */
    fun cancel(task: DownloadTask) {
        downloadManager.cancel(task.id)
    }

    /** 取消全部进行中的任务（排队中 + 下载中） */
    fun cancelAllActive() {
        downloadManager.cancelAllActive()
    }

    /**
     * 清空传输记录。
     *
     * **只删记录行，绝不删照片文件**：用户删的是这本账，不是相册里的原片。
     * 一旦在这里顺手 delete 相册文件，就是拿一个「清理列表」的点击去做不可逆的破坏性操作。
     */
    fun clearHistory() {
        viewModelScope.launch { historyDao.clearAll() }
    }
}
