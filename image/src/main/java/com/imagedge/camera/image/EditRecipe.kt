package com.imagedge.camera.image

import com.imagedge.camera.lut.ColorAdjust

/**
 * 一份完整的编辑描述：几何 + 调色 + 滤镜，同一个有序列表。
 *
 * 不可变，且**构造即校验**：init 要求「每个身份至多一步」与「rank 沿列表不降」。
 * `with` 是增量的写法，遵循**同类替换**：同身份的步骤就地替换、保持位置，新身份才追加并按
 * 规范顺序落位；预设解码器与 applyPreset 会拿手上的列表直接构造，同样落到这两条约束上——
 * 坏列表在构造时就抛，而不是渲染出错误的照片。这让「一份配方」既是渲染输入，也是撤销与
 * 预设的单位，三者共用同一个值，不存在两份状态互相追。
 *
 * 列表顺序在这一轮是**规范化且不可重排**的（几何 → 调色 → LUT）。顺序信息先存在于数据形状里，
 * 是为了下一轮真要多层时不必换形状；本轮没有任何 UI 能改变它，所以别去写可排序的渲染器。
 */
data class EditRecipe(val steps: List<EditStep> = emptyList()) {

    init {
        val identities = steps.map { it.identity }
        require(identities.size == identities.distinct().size) {
            "同一身份的步骤只能有一份，否则读取时会取到陈旧的那一份：$identities"
        }
        require(steps.zipWithNext().all { (a, b) -> a.rank <= b.rank }) {
            "步骤必须按规范顺序（几何 → 调色 → LUT），实际 rank：${steps.map { it.rank }}"
        }
    }

    /** 同身份就地替换，否则追加后按规范顺序落位 */
    fun with(step: EditStep): EditRecipe {
        val index = steps.indexOfFirst { it.identity == step.identity }
        val next = if (index >= 0) {
            steps.toMutableList().also { it[index] = step }
        } else {
            (steps + step).sortedBy { it.rank }
        }
        return EditRecipe(next)
    }

    /**
     * 按步骤类型删除（删不掉就是无操作，不抛）。
     *
     * 删的是**类型**，不是身份，所以 `without<EditStep.Flip>()` 会把 `flip:true` 与
     * `flip:false` 一起删掉——`Flip` 是唯一一个类型对应两个身份的步骤，也是这条 API
     * 唯一会「删多」的地方（其余类型按类型删至多一步）。
     *
     * **别拿它实现按方向的翻转撤销。** 水平与垂直今天是两个独立开关：`PhotoEditScreen`
     * 的 `edit_flip_h` 与 `edit_flip_v` 两枚 chip 分别接 `PhotoEditViewModel.toggleFlipHorizontal`
     * 与 `toggleFlipVertical`，两道可以并存，所以「撤销这一步翻转」必须是方向敏感的。
     * rank-0 只有 `Flip` 一个类型对应两个身份，按类型删正好把它多删一格——撤销一个方向
     * 会把另一个方向一起清掉，且没有任何地方会报错（`EditRecipeTest` 里那条 Flip 用例钉住
     * 的就是这个多删行为）。真要按方向删，得在**本模块**加带方向的写法：`identity` 是
     * internal，调用方连按身份过滤都写不出来，所以别在 :app 里绕，来这里加参数。
     */
    inline fun <reified S : EditStep> without(): EditRecipe =
        EditRecipe(steps.filterNot { it is S })

    /** 调色快照；没有 Color 步骤时是 NONE（恒等） */
    val colorAdjust: ColorAdjust
        get() = steps.filterIsInstance<EditStep.Color>().firstOrNull()?.adjust ?: ColorAdjust.NONE

    /** 滤镜槽；null = 从未设过滤镜 */
    val lut: EditStep.Lut?
        get() = steps.filterIsInstance<EditStep.Lut>().firstOrNull()

    /**
     * 拉直 / 旋转 / 翻转，**不含裁剪，也不含调色与滤镜**。
     *
     * 裁剪界面需要一张「已摆正但未裁开」的底图来画框，这个拆分就是它。
     * 与 alpha08 里 `geometrySteps()` + 单独处理 `crop` 的写法等价。
     */
    val geometryOnly: List<EditStep>
        get() = steps.filter { it.rank == 0 && it.identity != "crop" }

    /** 全部几何步骤（含裁剪），交 `ImagePipeline.renderGeometry` */
    val allSteps: List<EditStep>
        get() = steps.filter { it.rank == 0 }

    companion object {
        val EMPTY = EditRecipe()
    }
}

/**
 * 滤镜 key；没有 Lut 步骤时返回调用方给的「原图」常量。
 *
 * `:image` 不该认识 `:app` 的 `FILTER_NONE = "none"`，所以它作为参数进来。
 */
fun EditRecipe.lutKeyOrDefault(noFilterKey: String): String =
    lut?.key ?: noFilterKey

/**
 * 强度；没有 Lut 步骤时返回默认值。
 *
 * key 是「原图」占位值时**同样要返回它记的强度**：那是用户滑出来的值，alpha08 即使没套
 * LUT 也把它留在状态里，下次选滤镜接着用。别在这里顺手加「占位值就当没有 Lut」的过滤去
 * 「优化」掉占位步骤——那会静默丢掉强度，是行为变化，折叠等价性测试会红。
 */
fun EditRecipe.strengthOrDefault(default: Int): Int = lut?.strength ?: default
