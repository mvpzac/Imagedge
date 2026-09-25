package com.imagedge.camera.feature.photos

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 浏览范围切换的判定（设计 §8.2）：会话、传输、目标模式三件事分开看
 *     version: 1.0
 * </pre>
 */

/**
 * 现在能不能为了切范围去改相机的功能模式。
 *
 * 三条原因是**不同的修复路径**，合成一个 `canSwitch: Boolean` 就会只剩一句
 * 「切换失败」：没连接要去连，传输中要等，模式本来就一致根本不用动通道。
 */
enum class ChannelGate {
    /** 可以改功能模式 */
    Free,

    /** 相机没连上：此时切范围没有对象可切，界面该给的是「去连接」而不是「切换失败」 */
    NoSession,

    /** 传输正占着通道：这时改功能模式会让正在写的那个文件半途而废 */
    BlockedByTransfer
}

/** 浏览范围 → PTP 功能模式（0=选片集/遥控，1=整卡传输） */
fun functionModeOf(mode: BrowseMode): Int = if (mode == BrowseMode.FULL_CARD) 1 else 0

/**
 * @param needsModeSwitch 相机**当前**功能模式是否真的不等于目标模式。
 *   相等时整条判断放行——传输中可以照切范围标签，因为根本不碰通道。
 *   这一条不是偷懒：它正是「切范围」和「打断传输」之间唯一的实际边界。
 */
fun channelGateOf(
    sessionReady: Boolean,
    needsModeSwitch: Boolean,
    transferActive: Boolean
): ChannelGate = when {
    !needsModeSwitch -> ChannelGate.Free
    !sessionReady -> ChannelGate.NoSession
    transferActive -> ChannelGate.BlockedByTransfer
    else -> ChannelGate.Free
}
