package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : PTP/IP 操作码（ISO 15740 + 索尼 SDIO 扩展，参考 Sony-ZV-E10-RX）
 *     version: 1.0
 * </pre>
 */

/**
 * PTP 标准操作码
 * M1 完善：会话管理 / 存储 / 对象浏览 / 传输
 */
object PtpOperationCode {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_NUM_OBJECTS = 0x1006
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val GET_PARTIAL_OBJECT = 0x101B
    const val INITIATE_CAPTURE = 0x100E
    const val INITIATE_OPEN_CAPTURE = 0x100F
}

/**
 * 索尼 PTP 扩展操作码（SDIO_*）
 * 初始化序列参考 alpha-fairy init_table（对 ZV-E10 验证有效）
 */
object SonySdioOperationCode {
    const val SDIO_CONNECT = 0x9201                    // SDIOConnect（初始化序列核心步骤）
    const val SDIO_GET_EXT_DEVICE_INFO = 0x9202        // SDIOGetExtDeviceInfo（扩展设备信息）
    const val SDIO_SET_EXT_DEVICE_PROP = 0x9205        // SDIOSetExtDevicePropValue（写设备属性）
    const val SDIO_CONTROL_DEVICE = 0x9207             // SDIOControlDevice（控制设备：设属性/录像/触控，带数据阶段）
    const val SDIO_GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209 // SDIOGetAllExtDevicePropInfo（一次读全部设备属性描述+当前值）
    const val SDIO_OPEN_SESSION = 0x9210
    const val SDIO_SET_CONTENTS_TRANSFER_MODE = 0x9212
    const val SDIO_GET_PARTIAL_LARGE_OBJECT = 0x9219
    const val SDIO_GET_EXT_DEVICE_PROP = 0x9251        // SDIOGetExtDeviceProp（读单个设备属性）
}
