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

/**
 * 触发快门。
     *
     * 标准 PTP 操作码，保留供非索尼的 PTP 相机使用。**索尼相机不靠它出图**——
     * 触发快门走 [SonySdioOperationCode.SDIO_CONTROL_DEVICE] 配
     * [SonyControlCode.S1_BUTTON] 的两段式，本工程已不再下发 0x100E。
     */
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
    /**
     * SDIOGetPartialLargeObject —— 索尼分块读取扩展，MaxBytes 扩为 UINT64。
     *
     * 取值以 libgphoto2 的 `camlibs/ptp2/ptp.h` 为准
     * （`PTP_OC_SONY_SDIO_GetPartialLargeObject 0x9211`）：该文件的 Sony SDIO 块里
     * **没有 0x9219**。此前这里写的是 0x9219，于是「ZV-E10 不支持分块」这个结论
     * 是在一个从未被正确试过的分支上得出的。
     *
     * 仍未验证：0x9211 在 ZV-E10 上是否真的可用——需要真机。
     * 另注意 libgphoto2 逐机型调分块大小（`library.c` 的 1 MiB，"the EOS R does not
     * like 5MB, but likes 1MB"），照搬单一分块大小同样可能失败。
     */
    const val SDIO_GET_PARTIAL_LARGE_OBJECT = 0x9211

    /**
     * 短视频专用的分块读取。
     *
     * 此前这段注释把它当作「libgphoto2 里没有、所以不可信」而与 0x9211 混为一谈。
     * 它其实是独立且存在的操作码，只是语义比 0x9211 窄：**仅用于短视频**，
     * 因此不能拿来当 0x9211 的通用替代——两者不是同一个能力的两个版本。
     */
    const val SDIO_GET_PARTIAL_LARGE_OBJECT_FOR_SHORT_VIDEOS = 0x9219

    /**
     * 读单个扩展设备属性。
     *
     * 状态存疑：官方 App 的操作码表里没有这一条，它们读属性一律走
     * 0x9209（一次读全部）或 0x9205 的对称读路径。本工程此前把它当成
     * 既成事实在用，实际未在任何机型上确认过——所以调用方需自行处理失败。
     */
    const val SDIO_GET_EXT_DEVICE_PROP = 0x9251
}

/**
 * 索尼对象属性操作码（0x98xx）。
 *
 * 与设备属性（0xDxxx）的分工：设备属性描述相机，对象属性描述单个文件。
 * 这是传输列表在下载前标出 RAW / 代理 / 多帧合成片的唯一途径。
 */
object SonyObjectPropOperationCode {
    /** 相机支持哪些对象属性 */
    const val GET_OBJECT_PROPS_SUPPORTED = 0x9801

    /** 对象属性的类型、取值范围与当前值 */
    const val GET_OBJECT_PROP_DESC = 0x9802

    /** 读单个对象的单个属性 */
    const val GET_OBJECT_PROP_VALUE = 0x9803

    /**
     * 一次读多个对象属性。
     *
     * 参数为 `(句柄1, 属性码1, 句柄2, 属性码2, …)` 的交替序列，
     * 相机按序逐项回报，不支持的项直接略过而不报错。
     */
    const val GET_OBJECT_PROP_LIST = 0x9805
}
