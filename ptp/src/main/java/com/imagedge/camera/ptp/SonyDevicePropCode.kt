package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 索尼相机设备属性码（DevicePropCode）
 *     version: 1.1
 * </pre>
 */

/**
 * 索尼设备属性码。
 *
 * 分为两组：
 * - 标准 PTP 设备属性（0x50xx）：FNumber/白平衡/对焦模式等，消费级与电影机通用；
 * - 索尼私有属性（0xD2xx）：ISO、快门速度等，cinema 相机（FX30/FX3）实测。
 *
 * 读写走 [SonySdioOperationCode.SDIO_SET_EXT_DEVICE_PROP]；触发按键类动作
 * 不走这里，见 [SonyControlCode]。
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

    /**
     * 快门速度当前值。
     *
     * 与 [SHUTTER_SPEED] 是两个不同属性：后者是设定值，这个是相机回报的实测当前值。
     * 命名早于本文件其余部分，早年被当作「高端机专用的高快门速度属性」——
     * 那是按名字推断的，码本身指向的是当前值，两者不能互相替代。
     */
    const val SHUTTER_SPEED_CURRENT = 0xD017     // UINT32

    // ── 对焦 ────────────────────────────────────────────────────
    /** 对焦区域（读取用；设置走 [SonyControlCode.AF_AREA_POSITION]） */
    const val FOCUS_AREA = 0xD22C

    /** AF 区域坐标 (x,y) */
    const val AF_AREA_POSITION = 0xD232

    /** 近/远对焦驱动是否可用 */
    const val NEAR_FAR_ENABLED = 0xD235

    // ── 包围拍摄 ─────────────────────────────────────────────────
    /** 焦点包围：张数 */
    const val FOCUS_BRACKET_SHOT_NUM = 0xD2A1

    /** 焦点包围：对焦范围 */
    const val FOCUS_BRACKET_FOCUS_RANGE = 0xD2A2

    // ── 像素位移多帧拍摄 ─────────────────────────────────────────
    /** 拍摄模式 */
    const val PIXEL_SHIFT_SHOOTING_MODE = 0xD239

    /** 拍摄张数（帧数） */
    const val PIXEL_SHIFT_SHOOTING_NUMBER = 0xD23A

    /** 帧间隔 */
    const val PIXEL_SHIFT_SHOOTING_INTERVAL = 0xD23B

    /** 进行中状态 */
    const val PIXEL_SHIFT_SHOOTING_STATUS = 0xD23C

    /** 已完成帧数进度 */
    const val PIXEL_SHIFT_SHOOTING_PROGRESS = 0xD23D

    // ── 色彩 ────────────────────────────────────────────────────
    /** -picture profile（S-Log 拍摄依据，编辑器据此选 LUT） */
    const val PICTURE_PROFILE = 0xD23F

    /** 创意风格 */
    const val CREATIVE_STYLE = 0xD240

    // ── 创意风格参数 ────────────────────────────────────────────────
    // 与 [CREATIVE_STYLE] 分开上报：前者是选中的风格编号，后者是该风格的调节量。
    // 下列属性并非所有机型都提供，读不到即为 null，不按 0 处理。

    /** 创意风格 · 对比度 */
    const val CREATIVE_LOOK_CONTRAST = 0xD0FB

    /** 创意风格 · 高光 */
    const val CREATIVE_LOOK_HIGHLIGHTS = 0xD0FC

    /** 创意风格 · 阴影 */
    const val CREATIVE_LOOK_SHADOWS = 0xD0FD

    /** 创意风格 · 褪色（暗部提亮） */
    const val CREATIVE_LOOK_FADE = 0xD0FE

    /** 创意风格 · 饱和度 */
    const val CREATIVE_LOOK_SATURATION = 0xD0FF

    /** 创意风格 · 锐度 */
    const val CREATIVE_LOOK_SHARPNESS = 0xD100

    /** 创意风格 · 锐度范围 */
    const val CREATIVE_LOOK_SHARPNESS_RANGE = 0xD101

    /** 创意风格 · 清晰度 */
    const val CREATIVE_LOOK_CLARITY = 0xD102

    /** 监视器 LUT 设置 */
    const val MONITOR_LUT_SETTING = 0xD04D

    /** 色温（白平衡微调） */
    const val COLOR_TEMPERATURE = 0xD20F

    // ── 实时取景 ────────────────────────────────────────────────
    /** 实时取景流的 URL（相机下发，取代写死端口） */
    const val LIVE_VIEW_URL = 0xD278

    /** 取景画面显示效果（相机侧的显示 LUT） */
    const val LIVE_VIEW_DISPLAY_EFFECT = 0xD231

    /** 取景图像质量 */
    const val LIVE_VIEW_IMAGE_QUALITY = 0xD26A

    // ── 拍摄与存储 ──────────────────────────────────────────────
    /** 静态照片格式 */
    const val FILE_FORMAT_STILL = 0xD253

    /** JPEG 画质 */
    const val JPEG_QUALITY = 0xD252

    /** RAW 文件类型 */
    const val RAW_FILE_TYPE = 0xD288

    /** 静态照片保存目标设备 */
    const val STILL_IMAGE_SAVE_DESTINATION = 0xD222

    /** 存储卡 1 状态 */
    const val MEDIA_SLOT1_STATUS = 0xD248

    /** 存储卡 1 剩余可拍张数 */
    const val MEDIA_SLOT1_REMAINING_SHOTS = 0xD249

    /** 存储卡 2 状态 */
    const val MEDIA_SLOT2_STATUS = 0xD256

    /** 存储卡 2 剩余可拍张数 */
    const val MEDIA_SLOT2_REMAINING_SHOTS = 0xD257

    /** 电池剩余电量 */
    const val BATTERY_LEVEL = 0xD20E

    /** 遥控受限状态：相机端禁用了遥控时为非 0 */
    const val REMOTE_CONTROL_RESTRICTION = 0xD264
}