package com.imagedge.camera.data.model

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 相机身份（能力快照的归档键）
 *     version: 1.0
 * </pre>
 */

/**
 * 相机传输方式。
 *
 * 能力与传输强相关：UPnP（「发送到智能手机」）通道只做目录浏览与 JPEG 下载，
 * 结构上不暴露 PTP DeviceProp，因此参数控制在这些通道上不是「未知」而是**确定不支持**。
 */
enum class CameraTransport { PTP_IP, UPNP }

/**
 * 相机身份。
 *
 * 能力快照必须按 (model, firmware, transport, mode) 归档：同一台机器换镜头、
 * 换拍摄模式、换连接方式后能力都会变，把某一次的经验泛化成「这个型号支持 X」
 * 正是 ZV-E10 套头上出现 f/1.8 档位那类 bug 的根源。
 *
 * @param model 相机型号（PTP GetDeviceInfo 的 Model 字段）
 * @param firmware 固件版本（GetDeviceInfo 的 DeviceVersion 字段）
 * @param transport 传输方式；null = 未连接
 * @param mode PTP 功能模式（[MODE_REMOTE_CONTROL] / [MODE_CONTENTS_TRANSFER]），
 *             非 PTP 通道或未知时为 [MODE_UNKNOWN]
 */
data class CameraIdentity(
    val model: String,
    val firmware: String,
    val transport: CameraTransport?,
    val mode: Int
) {

    /** 型号是否已知（未知时能力一律按未探测处理） */
    val isKnown: Boolean get() = model.isNotBlank()

    /**
     * 能力快照的归档键：同一键下的快照可互相比较。
     *
     * 含传输方式与功能模式——**同一台相机换个连接方式，档位就是不一样**
     * （UPnP 连 DeviceProp 都没有）。
     */
    val snapshotKey: String get() = "$model|$firmware|${transport?.name}|$mode"

    /**
     * 相机档案（T6）的归档键：只认物理设备，不认这次是怎么连上的。
     *
     * 与 [snapshotKey] 必须分开：档案属于「这台 ZV-E10」，用户换手机连接方式、
     * 切整卡模式都还是同一台机器，档案不该裂成两份；而能力快照确实随模式/通道变化，
     * 所以它保留在 [snapshotKey] 这一层。
     *
     * 固件版本参与键值：同型号刷过固件后菜单与属性表可能不同，混用会写出相机不认的档位。
     */
    val profileKey: String get() = "$model|$firmware"

    companion object {
        /** 未连接 / 身份未知 */
        val UNKNOWN = CameraIdentity(model = "", firmware = "", transport = null, mode = MODE_UNKNOWN)

        const val MODE_UNKNOWN = -1
        /** 选片集（RemoteControl，标准 OpenSession 0x1002） */
        const val MODE_REMOTE_CONTROL = 0
        /** 整卡（ContentsTransfer，Sony 0x9210） */
        const val MODE_CONTENTS_TRANSFER = 1
    }
}
