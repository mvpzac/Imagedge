package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : 索尼私有扩展的「设备控制码」，经 SDIO_ControlDevice（0x9207）下发
 *     version: 1.0
 * </pre>
 */

/**
 * 设备控制码。
 *
 * 与 [SonyDevicePropCode] 的分工：设备属性是「设成某个值」（走 0x9205），
 * 控制码是「按一下某个键」（走 0x9207，带数据阶段）。相机不接受用属性事务触发快门。
 *
 * 每个控制码的数据阶段负载宽度不同，见 [payloadBytes]。
 */
object SonyControlCode {
    /** 快门键。两段式：按下=对焦，松开=出图 */
    const val S1_BUTTON = 0xD2C1

    /** 录像键 */
    const val MOVIE_REC_BUTTON = 0xD2C8

    /** 手动对焦驱动（近/远） */
    const val NEAR_FAR = 0xD2D1

    /** 触摸对焦区域坐标 */
    const val AF_AREA_POSITION = 0xD2DC

    /** 变焦操作 */
    const val ZOOM_OPERATION = 0xD2DD

    /**
     * 各控制码数据阶段的负载字节数。
     *
     * 宽度不统一是索尼扩展的既有约定：绝大多数键位是 2 字节小端，
     * 坐标类 4 字节，方向键 1 字节。发错宽度相机会静默丢弃整条控制指令，
     * 所以这里按控制码查表，不给调用方自选宽度的余地。
     */
    fun payloadBytes(controlCode: Int): Int = when (controlCode) {
        S1_BUTTON, MOVIE_REC_BUTTON, NEAR_FAR -> 2
        AF_AREA_POSITION -> 4
        ZOOM_OPERATION -> 1
        else -> throw IllegalArgumentException(
            "未知控制码 0x${controlCode.toString(16)}：其负载宽度无从判定，不猜"
        )
    }
}

/**
 * 控制指令的取值。
 *
 * [S1_BUTTON] 的按下/松开不是布尔：`[DOWN]` 与 `[RELEASE]` 是两个不同的整数，
 * 相机据此区分半按对焦与松开出图，发同一个值会只对焦不出图。
 */
object SonyControlValue {
    /** 键按下 / 手动对焦向近端 */
    const val DOWN = 2

    /** 键松开 / 停止驱动 */
    const val RELEASE = 1

    /** 手动对焦向远端 */
    const val FAR = 2

    /** 手动对焦向近端（对焦驱动用，与按键方向相反约定） */
    const val NEAR = -2

    /** 停止（不按键时发出，用于结束持续驱动） */
    const val STOP = 0
}