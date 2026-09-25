package com.imagedge.camera.feature.connection

import com.imagedge.camera.data.model.ConnectionPhase
import com.imagedge.camera.data.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 连接向导阶段机验收（批次 D）：步骤不撒谎、每种走不通都有能点的出口
 * </pre>
 */
class ConnectWizardTest {

    private fun attempt(
        stage: WizardStage = WizardStage.Connect,
        path: ConnectPath = ConnectPath.Qr,
        pending: Boolean = false,
        joined: Boolean = false,
        hotspotError: String? = null,
        phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
        sessionError: String? = null,
        descriptorRead: Boolean = false
    ) = ConnectAttempt(
        stage = stage,
        path = path,
        hotspotPending = pending,
        hotspotJoined = joined,
        hotspotError = hotspotError,
        sessionPhase = phase,
        sessionError = sessionError,
        descriptorRead = descriptorRead
    )

    // ── 步骤状态 ────────────────────────────────────────────────────────

    @Test
    fun `manual path never claims it joined the hotspot`() {
        // 「手机已在相机热点上」和手动 IP 都没有发起过配网请求。
        // 把这一步显示成已完成，是拿用户自己做的事来邀功
        val steps = stepsOf(attempt(path = ConnectPath.ManualIp, phase = ConnectionPhase.CONNECTING))

        assertEquals(StepState.Skipped, steps.wifi)
        assertEquals(StepState.Running, steps.session)
    }

    @Test
    fun `qr path reports the hotspot request as it moves`() {
        assertEquals(StepState.Pending, stepsOf(attempt()).wifi)
        assertEquals(StepState.Running, stepsOf(attempt(pending = true)).wifi)
        assertEquals(StepState.Done, stepsOf(attempt(joined = true)).wifi)

        val failed = stepsOf(attempt(hotspotError = "该二维码使用 WEP 加密"))
        assertEquals(StepState.Failed, failed.wifi)
        assertEquals("该二维码使用 WEP 加密", failed.wifiReason)
    }

    @Test
    fun `a joined hotspot stays joined after the scan step cleans itself up`() {
        // QrScanViewModel.release() 在成功态下保留配网请求并把 UI 清回 Idle
        // （释放即断开热点）。归约必须把「已连上」这条事实留住
        var observed = hotspotAfter(HotspotObservation(), QrScanUiState.Connecting("DIRECT-abc"))
        assertEquals(StepState.Running, stepsOf(attempt(pending = true)).wifi)

        observed = hotspotAfter(observed, QrScanUiState.Success("DIRECT-abc"))
        assertTrue(observed.joined)

        observed = hotspotAfter(observed, QrScanUiState.Idle)
        assertTrue("扫码步骤清理瞬时状态后，向导仍要知道热点已经连上", observed.joined)
        assertFalse(observed.pending)
        assertEquals(StepState.Done, stepsOf(attempt(joined = observed.joined)).wifi)
    }

    @Test
    fun `a capability read that never came back is unknown, not failed`() {
        // 已知坑 14：unknown ≠ unsupported。把一次超时报成红色失败，
        // 用户接下来做的就是「重连」，而重连正是会让相机端句柄全部失效的那个动作
        val steps = stepsOf(attempt(phase = ConnectionPhase.CONNECTED, descriptorRead = false))

        assertEquals(StepState.Unknown, steps.verify)
        assertEquals(StepState.Done, steps.session)
    }

    @Test
    fun `verify only becomes done when the descriptor actually came back`() {
        assertTrue(stepsOf(attempt(phase = ConnectionPhase.CONNECTED, descriptorRead = true))
            .verify == StepState.Done)
        // 还没连上时不预告验证步
        assertEquals(StepState.Pending, stepsOf(attempt()).verify)
    }

    @Test
    fun `session failure carries its reason onto the step`() {
        val steps = stepsOf(
            attempt(joined = true, phase = ConnectionPhase.ERROR, sessionError = "PTP 与 UPnP 都没连上")
        )

        assertEquals(StepState.Failed, steps.session)
        assertEquals("PTP 与 UPnP 都没连上", steps.sessionReason)
        assertEquals(StepState.Done, steps.wifi)
    }

    // ── 出口 ────────────────────────────────────────────────────────────

    @Test
    fun `prepare stage can always cancel and always offers the other path`() {
        val view = wizardViewOf(
            stage = WizardStage.PrepareCamera,
            path = ConnectPath.Qr,
            hotspot = HotspotObservation(),
            connection = ConnectionState(),
            descriptorRead = false
        )

        assertTrue(view.exits.canCancel)
        assertTrue("相机可能压根不显示二维码，这一步就得给别的路", view.exits.canUseOtherPath)
        assertFalse(view.exits.canGoBack)
    }

    @Test
    fun `scan stage gives back, cancel, other path and a wifi retry once the join failed`() {
        val view = wizardViewOf(
            stage = WizardStage.ScanQr,
            path = ConnectPath.Qr,
            hotspot = HotspotObservation(error = "连接相机热点超时"),
            connection = ConnectionState(),
            descriptorRead = false
        )

        assertTrue(view.exits.canGoBack)
        assertTrue(view.exits.canCancel)
        assertTrue(view.exits.canUseOtherPath)
        assertEquals(RetryTarget.Wifi, view.exits.retry)
    }

    @Test
    fun `connect stage offers exactly one retry target`() {
        val sessionDown = wizardViewOf(
            stage = WizardStage.Connect,
            path = ConnectPath.Qr,
            hotspot = HotspotObservation(joined = true),
            connection = ConnectionState(
                phase = ConnectionPhase.ERROR, errorMessage = "连不上"
            ),
            descriptorRead = false
        )
        assertEquals(RetryTarget.Session, sessionDown.exits.retry)

        val nothingToRetry = wizardViewOf(
            stage = WizardStage.Connect,
            path = ConnectPath.Qr,
            hotspot = HotspotObservation(joined = true),
            connection = ConnectionState(
                phase = ConnectionPhase.CONNECTING
            ),
            descriptorRead = false
        )
        // 正在连的时候不给重试按钮：再点一次就是并发发起两次连接（UI 规范 §7 防重）
        assertNull(nothingToRetry.exits.retry)
    }

    @Test
    fun `success stage stops offering connection plumbing`() {
        val view = wizardViewOf(
            stage = WizardStage.Success,
            path = ConnectPath.Qr,
            hotspot = HotspotObservation(joined = true),
            connection = ConnectionState(
                phase = ConnectionPhase.CONNECTED
            ),
            descriptorRead = true
        )

        assertFalse(view.exits.canCancel)
        assertFalse(view.exits.canGoBack)
        assertFalse(view.exits.canUseOtherPath)
    }

    @Test
    fun `the header lights verify only after the session is up`() {
        assertEquals(1, wizardPhaseIndex(attempt(stage = WizardStage.PrepareCamera)))
        assertEquals(2, wizardPhaseIndex(attempt(stage = WizardStage.ScanQr)))
        assertEquals(2, wizardPhaseIndex(attempt(stage = WizardStage.Connect)))
        assertEquals(
            "会话连上之后才算在确认这一段",
            3,
            wizardPhaseIndex(attempt(stage = WizardStage.Connect, phase = ConnectionPhase.CONNECTED))
        )
        assertEquals(3, wizardPhaseIndex(attempt(stage = WizardStage.Success)))
    }

    // ── 手动 IP ──────────────────────────────────────────────────────────

    @Test
    fun `a blank host means discover the gateway, not connect to nothing`() {
        assertEquals(ManualHost.AutoGateway, parseManualHost("   "))
    }

    @Test
    fun `a malformed host is caught before it reaches the socket`() {
        // 原先设置页那份没人调用的实现里就有这条校验；删掉它等于把
        // "192.168.abc" 丢下去等一个用户读不懂的超时
        assertTrue(parseManualHost("192.168.122.1") is ManualHost.Ip)
        assertEquals("192.168.122.1", (parseManualHost(" 192.168.122.1 ") as ManualHost.Ip).host)
        assertTrue(parseManualHost("192.168.1.999") is ManualHost.Invalid)
        assertTrue(parseManualHost("192.168.1") is ManualHost.Invalid)
        assertTrue(parseManualHost("localhost") is ManualHost.Invalid)
    }
}
