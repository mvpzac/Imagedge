package com.imagedge.camera.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.imagedge.camera.R

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 字体刻度 v3.1（对齐 UI 设计规范 7 级刻度）
 *              - 字号只取 7 级：48 / 36 / 30 / 24 / 16 / 14 / 12
 *              - 字重只取三档：400 Regular / 500 Medium / 700 Bold
 *              - 行高：正文 1.5~1.75，标题 1.1~1.3
 *              - 字体：Inter（拉丁字形）+ 系统中文字体自动兜底
 *                Inter 不含汉字，Compose 会自动 fallback 到系统字体，
 *                因此只需一套 FontFamily，中英文都能正常显示。
 *     version: 3.1
 * </pre>
 */

/** 字号刻度（全项目只允许取这 7 个值） */
object FontSize {
    val Display = 48.sp   // 首屏大标题
    val H1 = 36.sp        // 页面主标题
    val H2 = 30.sp        // 区块标题
    val H3 = 24.sp        // 卡片 / 分组标题
    val Body = 16.sp      // 正文
    val Small = 14.sp     // 次要说明
    val Caption = 12.sp   // 角标 / 时间戳
}

/** Inter 字体族（只打包 Regular / Medium / Bold 三档，控制 APK 体积） */
/** 得意黑（Smiley Sans Oblique）：品牌展示用标题字体，覆盖拉丁+中文 */
val SmileySansFamily = FontFamily(Font(R.font.smiley_sans_oblique))

val InterFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** 所有样式的基底：统一挂 Inter，各角色只改字号 / 字重 / 行高 */
private val base = TextStyle(fontFamily = InterFontFamily)

/**
 * Imagedge 排版系统。
 * 层级靠「字号 + 字重」双重区分，不靠单纯放大字号。
 */
val ImagedgeTypography = Typography(
    // Display 48 —— 首屏品牌字
    displayLarge = base.copy(
        fontSize = FontSize.Display,
        fontWeight = FontWeight.Medium,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp
    ),
    // H1 36 —— 页面主标题
    headlineLarge = base.copy(
        fontSize = FontSize.H1,
        fontWeight = FontWeight.Bold,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp
    ),
    // H2 30 —— 区块标题
    headlineMedium = base.copy(
        fontSize = FontSize.H2,
        fontWeight = FontWeight.Bold,
        lineHeight = 38.sp
    ),
    // H3 24 —— 卡片 / 分组标题
    titleLarge = base.copy(
        fontSize = FontSize.H3,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp
    ),
    // PageHeader 等二级页标题（此前未定义，回落到 M3 默认系统字体）
    // 刻意与 titleLarge 一致（同 24sp Bold），为 PageHeader 提供显式角色
    headlineSmall = base.copy(
        fontSize = FontSize.H3,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp
    ),
    // 卡片/列表项标题用 titleMedium，行内强调用 titleSmall
    titleMedium = base.copy(
        fontSize = FontSize.Body,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp
    ),
    titleSmall = base.copy(
        fontSize = FontSize.Body,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    // Body 16 —— 正文，行高 1.5
    bodyLarge = base.copy(
        fontSize = FontSize.Body,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    // Small 14 —— 次要说明
    bodyMedium = base.copy(
        fontSize = FontSize.Small,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = base.copy(
        fontSize = FontSize.Small,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // Caption 12 —— 角标 / 时间戳
    labelSmall = base.copy(
        fontSize = FontSize.Caption,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelLarge = base.copy(
        fontSize = FontSize.Small,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = base.copy(
        fontSize = FontSize.Caption,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
