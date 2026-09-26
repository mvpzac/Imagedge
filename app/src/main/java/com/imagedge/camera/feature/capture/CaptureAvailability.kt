package com.imagedge.camera.feature.capture

import com.imagedge.camera.data.ble.BleCameraStatus
import com.imagedge.camera.data.model.CapabilityState

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 遥控页的三件可用性（批次 E）：画面、快门、拍后保存分别判定
 *     version: 1.0
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

/** 一行可用性。[note] 是「为什么」和「怎么办」，不是重复 [label] */
data class AvailabilityLine(
    val state: Availability,
    val note: String?
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
): CaptureAvailability = CaptureAvailability(
    viewfinder = when {
        !connected -> AvailabilityLine(Availability.NotNow, "相机没连上，没有画面可取")
        viewfinderPaused -> AvailabilityLine(Availability.NotNow, "取景已暂停（回前台或继续取景即恢复）")
        hasFrame -> AvailabilityLine(Availability.Ready, null)
        else -> AvailabilityLine(Availability.Unknown, "已连接，还没收到这一轮的第一帧")
    },
    shutter = when {
        !connected -> AvailabilityLine(Availability.NotNow, "连接相机后才能遥控拍摄")
        busy -> AvailabilityLine(Availability.NotNow, "上一次拍摄还没结束，忙时不接新快门")
        bleConnected -> AvailabilityLine(Availability.Ready, "走蓝牙快门")
        ptpCapture == CapabilityState.WRITABLE ->
            AvailabilityLine(Availability.Ready, "走相机传输通道的遥控拍摄")
        // 这个模式没实测过 ≠ 这台相机不支持：说未知，别把用户推去重连（已知坑 14）
        ptpCapture == CapabilityState.UNKNOWN ->
            AvailabilityLine(Availability.Unknown, "当前模式下遥控拍摄没有实测记录，先别当成不支持")
        // 能力还没读回来：同样说未知，不能说这台相机不支持
        capabilitiesStale -> AvailabilityLine(Availability.Unknown, "还没读到这轮相机的能力，先别当成不支持")
        else -> AvailabilityLine(Availability.NotNow, "这台相机的传输通道不具备遥控拍摄")
    },
    autoSave = when {
        !autoSaveEnabled -> AvailabilityLine(Availability.NotNow, "未开启：拍摄后不会自动存入相册")
        !connected -> AvailabilityLine(Availability.NotNow, "没连上相机，拍后的文件取不回来")
        else -> AvailabilityLine(Availability.Ready, "拍完自动存入相册（重复的只存一次）")
    }
)

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
