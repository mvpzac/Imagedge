package com.imagedge.camera.feature.capture

import com.imagedge.camera.data.ble.BleCameraStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 遥控可用性判定验收（批次 E）：三件能力分开算，未知不染成不可用
 * </pre>
 */
class CaptureAvailabilityTest {

    private fun availability(
        connected: Boolean = true,
        paused: Boolean = false,
        hasFrame: Boolean = true,
        ble: Boolean = false,
        ptpCapture: Boolean = false,
        stale: Boolean = true,
        busy: Boolean = false,
        autoSave: Boolean = false
    ) = captureAvailabilityOf(
        connected = connected,
        viewfinderPaused = paused,
        hasFrame = hasFrame,
        bleConnected = ble,
        ptpCaptureAvailable = ptpCapture,
        capabilitiesStale = stale,
        busy = busy,
        autoSaveEnabled = autoSave
    )

    // ── 快门 ────────────────────────────────────────────────────────────

    @Test
    fun `shutter is ready over either path, not only over bluetooth`() {
        // 只显示「BLE 已连接/未连接」时，用户看不出 PTP 遥控其实可用
        assertEquals(Availability.Ready, availability(ble = true, stale = true).shutter.state)
        assertEquals(Availability.Ready, availability(ptpCapture = true, stale = false).shutter.state)
    }

    @Test
    fun `an unread capability report is unknown, never unsupported`() {
        // 已知坑 14：0x9209 读取超时/未探测必须落在 Unknown 且允许重试。
        // 把它显示成「这台相机不支持」，用户唯一能想到的修复就是重连——
        // 而重连会让相机端句柄全部失效（已知坑 4）
        val line = availability(stale = true).shutter

        assertEquals(Availability.Unknown, line.state)
        assertNotNull("未知还要说明为什么未知", line.note)
    }

    @Test
    fun `a probed capability that excludes remote shooting says so plainly`() {
        val line = availability(stale = false).shutter

        assertEquals(Availability.NotNow, line.state)
        assertTrue(
            "读了且不支持，就该说是相机在当前模式下没上报这项",
            line.note!!.contains("没有上报")
        )
    }

    @Test
    fun `busy refuses a new shutter instead of stacking one`() {
        // 与 CameraControlViewModel 的「忙时不叠加快门」同一口径：
        // 界面禁用与不发命令必须是同一件事
        assertEquals(Availability.NotNow, availability(ble = true, busy = true).shutter.state)
    }

    // ── 画面 ────────────────────────────────────────────────────────────

    @Test
    fun `a paused viewfinder says paused, not broken`() {
        val line = availability(paused = true).viewfinder

        assertEquals(Availability.NotNow, line.state)
        assertTrue(line.note!!.contains("暂停"))
    }

    @Test
    fun `connected but no frame yet is unknown`() {
        assertEquals(Availability.Unknown, availability(hasFrame = false).viewfinder.state)
        assertEquals(Availability.Ready, availability(hasFrame = true).viewfinder.state)
    }

    // ── 拍后保存 ─────────────────────────────────────────────────────────

    @Test
    fun `auto-save off is a choice, not a fault`() {
        val line = availability(autoSave = false).autoSave

        assertEquals(Availability.NotNow, line.state)
        assertTrue("没开启要说成用户的选择", line.note!!.startsWith("未开启"))
    }

    @Test
    fun `auto-save on while connected promises the album`() {
        assertEquals(Availability.Ready, availability(autoSave = true).autoSave.state)
    }

    @Test
    fun `the three lines never collapse into one verdict`() {
        // 三条各有各的来路：画面已停、快门未知、拍后保存未开启——
        // 合成一个「不可用」就把三件事都说成了同一件
        val a = availability(paused = true, hasFrame = false, stale = true, autoSave = false)

        assertEquals(Availability.NotNow, a.viewfinder.state)
        assertEquals(Availability.Unknown, a.shutter.state)
        assertEquals(Availability.NotNow, a.autoSave.state)
    }

    // ── 录像：只认相机回报 ────────────────────────────────────────────────

    @Test
    fun `record button hides itself when there is no bluetooth path`() {
        assertEquals(
            RecordAction.Unavailable,
            recordActionOf(bleConnected = false, status = BleCameraStatus(recording = true))
        )
    }

    @Test
    fun `record label follows the camera notification`() {
        assertEquals(RecordAction.Start, recordActionOf(true, BleCameraStatus(recording = false)))
        assertEquals(RecordAction.Stop, recordActionOf(true, BleCameraStatus(recording = true)))
    }

    @Test
    fun `no notification yet is unknown, not idle`() {
        // 断线或刚连上时 recording 是 null。显示「开始录像」并默认相机在空闲，
        // 就是拿本地布尔值替相机说话（已知坑 30：状态标签必须在动作确认之后才改）
        val action = recordActionOf(true, BleCameraStatus())

        assertEquals(RecordAction.Unknown, action)
        assertNull(BleCameraStatus().recording)
    }
}
