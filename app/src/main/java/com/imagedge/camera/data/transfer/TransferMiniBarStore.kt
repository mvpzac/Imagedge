package com.imagedge.camera.data.transfer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 全局传输小条的数据源（批次 C）：把队列状态翻译成「底部该不该有条、写什么」
 *     version: 1.0
 * </pre>
 */

/**
 * 传输小条的只读视图。
 *
 * 为什么单独一个类而不是让 `AppRoot` 直接注入 [DownloadManager]：根 composable 不该知道
 * 队列里有几个任务、批次怎么算，它只需要知道「现在该画哪一条」。这条翻译规则
 * （[miniBarOf]）是纯函数，放在数据侧才能被单测覆盖。
 *
 * [bar] 是 cold flow，直接由 `collectAsStateWithLifecycle` 消费：队列本身是 StateFlow，
 * 再套一层 stateIn 只会多一个常驻协程，换不来任何东西。
 */
@Singleton
class TransferMiniBarStore @Inject constructor(
    downloadManager: DownloadManager
) {
    val bar: Flow<TransferBarContent?> = downloadManager.tasks.map { miniBarOf(it) }
}
