package com.imagedge.camera.feature.photos

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 相册页的一次说明：带结构化原因，文案由界面按类型取字符串资源
 *     version: 1.0
 * </pre>
 */

/**
 * 相册页要说的一句话（设计 §8.2「不可用要带结构化原因」）。
 *
 * 原来这里是一条 `String?`：同一个「传输中不能切」在两处各写了一遍字面量，
 * 措辞还不一样（一处「浏览范围」一处「浏览通道」），且 ViewModel 里硬写中文
 * 绕开了 strings.xml。类型化之后，界面负责文案、这里负责**是什么事**，
 * 「未连接」和「切换失败」也不会再被合并成同一句红字。
 */
sealed interface PhotosNotice {

    /** 传输占着通道，切范围会打断正在写的那个文件 */
    data object TransferBusy : PhotosNotice

    /** 相机没连上就要求切范围：该去连接，不是重试切换 */
    data object NotConnected : PhotosNotice

    /** 入队没被受理（落库失败）：选择保持原样，用户可以直接再试一次 */
    data object QueueRejected : PhotosNotice

    /** 后台轮询失败但列表已有内容：说一次，不清空可见数据 */
    data object RefreshFailed : PhotosNotice

    /**
     * 用户主动切范围，但相机没能切到目标功能模式：
     * 范围标签、列表、选择集合一律保持原样，界面说的仍是**没变之前**那个范围。
     */
    data class ModeSwitchFailed(val target: BrowseMode) : PhotosNotice

    /** 加载列表前校正功能模式失败：标签已经是当前范围，只是进不去 */
    data object ModeSyncFailed : PhotosNotice

    /** 列表加载失败，detail 是通道给出的原文（可能为 null） */
    data class LoadFailed(val detail: String?) : PhotosNotice

    /** 用户点「重新连接」失败 */
    data class ReconnectFailed(val detail: String?) : PhotosNotice
}
