package com.imagedge.camera.data.transfer

import com.imagedge.camera.data.model.DownloadState
import com.imagedge.camera.data.model.DownloadTask
import com.imagedge.camera.data.model.isActive

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 批次口径（批次 C）：一次提交算一批，摘要与全局任务条都只说「本批次」
 *     version: 1.0
 * </pre>
 */

/**
 * 传输页顶部的批次摘要（设计 §4.5「已保存 N 项，M 项未完成」）。
 *
 * 刻意做成纯函数：这些数字是唯一一处「用户凭它决定要不要再按一次重试」的界面事实，
 * 它必须能脱离相机、脱离 Android 运行时被验证（仓库里没有 coroutines-test / robolectric，
 * 把规则从 DownloadManager 里摘出来是**唯一**能写单测的形状）。
 */
data class TransferBatchSummary(
    val batchId: Long,
    /** 已存进相册的项数 */
    val savedCount: Int,
    /** 排队中 + 下载中 + 失败的项数：这批还没有全部落地 */
    val unfinishedCount: Int,
    /** 本批中可以被再次入队的项（只有失败项；排队/下载中的不该被重复排队） */
    val retryable: List<DownloadTask>
)

/** 全局迷你任务条该说什么（设计 §4.5）：活动任务，或本批还没看过的失败 */
data class TransferBarContent(
    val batchId: Long,
    val activeCount: Int,
    val unviewedFailureCount: Int
)

/**
 * 本批次 = 编号最大的那批。
 *
 * 为什么取最大而不是「用户正在看的那批」：队列列表跨批显示（清空之前老批次的行还在），
 * 而摘要只有一个数字。让它跟着最新提交走，才不会在用户刚按完「保存」时报一个旧批次的数。
 */
fun latestBatchId(tasks: List<DownloadTask>): Long? =
    tasks.maxOfOrNull { it.batchId }

fun batchSummaryOf(tasks: List<DownloadTask>): TransferBatchSummary? {
    val batchId = latestBatchId(tasks) ?: return null
    val batch = tasks.filter { it.batchId == batchId }
    return TransferBatchSummary(
        batchId = batchId,
        savedCount = batch.count { it.state == DownloadState.DONE },
        unfinishedCount = batch.count { it.state != DownloadState.DONE },
        retryable = batch.filter { it.state == DownloadState.FAILED }
    )
}

/**
 * 该不该弹全局任务条。
 *
 * 两条并存的理由不同，所以分开算：活动任务 = 「还在传，随时可以去看进度」；
 * 未看过的失败 = 「传坏了，得让人知道」。除此之外它必须不存在——
 * 「无任务即消失」是设计写死的，常驻一条空状态栏就是又一个占底部的东西，
 * 而底部位只有这一个（见 [com.imagedge.camera.navigation.bottomBarOf]）。
 */
fun miniBarOf(tasks: List<DownloadTask>): TransferBarContent? {
    val activeCount = tasks.count { it.state.isActive }
    val batchId = latestBatchId(tasks)
    val unviewedFailures = if (batchId == null) 0 else tasks.count {
        it.batchId == batchId && it.state == DownloadState.FAILED && !it.failureViewed
    }
    if (activeCount == 0 && unviewedFailures == 0) return null
    return TransferBarContent(
        batchId = batchId ?: 0L,
        activeCount = activeCount,
        unviewedFailureCount = unviewedFailures
    )
}

/**
 * 重启后一条持久化任务该显示成什么状态。
 *
 * 排队中/下载中一律回落到排队中：PTP 的 GetObject 是整文件操作，没有断点续传
 * （分块在 ZV-E10 整卡模式下每块回 0x2009，见 docs/HANDOFF.md 已知坑 5 与 TransferPolicy），
 * 所以「下载中 45%」是一个重启后立刻就不成立的数字，留着它就是在撒谎。
 * 完成/失败是已经发生的事实，原样恢复。
 */
fun restoredStateOf(persisted: DownloadState): DownloadState =
    if (persisted.isActive) DownloadState.QUEUED else persisted

/** 恢复时哪些行需要重新交给消费循环（已完成/已失败的行只是给用户看的账目） */
fun resumesOnRestore(persisted: DownloadState): Boolean = persisted.isActive
