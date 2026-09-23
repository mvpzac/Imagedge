package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 索尼相机设备属性码（DevicePropCode）
 *             参考 sony-alpha-python（protocol.py SONY_PROPERTIES）与
 *             Sony-ZV-E10-RX（DevicePropCode.kt）互相印证
 *     version: 1.0
 * </pre>
 */

/**
 * 索尼设备属性码。
 *
 * 分为两组：
 * - 标准 PTP 设备属性（0x50xx）：FNumber/白平衡/对焦模式等，消费级与电影机通用；
 * - 索尼私有属性（0xD2xx）：ISO、快门速度等，cinema 相机（FX30/FX3）实测，
 *   ZV-E10 的 ISO/快门可能走标准 0x500F/0x500D——真机日志确认后在此补充。
 */
object SonyDevicePropCode {
    // ── 标准 PTP 设备属性 ─────────────────────────────────────────
    const val WHITE_BALANCE = 0x5005            // UINT16，枚举
    const val F_NUMBER = 0x5007                 // UINT16，值 ×100（f/1.8 = 180）
    const val FOCUS_MODE = 0x500A               // UINT16，枚举
    const val EXPOSURE_METERING_MODE = 0x500B   // UINT16，枚举
    const val EXPOSURE_TIME = 0x500D            // UINT32 标准快门（ExposureTime，消费级候选）
    const val EXPOSURE_PROGRAM_MODE = 0x500E    // UINT16，枚举（P/A/S/M 等）
    const val EXPOSURE_INDEX = 0x500F           // UINT16 标准 ISO（ExposureIndex，消费级候选）
    const val EXPOSURE_BIAS = 0x5010            // INT16，×1000

    // ── 索尼私有属性 ─────────────────────────────────────────────
    const val ISO = 0xD21E                       // UINT32，低 24 位 = ISO 值，高 8 位 = 模式
    const val SHUTTER_SPEED = 0xD20D             // UINT32，高 16 分子 / 低 16 分母（FX30/FX3）
    const val SHUTTER_SPEED_HIGH = 0xD017        // UINT32，FX6/高端机（仅读）
}
