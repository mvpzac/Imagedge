package com.imagedge.camera.raw

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : RAW 解码抽象（混合策略：内嵌 JPEG 秒开预览 + libraw 真解码）
 *     version: 1.0
 * </pre>
 */

/**
 * RAW 解码接口
 * M1：先落地内嵌 JPEG 预览解析（纯 Kotlin，TIFF 头扫描）
 * M2：接入 libraw 真解码（NDK 裁剪，仅 Sony ARW）
 */
interface RawDecoder {

    /**
     * 解析 RAW 文件内嵌 JPEG 预览（秒开，供列表/快速预览）
     * @param bytes RAW 文件字节流（调用方负责读取）
     * @return 内嵌 JPEG 字节流；无内嵌 JPEG 时返回 null
     */
    suspend fun decodeEmbeddedJpeg(bytes: ByteArray): ByteArray?

    /**
     * 完整解码 RAW（libraw，去马赛克 + 白平衡 + 色彩空间）
     * @param bytes RAW 文件字节流
     * @return 解码后的像素数据（RGBA8，按行优先）
     */
    suspend fun decodeFull(bytes: ByteArray): DecodedImage?
}

/**
 * 解码结果数据类
 * @param pixels RGBA8 像素数组（width × height × 4）
 * @param width 图像宽
 * @param height 图像高
 */
data class DecodedImage(
    val pixels: ByteArray,
    val width: Int,
    val height: Int
)
