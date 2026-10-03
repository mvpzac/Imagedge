package com.imagedge.camera.core.export

/**
 * 导出解码长边上限：**按堆上限反推**，而不是写死一个数字。
 *
 * 全分辨率导出要同时持有源位图与输出位图（各 4 字节/像素，8 字节/像素），
 * 所以像素上限取堆上限的 1/[HEAP_BUDGET_RATIO]。写死 6000 会在小内存的机器上 OOM；
 * 只按源图原尺寸解码则会在 100MP 的机器上把同一张图导出成不同尺寸。
 *
 * 下限 [MIN_LONG_EDGE] 是为了让极端内存环境下的导出结果仍可用——一张 1080px 的
 * 「水印成片」没有意义，而空文件更糟。
 *
 * ## 这条预算并不覆盖位图像素本身
 *
 * [java.lang.Runtime.maxMemory] 是 **Java 堆**的上限。本项目 minSdk 29，而从 API 26 起
 * `Bitmap` 的像素数据分配在 **native 堆**里，不计入这个数。所以这里是「按 Java 堆
 * 推一个保守的边长」，**不是**对总占用的硬保证——它压低了解码尺寸、从而压低了 native 分配，
 * 但「堆上限 ÷ 3 就一定不 OOM」这个说法是不成立的，别照着这句去推断别的模块的内存占用。
 *
 * 另一处已知的失真：8 字节/像素来自「源 + 输出两张全尺寸位图」的算法。
 * 编辑调节按 256 行条带渲染，实际只多持有一条带；边框水印确实同时持有两张。
 * 两条路径共用这个常数是刻意的（同一个上限不该在两个文件里漂移），但它对前者偏保守。
 *
 * 为什么放在 `:core`：编辑调节与边框水印是两条独立的导出路径，但**同一条策略**。
 * 各自抄一份的结果就是同一个上限在两个文件里慢慢漂移。
 */
object ExportLimits {

    /** 堆上限的 1/3 留给导出，其余留给解码、GC 与界面。 */
    const val HEAP_BUDGET_RATIO = 3

    const val MIN_LONG_EDGE = 2048
    const val MAX_LONG_EDGE = 6000

    /**
     * 本次导出允许的最大长边（像素）。
     *
     * 注入 [maxMemory] 是为了能在 JVM 上验收——直接调 `Runtime` 的版本只能在真机上验证，
     * 而这条曲线正是那种「错了不报错、只在大图上 OOM」的地方。
     */
    fun maxLongEdge(maxMemoryBytes: Long): Int {
        val budgetBytes = maxMemoryBytes / HEAP_BUDGET_RATIO
        // 源位图 + 输出位图 → 每像素 8 字节
        val maxPixels = (budgetBytes / 8).coerceAtLeast(MIN_LONG_EDGE.toLong() * MIN_LONG_EDGE)
        val maxSide = kotlin.math.sqrt(maxPixels.toDouble()).toInt()
        return maxSide.coerceIn(MIN_LONG_EDGE, MAX_LONG_EDGE)
    }

    /** 真机入口。 */
    fun maxLongEdge(): Int = maxLongEdge(Runtime.getRuntime().maxMemory())
}