package com.imagedge.camera.ui.theme

import androidx.compose.ui.unit.dp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 页面结构重构（批次 A）新增的尺寸 token：最小值与上限，不是固定页面高度
 *     version: 1.0
 * </pre>
 */

/**
 * 结构尺寸的唯一来源。
 *
 * 与 [Spacing] / [Radius] 的分工：Spacing 管元素之间的呼吸，Radius 管圆角，
 * 这里管**可点击与可读的最小尺寸**以及版面上限。大字模式下内容按自身增高，
 * 这些值只是地板——把它们当固定高度用就会裁字。
 *
 * 页面不散写数值：预览验证后若要调整，全项目从此处同步。
 */
object UiSize {
    /** 任何可点击元素的最小触控边（无障碍底线，与 UI-SPEC §8 一致） */
    val TouchMin = 48.dp

    /** 页面标题栏最小高度；文字更高时随之增高，不裁切 */
    val HeaderMin = 56.dp

    /** 列表/设置行最小高度；内容可换行 */
    val RowMin = 64.dp

    /** 底部导航内容最小高度（图标 + 文字标签） */
    val NavigationMin = 64.dp

    /** 照片网格自适应列的最小格宽 */
    val PhotoTileMin = 104.dp

    /** 表单与长说明的最大宽度；宽屏居中，避免一行读到头 */
    val FormMaxWidth = 600.dp

    /** 遥控主快门视觉直径 */
    val ShutterDiameter = 72.dp
}
