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
 *
 * **构造即校验**：`capacity` 必须为正，`entries` 不许超过 `capacity`，`cursor` 必须落在
 * `-1..entries.lastIndex`（空历史因此只认 -1）。第三条今天没有调用方能触发
 * （唯一的构造路径是 record/undo/redo，它们自己维持上限），但「构造即校验」是这个类的卖点，
 * 留一个没人守的不变量等于留一个将来会静默烂掉的洞。下面几个方法全按「游标在列表内」来写，而主构造器带着 private 属性
 * 仍是公开的命名参数，挡得住的地方只有 init——这与 [EditRecipe] 同一决定。
 */
data class HistoryList<T>(
    val capacity: Int = DEFAULT_CAPACITY,
    private val entries: List<T> = emptyList(),
    private val cursor: Int = -1,
) {
    init {
        require(capacity > 0) {
            "上限至少得装下一条记录，capacity=$capacity 会把每一条都裁空，历史静默什么都不记"
        }
        require(entries.size <= capacity) {
            "条目数不能超过上限，否则第一条就被裁掉、而裁掉哪条取决于调用顺序；实际 size=${entries.size}, capacity=$capacity"
        }
        require(cursor in -1..entries.lastIndex) {
            "游标必须指在列表内，空历史只能是 -1；实际 cursor=$cursor, size=${entries.size}"
        }
    }

    /**
     * 游标指向的条目；空历史为 null。
     * 条目类型本身可空时这两者读起来一样（null 条目也是 null）。判「有没有当前项」用 [canUndo]/[canRedo]
     * 或 [size]——游标是 private 的，外部看不到，别在注释里把读者送去看一个他读不到的字段。
     */
    val current: T? get() = entries.getOrNull(cursor)

    // 能不能退只看游标自己的位置，不拿「列表长度减去游标」：上限淘汰会把条目整体前移，
    // 那样算出来的步数跟着淘汰漂移。cursor > 0 就是「头上还留着可退的条目」，
    // 于是游标永远指在列表内；越过最旧一条退不出去，被淘汰掉的更是回不去——那就是「上限」的含义。
    val canUndo: Boolean get() = entries.isNotEmpty() && cursor > 0
    val canRedo: Boolean get() = cursor >= 0 && cursor < entries.lastIndex

    /** 保留的条目数，不是「一共提交过几步」：被上限淘汰掉的最旧条目不再计数 */
    val size: Int get() = entries.size

    /**
     * 记录一条新状态：同值不记录，否则截断游标之后的重做尾，超上限淘汰最旧一条。
     *
     * 去重判在截断**之前**，所以「与当前同值的提交」连重做尾都不动：没记录就是什么都没改，
     * 不等于「编辑过一次」，尾巴上那些状态留着比被这次空提交悄悄吃掉更合调用方的预期。
     */
    fun record(item: T): HistoryList<T> {
        // 必须带上 cursor >= 0：空历史的 current 也是 null，只比 current 的话
        // 可空条目类型的 `record(null)` 会被「null == null」当成重复，第一条永远进不来。
        if (cursor >= 0 && current == item) return this
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
