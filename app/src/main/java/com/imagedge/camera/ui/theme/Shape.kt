package com.imagedge.camera.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 形状 Token（圆角只取 5 档，全项目统一）
 *              4dp 标签/小元素 · 8dp 按钮/输入框 · 12dp 卡片
 *              16dp 大容器/弹窗 · 999dp 胶囊/头像
 *     version: 1.0
 * </pre>
 */

/** 圆角刻度 */
object Radius {
    val Tag = 4.dp        // 标签、角标、小色块
    val Control = 8.dp    // 按钮、输入框、下拉项
    val Card = 12.dp      // 卡片、列表项
    val Container = 16.dp // 大容器、底部弹窗
    val Sheet = 28.dp     // 半屏弹窗顶部圆角
    val Pill = 50         // 胶囊 / 头像（百分比，配合 RoundedCornerShape(percent = 50)）
}

val ImagedgeShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Tag),
    small = RoundedCornerShape(Radius.Control),
    medium = RoundedCornerShape(Radius.Card),
    large = RoundedCornerShape(Radius.Container),
    extraLarge = RoundedCornerShape(Radius.Sheet)
)

/** 胶囊形（头像、Chip、状态标签） */
val PillShape = RoundedCornerShape(percent = Radius.Pill)
