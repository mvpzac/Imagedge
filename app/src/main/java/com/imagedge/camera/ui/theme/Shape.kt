package com.imagedge.camera.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 形状 Token（圆角 6 档（含半屏 Sheet），全项目统一）
 *              8dp 标签 · 12dp 按钮/输入框 · 16dp 卡片
 *              20dp 大容器/分组 · 28dp 半屏弹窗顶部 · 50% 胶囊
 *     version: 2.0
 * </pre>
 */

/** 圆角刻度（最小 8，全项目无直角感） */
object Radius {
    val Tag = 8.dp        // 标签、角标、小元素
    val Control = 12.dp   // 按钮、输入框、下拉项
    val Card = 16.dp      // 卡片、列表项
    val Container = 20.dp // 大容器、分组面板
    val Sheet = 28.dp     // 半屏弹窗顶部圆角
    val Pill = 50         // 胶囊 / 头像（百分比）
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
