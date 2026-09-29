package com.imagedge.camera.data.hdr

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 把一张基础图连同它的增益图写成 **Ultra HDR JPEG**。
 *
 * 一个 Ultra HDR 文件是两张图：基础图存正常看到的颜色，增益图每像素存一个「这里该亮多少」。
 * 同一份文件在 HDR 屏上是 HDR，在 SDR 屏上自动回落成普通照片——这就是它能被转发、
 * 也正是它在国产系统相册里多半看不出效果的原因（文件合规，但相册不渲染）。
 *
 * **应用自己不压缩增益图**。AOSP 的 `Bitmap_compress` 根本不碰增益图，只把整张
 * `Bitmap`（连同挂着的 `Gainmap`）转交给 hwui；增益图是以 8 位 JPEG 写进文件的，
 * 而官方格式明确说「增益图内嵌的色彩描述会被忽略」，所以用 `ALPHA_8`——
 * 单通道、无色彩空间、只有基础图体积的三分之一。
 *
 * ⚠️ **本类只做搬运，不做判断**：增益图里的每个数从哪来是另一条路
 * （从 SDR 反推 / 多张包围曝光 / 相机直出），还没裁定。在那条路定下来之前，
 * 调本类的人必须自己保证 [gainMap] 的内容与 [ratioMin]/[ratioMax] 相容。
 *
 * **当前唯一的调用方是 `UltraHdrRoundTripTest` 这条仪器化测试**，编辑器的导出走的是
 * 另一条路——它是把**源照片已有的**那份 `Gainmap` 直接挂回成品（`Bitmap.setGainmap`），
 * 不经本类。导出路径不需要本类，是因为 v1 只透传、不生成。
 * 「从增益值算出一张图再编码」这件事要等「HDR 从哪来」定了才有第二个调用方。
 *
 * 见 `UltraHdrRoundTripTest`：写出去再读回来的那一圈，包括「ALPHA_8 是否原样保留」。
 */
object UltraHdrEncoder {

    /**
     * 与增益图元数据配套的偏置。写进文件里，所以编解码两侧天然一致——
     * 它必须与增益图的**生成**侧用同一个值，否则暗部的增益会整体偏掉。
     */
    const val EPSILON = 1e-4f

    /**
     * @param gainMap 增益图的像素，一字节一个值，宽 × 高。
     * @return JPEG 字节；**`null` 表示这台机器做不了**（低于 API 34），
     *   调用方必须据此给用户一个说得出原因的回执，不能悄悄退回去导一张普通照片。
     */
    fun encode(
        base: Bitmap,
        gainMap: ByteArray,
        gainMapWidth: Int,
        gainMapHeight: Int,
        ratioMin: Float,
        ratioMax: Float,
        quality: Int,
    ): ByteArray? {
        // Gainmap 是 API 34 才有的类。整个方法只在这一个分支里碰它，
        // 所以低版本不会在类加载时就炸——那是最难查的一种崩溃
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (gainMapWidth <= 0 || gainMapHeight <= 0) return null
        if (gainMap.size < gainMapWidth * gainMapHeight) return null

        val contents = Bitmap.createBitmap(gainMapWidth, gainMapHeight, Bitmap.Config.ALPHA_8)
        // ALPHA_8 的 rowBytes 就等于 width，所以 w·h 字节正好填满，多一个字节都没有
        contents.copyPixelsFromBuffer(ByteBuffer.wrap(gainMap))

        val map = Gainmap(contents).apply {
            // 这两个收的是**普通倍率**，不是 log2。见 GainMap 里的警告：
            // 文档里那句 log2(min_content_boost) 说的是解码公式内部那层显示能力归一化，
            // 照它改会让 0.25 被当成 2^0.25、4 被当成 16，而导出侧一次都不报错
            setRatioMin(ratioMin, ratioMin, ratioMin)
            setRatioMax(ratioMax, ratioMax, ratioMax)
            setGamma(1.0f, 1.0f, 1.0f)
            setEpsilonSdr(EPSILON, EPSILON, EPSILON)
            setEpsilonHdr(EPSILON, EPSILON, EPSILON)
        }

        val out = ByteArrayOutputStream()
        base.gainmap = map
        if (!base.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
        return out.toByteArray()
    }
}
