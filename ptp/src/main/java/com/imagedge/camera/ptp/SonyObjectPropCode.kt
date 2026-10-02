package com.imagedge.camera.ptp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/10/02
 *     desc   : PTP 对象属性码（0x9801/9802/9803/9805 读取的对象级元数据）
 *     version: 1.0
 * </pre>
 */

/**
 * 对象属性码。
 *
 * 与 [SonyDevicePropCode] 的分工：设备属性描述**相机**，对象属性描述**单个文件**。
 * 因此这套码是「不下载文件就读出它是什么」的唯一途径——传输列表要标 RAW /
 * 合成片 / 代理文件，只能从这里拿。
 */
object SonyObjectPropCode {
    // ── 标准对象属性 ───────────────────────────────────────────────
    const val OBJECT_FORMAT = 0xDC02
    const val OBJECT_SIZE = 0xDC04
    const val OBJECT_FILE_NAME = 0xDC07
    const val DATE_CREATED = 0xDC08
    const val IMAGE_BIT_DEPTH = 0xDCD3
    const val PERSISTENT_UNIQUE_ID = 0xDC41

    /** 宽度（像素） */
    const val WIDTH = 0xDC87

    /** 高度（像素） */
    const val HEIGHT = 0xDC88

    /** 时长（视频，单位随对象格式而定） */
    const val DURATION = 0xDC89

    /** 视频编码 FourCC */
    const val VIDEO_FOURCC_CODEC = 0xDE9B

    // ── 索尼私有对象属性 ───────────────────────────────────────────
    /** 连拍中的代表帧标记：非 0 表示这张是连拍里的代表图 */
    const val IS_BURSTSHOT_REPRESENTATIVE = 0xD8A1

    /** 合成片数量：一张图由多帧相机端合成时大于 1（像素位移、多帧降噪等） */
    const val PRIMARY_IMAGE_COUNT = 0xD8A5

    /** 多图类型码：区分普通单帧 / 多帧合成 / 特定合成模式 */
    const val MPTYPE_CODE = 0xD8A6

    /** 色彩格式 */
    const val COLOR_FORMAT = 0xD8A8

    /** 视频位深：8 / 10 / 12，10bit 素材据此标记 */
    const val VIDEO_BIT_DEPTH = 0xD8A9

    /** 是否为代理文件：代理是相片/视频的轻量副本，不该当作正片展示 */
    const val IS_MOVIE_PROXY = 0xD8AD

    /** 相机侧选定的传输尺寸 */
    const val CAMERA_SELECTED_TRANSFER_SIZE = 0xD8AF

    /** 视频剪辑标记 */
    const val VIDEO_SHOT_MARK = 0xD8B2

    /** 代表帧尺寸（字节）：据此判断代表帧能否替代整文件 */
    const val REPRESENTATIVE_SAMPLE_SIZE = 0xDC82

    /** 代表帧高度 */
    const val REPRESENTATIVE_SAMPLE_HEIGHT = 0xDC83

    /** 代表帧宽度 */
    const val REPRESENTATIVE_SAMPLE_WIDTH = 0xDC84
}

/**
 * 对象属性的数据类型（PTP DATA_TYPE）。
 *
 * 读取时相机逐项回报类型，同一个属性码在不同机型上类型可能不同
 * （例如 MP4 的 [SonyObjectPropCode.DURATION] 常回 UINT32，AVCHD 回 UINT16），
 * 因此解析必须按实际类型走，不能按属性码硬编码宽度。
 */
object SonyObjectPropDataType {
    const val UINT8 = 0x0002
    const val UINT16 = 0x0004
    const val UINT32 = 0x0006
    const val UINT64 = 0x0007
    const val INT8 = 0x0003
    const val INT16 = 0x0005
    const val INT32 = 0x0008
    const val INT64 = 0x0009
    const val STRING = 0xFFFF

    /** 该类型在负载里占多少字节；字符串返回 -1（长度由类型自带的字符串头决定） */
    fun byteWidth(dataType: Int): Int = when (dataType) {
        UINT8, INT8 -> 1
        UINT16, INT16 -> 2
        UINT32, INT32, UINT64, INT64 -> 4
        else -> -1
    }
}