package com.imagedge.camera.image

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 镜头校正的**位图**入口：按横向条带流式重采样。
 *
 * ## 为什么不能直接用 [LensResample.correctRgba]
 *
 * 那一版把整张图搬成 RGBA 字节再搬回来，在 21.3 MP（5328×4000）的照片上一次性分配
 * **约 426 MB**（IntArray + 两个 ByteArray + IntArray + 新 Bitmap，各 85 MB）。
 * 真机上导出那张照片的实测：应用 PSS 402 MB、`Choreographer: Skipped 335 frames`，
 * 导出停在「正在读取这张图…」超过五分钟——不是死锁，是分配风暴。
 * 而 512×512 的仪器化用例永远暴露不出来，它们的图太小。
 *
 * ## 为什么是「条带」而不是「逐行」
 *
 * 径向重映射把输出像素指向**任意**源行。只留两行缓冲会取到错误的行——
 * 那是错，不是慢。正确做法是按横向条带处理：一条带需要的源行是一段**连续**区间，
 * 把那段读进缓冲再渲染。
 *
 * ## 内存
 *
 * 条带缓冲 = `(条带高 + 2·位移) × 行宽 × 4`，位移用 [LensCorrection.bandSlack] 的**精确上界**。
 * 21.3 MP 上滑条两端的最大位移是 84 行（k1 = 0.02）与 107 行（k1 = −0.02），缓冲 6~7 MB。
 *
 * 但**峰值不是缓冲**：输出位图本身 81 MB，两条路都要花。所以总足迹是从约 426 MB 降到
 * 约 88 MB——4.8 倍，不是「425 MB 压到 20 MB」那种量级。省下来的是那 340 MB 的 Java 堆
 * 往返拷贝，省不掉的是最终那张图。
 *
 * ## 正确性
 *
 * 数学全在 [LensCorrection]（JVM 17 条用例 + `BandSlackTest` 的逐像素界对拍）。本文件只做搬运，
 * 因此它的正确性靠**对拍**盯住：`LensResampleBitmapTest`（仪器化）断言条带结果与
 * [LensResample.correctRgba] 的整图结果逐像素相同。
 *
 * 那条对拍的画幅要求不是「比条带高就行」——**首条与末条的越界是无害的**：它们本来就覆盖到
 * 画面边缘，与整图版把坐标钳到 `[0, h−1]` 的结果一致。真正会取到错误的行的是**中间条带**，
 * 所以画幅必须高到有一条离画面中心足够远的中间条带。实测：`180×320`、`320×180` 都抓不住
 * 位移界估松，`320×900` 抓得住（界估松时最差差 4，位置 `(0,128)`——第一个中间条带的首行）。
 *
 * @param source 不得被修改。
 * @return 新的位图；[LensCorrectionParams.isIdentity] 时**原样返回入参**（同一实例）。
 */
fun correctLensDistortion(source: Bitmap, rawParams: LensCorrectionParams): Bitmap {
    val params = rawParams.coerceIntoRange()
    if (params.isIdentity) return source
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0) return source

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    // 这条带上下各要多读多少行。用精确上界而不是估的——估松了会静默错图，见 bandSlack
    val slack = LensCorrection.bandSlack(w, h, params)
    val bandHeight = 128

    val maxRows = (bandHeight + 2 * slack).coerceAtMost(h)
    val src = IntArray(maxRows * w)
    val dst = IntArray(w)

    // 每像素算一次的三个通道偏移，循环外分配一次
    val dx = FloatArray(3)
    val dy = FloatArray(3)
    val midX = (w - 1) / 2f
    val midY = (h - 1) / 2f

    var bandTop = 0
    while (bandTop < h) {
        val bandBottom = minOf(bandTop + bandHeight, h)
        val readTop = (bandTop - slack).coerceAtLeast(0)
        val readBottom = minOf(bandBottom + slack, h)
        val rows = readBottom - readTop
        source.getPixels(src, 0, w, 0, readTop, w, rows)

        for (y in bandTop until bandBottom) {
            for (x in 0 until w) {
                // 一次算三个通道的偏移：畸变只算一次，色差才是分通道的那部分
                LensCorrection.offsetsOf(x.toFloat(), y.toFloat(), w, h, params, dx, dy)

                val r = sample(src, w, readTop, rows, h, midX + dx[0], midY + dy[0], 0)
                val g = sample(src, w, readTop, rows, h, midX + dx[1], midY + dy[1], 1)
                val b = sample(src, w, readTop, rows, h, midX + dx[2], midY + dy[2], 2)
                val alpha = src[(y - readTop) * w + x] ushr 24
                // alpha 逐位透传：校正动的是取样位置，不是不透明度，
                // 而任何一次重采样都会让半透明边缘的 alpha 变浑
                dst[x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(dst, 0, w, 0, y, w, 1)
        }
        bandTop = bandBottom
    }
    return out
}

/**
 * 从条带缓冲里做一次双线性取样。越界钳到画面边缘——取到画面外要么变黑（凭空一块黑洞），
 * 要么环绕（把边缘糊到对边），钳边是唯一不引入新东西的失败方式。
 *
 * 条带缓冲只覆盖 `[readTop, readTop+rows)`，行下标因此被钳进这一段——读出去最多是
 * 「复制一条边缘行」的糊带，不会越界取到别人的内存。
 *
 * 这条钳制在 [LensCorrection.bandSlack] 的精确上界下**不可达**，它是兜底而不是常态：
 * 位移界一旦被改松，它会把越界读变成静默错图而不是崩溃，所以它必须留着。
 */
private fun sample(
    src: IntArray,
    width: Int,
    readTop: Int,
    rows: Int,
    height: Int,
    fx: Float,
    fy: Float,
    channel: Int,
): Int {
    if (fx.isNaN() || fy.isNaN()) return 0
    val cx = fx.coerceIn(0f, (width - 1).toFloat())
    val cy = fy.coerceIn(0f, (height - 1).toFloat())
    val x0 = floor(cx).toInt()
    val y0 = floor(cy).toInt()
    val x1 = (x0 + 1).coerceAtMost(width - 1)
    val y1 = y0 + 1
    val tx = cx - x0
    val ty = cy - y0

    // ARGB 里 A 在 24..31、R 在 16..23、G 在 8..15、B 在 0..7。
    // 写成 `24 - 8*channel` 会让 channel=0 取到**alpha**——本文件第一版就是这么错的，
    // 而它只被 `LensResampleBitmapTest` 的逐像素对拍逮到（最差差值 255）
    fun at(y: Int, x: Int): Int =
        (src[(y - readTop).coerceIn(0, rows - 1) * width + x] ushr (16 - 8 * channel)) and 0xFF

    val c00 = at(y0, x0)
    val c10 = at(y0, x1)
    val c01 = at(y1, x0)
    val c11 = at(y1, x1)

    val t = c00 + (c10 - c00) * tx
    val b = c01 + (c11 - c01) * tx
    // 舍入规则必须与 [LensResample] 那一版**同一条**：那边走 `roundToInt()`，在 `.5` 上
    // 取偶；`kotlin.math.round()` 取入。同一个恰好落在 `.5` 的取样点，两条规则差 1 个码值。
    //
    // 这不是已验证的缺陷：把这里的图换成 ties 取入，对拍仍然全绿——现有测试图构造不出
    // 命中 ties 的取样点。所以这是一条**约束**而不是一条修复：两套实现给出不同的照片
    // 本身就是 bug（同一张照片在「开校正」与「没校正」两条路径上得到两个结果），
    // 而 ties 是二者唯一还可能分岔的地方。若哪天要改动这一行，先造一张能命中 ties 的图。
    return (t + (b - t) * ty).roundToInt().coerceIn(0, 255)
}
