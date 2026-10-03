package com.imagedge.camera.image

/**
 * 边框水印的**版式参数与纯计算**。不碰 `Canvas`、不碰 `Paint`，因此能在 JVM 上直接单测。
 *
 * ## 为什么不留在 :app 的 ViewModel 里
 *
 * 原先五套模板是 `renderFrame` 的五个 `when` 分支，每个分支自带一套魔法比例
 * （`borderRatio = 0.045f`、`barH = w * 0.155f`、基线在信息栏的 0.44/0.78 处……）。
 * 那套写法的代价不是"难看"，是**渲染器一行都测不了**——量一个字符串要 `Paint`，
 * 要 `Paint` 就要 Android，于是整个排版只能靠肉眼看预览。比例一旦写错，
 * 表现形式是"某个机型上边距怪"，不会抛异常。
 *
 * 这里把两件事拆出来：[FrameSpec] 是参数（模板 → 一组比例），[FrameGeometry] 是纯函数
 * （参数 + 源图尺寸 → 成品尺寸；文本 + 可用宽度 → 字号或截断串）。
 * Canvas 那层只剩"把算好的数交给 Paint"。
 *
 * ## 一条支撑导出分辨率修复的不变式
 *
 * 所有尺寸都按**源图宽**取比例，`coerceAtLeast` 的下限只在小图上兜底。
 * 1600px 预览与 6000px 全分辨率导出的版式因此**逐像素同构**——
 * 这正是「导出改按原分辨率重算」不需要同时重排版的前提。
 * [proportionalAbove] 把这个不变式写成了可断言的函数，而不是留在注释里。
 */
object FrameGeometry {

    /**
     * 一套模板的版式参数。
     *
     * @param sideMarginRatio 四周留白占源图宽的比例；`0` 表示贴边
     * @param sideMarginMin 留白的像素下限（只在小图上兜底）
     * @param barRatio 底部信息栏高度占源图宽的比例
     * @param barMin 信息栏高度的像素下限
     * @param addsSideMargin 贴边模板（签名式）为 false，此时成品宽等于源图宽
     */
    data class FrameSpec(
        val sideMarginRatio: Float,
        val sideMarginMin: Int,
        val barRatio: Float,
        val barMin: Int,
        val addsSideMargin: Boolean,
    )

    /** 算好的成品几何。[barTop] 是信息栏上沿，即照片底部。 */
    data class FrameOutput(
        val width: Int,
        val height: Int,
        val sideMargin: Int,
        val barHeight: Int,
    ) {
        val barTop: Int get() = height - barHeight
    }

    /** 经典白边：四边留白 + 底部信息栏。 */
    val FRAMED: FrameSpec = FrameSpec(0.045f, 16, 0.155f, 110, addsSideMargin = true)

    /** 拍立得：留白更大、信息栏更高。 */
    val POLAROID: FrameSpec = FrameSpec(0.085f, 24, 0.20f, 72, addsSideMargin = true)

    /** 双行签名：贴边，只有信息栏。 */
    val SIGNATURE: FrameSpec = FrameSpec(0f, 0, 0.17f, 120, addsSideMargin = false)

    /** 极简叠字：不改变画面尺寸，文字压在照片内部。 */
    val MINIMAL: FrameSpec = FrameSpec(0f, 0, 0f, 0, addsSideMargin = false)

    /**
     * 成品画布尺寸。
     *
     * 一条式子覆盖四套模板：`高 = 源高 + 留白 + 信息栏`。贴边模板的留白与信息栏都是 0，
     * 于是自动退化成 `源高 + 信息栏`；极简叠字两者皆 0，退化成源图原尺寸——不需要 if。
     */
    fun output(spec: FrameSpec, srcWidth: Int, srcHeight: Int): FrameOutput {
        val margin = (srcWidth * spec.sideMarginRatio).toInt().coerceAtLeast(spec.sideMarginMin)
        val bar = (srcWidth * spec.barRatio).toInt().coerceAtLeast(spec.barMin)
        return FrameOutput(
            width = if (spec.addsSideMargin) srcWidth + margin * 2 else srcWidth,
            height = srcHeight + margin + bar,
            sideMargin = margin,
            barHeight = bar,
        )
    }

    /**
     * 这个尺寸下的比例换算是否**已经脱离**下限——即把同一套版式按更大的源图重算，
     * 每一步都是纯比例放大。
     *
     * 导出改按原分辨率重算时，这是"不用重排版"的那条保证。别把它当注释读：
     * 有人给某个模板加一个 `coerceAtLeast(200)` 的栏高，这条就开始返回 false，
     * 而预览与成品会**在预览尺寸以下不一致、以上一致**——那种 bug 只在用户拿小图导出时才显形。
     *
     * 判据是所有下限都已在 `srcWidth` 处被比例值超过，即 `srcWidth * ratio >= min`。
     */
    fun proportionalAbove(spec: FrameSpec, srcWidth: Int): Boolean {
        val margin = srcWidth * spec.sideMarginRatio
        val bar = srcWidth * spec.barRatio
        return (spec.sideMarginRatio <= 0f || margin >= spec.sideMarginMin) &&
            (spec.barRatio <= 0f || bar >= spec.barMin)
    }

    /**
     * 自适应字号：按 [baseSize] 量一次，放得下就用它，否则等比缩到 [maxWidth]。
     *
     * 返回值**不低于 [minSize]**——所以下限之外还放不下时，调用方必须自己截断，
     * 否则文字会直接画出画布。原先这里返回下限就结束了，画布上表现为型号被切断或
     * 与右侧自定义文字叠在一起，两处都不报错。
     *
     * @param measureAt 给定字号返回文本宽度。传 lambda 而不是 [android.graphics.Paint]
     *   是这个函数能被 JVM 单测的唯一原因。
     */
    fun fitTextSize(
        baseSize: Float,
        minSize: Float,
        maxWidth: Float,
        measureAt: (Float) -> Float,
    ): Float {
        if (maxWidth <= 0f) return baseSize
        val measured = measureAt(baseSize)
        if (measured <= maxWidth) return baseSize
        return (baseSize * maxWidth / measured).coerceAtLeast(minSize)
    }

    /**
     * 截断到 [maxWidth]，末尾补 [ellipsis]。
     *
     * 二分字符数：文本长度的对数时间。**按码点而非 char 截断**，否则一个代理对被劈成两半，
     * 渲染出来是一个替换字符（`U+FFFD`）——比截断本身难看得多。
     *
     * 放得下时原样返回，不分配新串。
     */
    fun ellipsize(
        text: String,
        maxWidth: Float,
        ellipsis: String = "…",
        measure: (CharSequence) -> Float,
    ): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (measure(text) <= maxWidth) return text
        if (measure(ellipsis) > maxWidth) return ""

        val codePoints = text.codePointCount(0, text.length)
        var lo = 0
        var hi = codePoints
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val cut = text.offsetByCodePoints(0, mid)
            if (measure(text.substring(0, cut) + ellipsis) <= maxWidth) lo = mid else hi = mid - 1
        }
        if (lo <= 0) return ellipsis
        return text.substring(0, text.offsetByCodePoints(0, lo)) + ellipsis
    }

    /**
     * 快门速度的显示格式。
     *
     * 优先用 `ExposureTime`（秒）。**它在部分机型上缺失**，此时退回 APEX 的
     * `ShutterSpeedValue`：曝光时间 = 2^(-tv)。不退回的表现是快门字段整个空掉——
     * 而用户看到的是「这台相机不支持」而不是「这条 EXIF 缺了」。
     *
     * 两个来源都没有时返回空串（字段留空但不编造），与其它缺字段的处理一致。
     *
     * @param seconds ExposureTime，单位秒
     * @param apexTv ShutterSpeedValue，单位 APEX 值（tv）
     */
    fun formatShutter(seconds: Double?, apexTv: Double?): String {
        val exposure = seconds ?: apexTv?.let { Math.pow(2.0, -it) } ?: return ""
        if (exposure <= 0.0) return ""
        // 两支都带单位 `s`：原先子秒那支写成 "1/%.0f"，于是同一画面上
        // 长曝显示「2s」而快门显示「1/125」——缺单位的不是显示习惯，是不一致
        return if (exposure >= 1.0) "%.0fs".format(exposure)
        else "1/%.0fs".format(1.0 / exposure)
    }

    /**
     * 左右两段共处一行时的可用宽度：整行宽度减去右段宽度与间距。
     *
     * 此前两段各自按"整行宽"自适应字号，于是两段各自都觉得放得下，合起来超出画布——
     * 表现为型号与署名**静默叠在一起**。先分宽度再各自自适应，这一条才成立。
     *
     * @return 右段不存在时返回整行宽度。
     */
    fun leftBudgetOf(totalWidth: Float, rightWidth: Float, gap: Float): Float =
        if (rightWidth <= 0f) totalWidth
        else (totalWidth - rightWidth - gap).coerceAtLeast(0f)
}