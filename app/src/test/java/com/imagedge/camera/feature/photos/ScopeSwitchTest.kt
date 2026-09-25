package com.imagedge.camera.feature.photos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 浏览范围切换判定验收（设计 §8.2）：会话、传输、目标模式三件事各管各的
 * </pre>
 */
class ScopeSwitchTest {

    @Test
    fun `switching to the other scope while a transfer runs is blocked`() {
        val gate = channelGateOf(
            sessionReady = true,
            needsModeSwitch = true,
            transferActive = true
        )

        assertEquals(
            "改功能模式会让正在写的那个文件半途而废，这时必须挡住",
            ChannelGate.BlockedByTransfer,
            gate
        )
    }

    @Test
    fun `a transfer does not block a switch that never touches the channel`() {
        // 相机已经在目标模式（例如上一次离开的延迟退出还没跑，标签却是新的）：
        // 这时切范围只是把界面说对，不碰通道，也就打断不了传输
        val gate = channelGateOf(
            sessionReady = true,
            needsModeSwitch = false,
            transferActive = true
        )

        assertEquals(ChannelGate.Free, gate)
    }

    @Test
    fun `no session is reported before a transfer is blamed`() {
        // 没连上时先去连接。若把这条判成「传输中」，用户会去等一个永远不会结束的下载
        val gate = channelGateOf(
            sessionReady = false,
            needsModeSwitch = true,
            transferActive = true
        )

        assertEquals(ChannelGate.NoSession, gate)
    }

    @Test
    fun `connected, idle and a real mode change is the one allowed path`() {
        val gate = channelGateOf(
            sessionReady = true,
            needsModeSwitch = true,
            transferActive = false
        )

        assertEquals(ChannelGate.Free, gate)
    }

    @Test
    fun `scopes map to the function modes the camera actually answers`() {
        // 0=选片集/遥控，1=整卡传输。写反了界面会「切换成功」却列出另一批东西
        assertEquals(0, functionModeOf(BrowseMode.SELECTION))
        assertEquals(1, functionModeOf(BrowseMode.FULL_CARD))
    }
}
