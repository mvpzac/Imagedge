package com.imagedge.camera.feature.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.transfer.DownloadHistoryDao
import com.imagedge.camera.data.transfer.DownloadHistoryEntity
import com.imagedge.camera.data.transfer.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 下载队列 + 传输记录 ViewModel（转发 DownloadManager 状态与历史 DAO）
 *     version: 2.0
 * </pre>
 */

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    private val historyDao: DownloadHistoryDao
) : ViewModel() {

    val tasks: StateFlow<List<DownloadTask>> = downloadManager.tasks

    /** 传输记录（按结束时间倒序） */
    val history: StateFlow<List<DownloadHistoryEntity>> = historyDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun clearHistory() {
        viewModelScope.launch { historyDao.clearAll() }
    }
}
