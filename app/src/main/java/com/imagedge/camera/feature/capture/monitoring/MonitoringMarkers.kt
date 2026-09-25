package com.imagedge.camera.feature.capture.monitoring

import androidx.compose.ui.geometry.Rect
import com.imagedge.camera.data.model.AspectMarker
import com.imagedge.camera.data.model.GridMode

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 网格与比例标记的纯几何（T1）——只算位置，不碰像素、不发命令
 *     version: 1.0
 * </pre>
 */

/**
 * 监看辅助标记几何。
 *
 * 全部返回**归一化位置或内容矩形内的像素矩形**，由 `MonitoringScreen` 用 Canvas 叠加绘制。
 * 单独成物有两个理由：一是几何要在 JVM 单测里钉住（标记越界、除零、0 比例这类问题
 * 不需要真机就能发现）；二是保证「标记」永远是显示层叠加，
 * 不会哪天顺手变成对预览像素的裁剪——那会误导用户对成片构图的判断。
 */
object MonitoringMarkers {

    /**
     * 网格线在每一轴上的位置（0..1，等分点）。
     *
     * 横竖共用同一组：网格是正方形语义，横竖用同一批等分点才对得上。
     */
    fun gridFractions(mode: GridMode): List<Float> = when (mode) {
        GridMode.NONE -> emptyList()
        GridMode.THIRD -> listOf(1f / 3f, 2f / 3f)
        GridMode.QUARTER -> listOf(0.25f, 0.5f, 0.75f)
    }

    /**
     * 比例标记矩形：在 [content] 内**居中内接**该比例，取面积最大者。
     *
     * 只在画面范围内画（标记本身是监看提示，超出画面就没有参照意义）。
     *
     * @return null 表示不绘制——[AspectMarker.NONE]、比例非法（0/负数/NaN/无穷）、
     *         或内容矩形尚未就绪（宽高 ≤ 0）
     */
    fun aspectRect(content: Rect, marker: AspectMarker): Rect? {
        if (!marker.isDrawn) return null
        if (content.width <= 0f || content.height <= 0f) return null

        var width = content.width
        var height = width / marker.ratio
        if (height > content.height) {
            // 受高度限制：按比例回推宽度
            height = content.height
            width = height * marker.ratio
        }
        // 浮点误差可能让内接结果略超内容框，夹回来保证标记永不越界
        width = width.coerceAtMost(content.width)
        height = height.coerceAtMost(content.height)

        val left = content.left + (content.width - width) / 2f
        val top = content.top + (content.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }
}
