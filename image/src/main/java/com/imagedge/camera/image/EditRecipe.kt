package com.imagedge.camera.image

import com.imagedge.camera.lut.ColorAdjust

/**
 * 一份完整的编辑描述：几何 + 调色 + 滤镜，同一个有序列表。
 *
 * 不可变。`with` 是唯一的增长方式，且遵循**同类替换**：同身份的步骤就地替换、保持位置，
 * 新身份才追加并按规范顺序落位。这让「一份配方」既是渲染输入，也是撤销与预设的单位——
 * 三者共用同一个值，不存在两份状态互相追。
 *
 * 列表顺序在这一轮是**规范化且不可重排**的（几何 → 调色 → LUT）。顺序信息先存在于数据形状里，
 * 是为了下一轮真要多层时不必换形状；本轮没有任何 UI 能改变它，所以别去写可排序的渲染器。
 */
data class EditRecipe(val steps: List<EditStep> = emptyList()) {

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

    /** 去掉某个身份的步骤（撤销裁剪、关掉滤镜等） */
    fun without(identity: String): EditRecipe =
        EditRecipe(steps.filterNot { it.identity == identity })

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
