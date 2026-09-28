package com.imagedge.camera.image

/**
 * 编辑历史：不可变条目列表 + 一个游标。
 *
 * 为什么不是命令模式：编辑状态是一个可整体替换的不可变值（[EditRecipe]），撤销就是退回上一个值，
 * 不必为每种步骤另写一份反向操作——反向操作要自己写对，快照只要值本身不可变。
 * 一份配方是十几个小对象，几百字节而已。
 *
 * 两条刻意的取舍，都是参考实现里踩过的：
 * - **只存引用，所以条目必须是纯值**。Gallery2 的 HistoryItem 既共享一个可变 preset
 *   （原地一改，历史跟着烂），又每项缓存一张预览图且没有上限（内存事故）。本类不替条目算任何
 *   东西，但它拦不住调用方把位图塞进 `T`——那条约束写在这里，别以为有防线。
 * - **同值去重只是兜底，不是去抖**。滑条一次拖动经过的是几十个**互不相同**的中间值，
 *   这一层拦不住也不该在这里拦——「什么时候算一步」只有调用方知道，它自己决定何时提交；
 *   这里只保证重复提交同一个值不占格子。
 *
 * 除无操作外一律返回新实例：`record` 碰到同值、`undo`/`redo` 走到头时原样返回 `this`。
 * 上限淘汰最旧一条，永不淘汰游标所在项。
 */
data class HistoryList<T>(
    val capacity: Int = DEFAULT_CAPACITY,
    private val entries: List<T> = emptyList(),
    private val cursor: Int = -1,
) {
    /** 游标指向的条目；空历史为 null */
    val current: T? get() = entries.getOrNull(cursor)

    // 能不能退只看游标自己的位置，不拿「列表长度减去游标」：上限淘汰会把条目整体前移，
    // 那样算出来的步数跟着淘汰漂移。cursor > 0 就是「头上还留着可退的条目」，
    // 于是游标永远指在列表内；越过最旧一条退不出去，被淘汰掉的更是回不去——那就是「上限」的含义。
    val canUndo: Boolean get() = entries.isNotEmpty() && cursor > 0
    val canRedo: Boolean get() = cursor >= 0 && cursor < entries.lastIndex
    val size: Int get() = entries.size

    /** 记录一条新状态：截断游标之后的重做尾，同值不记录 */
    fun record(item: T): HistoryList<T> {
        if (current == item) return this
        val kept = entries.take(cursor + 1) + item
        val trimmed = if (kept.size > capacity) kept.drop(kept.size - capacity) else kept
        return copy(entries = trimmed, cursor = trimmed.lastIndex)
    }

    fun undo(): HistoryList<T> =
        if (canUndo) copy(cursor = cursor - 1) else this

    fun redo(): HistoryList<T> =
        if (canRedo) copy(cursor = cursor + 1) else this

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
