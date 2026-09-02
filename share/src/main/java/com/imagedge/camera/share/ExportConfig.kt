package com.imagedge.camera.share

/**
 * 导出配置与策略（:share）。
 *
 * 「分享」环节的三大决策：导出多大、什么格式、带不带元数据。
 */

/**
 * 导出尺寸档位。
 *
 * 社交平台普遍会二次压缩，传原图既慢又无意义；
 * 提供常用档位让用户按用途选择（备份用原图，发朋友圈用 1080p）。
 */
enum class ExportSize(val label: String, val maxLongEdge: Int?) {
    /** 原图：不做缩放，用于备份或后续精修 */
    ORIGINAL("原图", null),

    /** 2048px 长边：冲印 / 高清分享 */
    P2048("2048px", 2048),

    /** 1080px 长边：社交平台主流尺寸 */
    P1080("1080px", 1080),

    /** 约 200 万像素：与 Sony 官方 App 的「2M 传输」同档，体积最小 */
    M2("2M", null);

    companion object {
        /** 2M 档的像素上限 */
        const val M2_MAX_PIXELS = 2_000_000
    }
}

/**
 * 导出格式。
 * @param supportsExif 该格式能否承载 EXIF——PNG 无标准 EXIF 容器，
 *                     选 PNG 时元数据策略自动失效（UI 需据此提示）。
 */
enum class ExportFormat(val mime: String, val extension: String, val supportsExif: Boolean) {
    JPEG("image/jpeg", "jpg", true),
    PNG("image/png", "png", false),
    WEBP("image/webp", "webp", true)
}

/**
 * EXIF 隐私策略——分享场景的核心诉求。
 *
 * 相机照片的 EXIF 里往往带 GPS 坐标、机身序列号、镜头信息，
 * 直接发到公开平台等于泄露拍摄地点和设备信息。
 */
enum class ExifPolicy(val label: String) {
    /** 保留全部：本地备份、或信任的接收方 */
    KEEP_ALL("保留全部信息"),

    /** 仅清除位置：最常用的选择——保留摄影参数，去掉坐标 */
    STRIP_LOCATION("仅清除位置"),

    /** 清除全部：发给公开平台时的稳妥选择 */
    STRIP_ALL("清除全部信息")
}

/**
 * 一次导出的完整配置。
 *
 * @param size 尺寸档位
 * @param format 输出格式
 * @param quality 有损压缩质量（1-100）；PNG 为无损，忽略此项
 * @param exif 元数据策略
 */
data class ExportConfig(
    val size: ExportSize = ExportSize.ORIGINAL,
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 95,
    val exif: ExifPolicy = ExifPolicy.KEEP_ALL
) {
    init {
        require(quality in 1..100) { "导出质量必须在 1..100，当前为 $quality" }
    }

    /** 该配置下元数据策略是否真的生效（PNG 无 EXIF 容器） */
    val exifEffective: Boolean
        get() = format.supportsExif && exif != ExifPolicy.STRIP_ALL
}
