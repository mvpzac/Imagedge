package com.imagedge.camera.feature.capture

import androidx.annotation.StringRes
import com.imagedge.camera.R
import com.imagedge.camera.data.ble.BleCameraStatus
import com.imagedge.camera.data.model.CapabilityState

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 遥控页的三件可用性（批次 E/O）：画面、快门、拍后保存分别判定，措辞在 strings.xml
 *     version: 1.1
 * </pre>
 */

/**
 * 一件能力现在能不能用。
 *
 * [Unknown] 必须独立存在：相机没上报过能力（未探测、0x9209 读取超时、BLE 还没回通知）
 * 和相机明确说不支持，是两件事。把它们合并成「不可用」，用户看到的下一步就是重连，
 * 而重连会让相机端句柄全部失效（docs/HANDOFF.md 已知坑 4、14）。
 */
enum class Availability { Ready, NotNow, Unknown }

/**
 * 一行可用性。[noteRes] 说「为什么」和「怎么办」，不是重复状态本身。
 *
 * 存字符串资源 id 而不是成品句子（新手手册 §6：措辞归 `strings.xml`）：
 * 判定是纯函数、跑在 JVM 单测里，拿不到 `Context`。上一版把 13 句中文直接写在这里，
 * 等于让判定逻辑顺手 owns 了文案——改一句话要动业务代码，改版与翻译也没有落点。
 */
data class AvailabilityLine(
    val state: Availability,
    @param:StringRes val noteRes: Int?
)

/** 遥控页的三件能力。设计 §4.6：分别显示，不能只摆 BLE/Wi-Fi 两个技术词 */
data class CaptureAvailability(
    val viewfinder: AvailabilityLine,
    val shutter: AvailabilityLine,
    val autoSave: AvailabilityLine
)

/**
 * 算出三件能力。
 *
 * 为什么分开算：这三件事各有各的来路——画面来自 60152 推流、快门来自 BLE 通路或
 * PTP 的 CAPTURE 能力、拍后保存是用户偏好加会话状态。原来页面上只有
 * 「蓝牙已连接 / 未连接」两行技术状态，用户读完仍然不知道**能不能拍**：
 * BLE 没连但 PTP 支持遥控时快门其实能用，BLE 连上了但相机没回能力时快门其实未知。
 *
 * 快门那一行还要看画面的脸色（新手手册 §4）：取景停了而快门照样能用时，明说
 * 「看不到实时画面也能拍」。不说，用户读到「取景已暂停」就以为整屏都停了，
 * 于是去重连——而那正是最不该发生的动作。
 */
fun captureAvailabilityOf(
    connected: Boolean,
    viewfinderPaused: Boolean,
    hasFrame: Boolean,
    bleConnected: Boolean,
    ptpCapture: CapabilityState,
    capabilitiesStale: Boolean,
    busy: Boolean,
    autoSaveEnabled: Boolean
): CaptureAvailability {
    val viewfinder = when {
        !connected -> AvailabilityLine(Availability.NotNow, R.string.capture_note_view_off)
        viewfinderPaused -> AvailabilityLine(Availability.NotNow, R.string.capture_note_view_paused)
        hasFrame -> AvailabilityLine(Availability.Ready, null)
        else -> AvailabilityLine(Availability.Unknown, R.string.capture_note_view_no_frame)
    }
    val shutter = when {
        !connected -> AvailabilityLine(Availability.NotNow, R.string.capture_note_shutter_off)
        busy -> AvailabilityLine(Availability.NotNow, R.string.capture_note_shutter_busy)
        bleConnected -> AvailabilityLine(Availability.Ready, R.string.capture_note_shutter_ble)
        ptpCapture == CapabilityState.WRITABLE ->
            AvailabilityLine(Availability.Ready, R.string.capture_note_shutter_ptp)
        // 这个模式没实测过 ≠ 这台相机不支持：说未知，别把用户推去重连（已知坑 14）
        ptpCapture == CapabilityState.UNKNOWN ->
            AvailabilityLine(Availability.Unknown, R.string.capture_note_shutter_unverified)
        // 能力还没读回来：同样说未知，不能说这台相机不支持
        capabilitiesStale ->
            AvailabilityLine(Availability.Unknown, R.string.capture_note_shutter_stale)
        else -> AvailabilityLine(Availability.NotNow, R.string.capture_note_shutter_unsupported)
    }
    return CaptureAvailability(
        viewfinder = viewfinder,
        shutter = if (shutter.state == Availability.Ready && viewfinder.state != Availability.Ready) {
            shutter.copy(noteRes = R.string.capture_note_shutter_blind)
        } else {
            shutter
        },
        autoSave = when {
            !autoSaveEnabled -> AvailabilityLine(Availability.NotNow, R.string.capture_note_autosave_off)
            !connected -> AvailabilityLine(Availability.NotNow, R.string.capture_note_autosave_offline)
            else -> AvailabilityLine(Availability.Ready, R.string.capture_note_autosave_on)
        }
    )
}

/**
 * 录像按钮该显示什么。
 *
 * 状态**只认相机回报**（ff02 通知）：[BleCameraStatus.recording] 为 null 表示连上了
 * 但相机还没说过它在不在录。这时界面不许拿本地布尔值猜「没在录」，
 * 也不给一个「停止/开始」的切换（那等于替用户决定当前是哪一态）——
 * 给一个明确的「开始录像」并把未知写在旁边，按下以后仍以相机回报为准。
 */
enum class RecordAction { Unavailable, Unknown, Start, Stop }

fun recordActionOf(bleConnected: Boolean, status: BleCameraStatus): RecordAction = when {
    !bleConnected -> RecordAction.Unavailable
    status.recording == true -> RecordAction.Stop
    status.recording == false -> RecordAction.Start
    else -> RecordAction.Unknown
}
