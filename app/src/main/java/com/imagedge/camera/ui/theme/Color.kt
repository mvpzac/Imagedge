package com.imagedge.camera.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 色彩 Token v5.0（极简黑白）
 *              浅色 = 浅灰底 + 近黑主调（Ink），深色 = 近白主调。
 *              三条硬规矩：
 *              1. 主色只有黑白两态（浅 InkLight / 深 InkDark），无彩色主色
 *              2. 语义色只表状态，状态必须「图标 + 文字」双保险，不单独靠颜色
 *              3. 纯黑仅允许用于看图全屏背景（ViewerBackdrop 例外）
 *     version: 5.0
 * </pre>
 */

// ── 主色（极简黑白：浅色=近黑，深色=近白）─────────────────────
val InkLight = Color(0xFF1A1B1E)          // 浅色主题主按钮/强调
val OnInkLight = Color(0xFFFAFAFB)        // 主按钮上的文字
val InkDark = Color(0xFFE8E9EB)           // 深色主题主按钮/强调
val OnInkDark = Color(0xFF17181A)

// ── 中性色：浅色（冷调浅灰底 + 近黑文字）──────────────────────
val BgLight = Color(0xFFF3F4F5)
val SurfaceLight = Color(0xFFFBFBFC)
val SurfaceVariantLight = Color(0xFFEFF0F2)
val OnLight = Color(0xFF17181A)
val OnLightVariant = Color(0xFF5C6168)
val OnLightMuted = Color(0xFF9CA1A8)
val OutlineLight = Color(0xFFE0E2E6)

// ── 中性色：深色 ────────────────────────────────────────────
val BgDark = Color(0xFF0F1014)
val SurfaceDark = Color(0xFF17181D)
val SurfaceVariantDark = Color(0xFF22242B)
val OnDark = Color(0xFFF3F4F6)
val OnDarkVariant = Color(0xFFA8B0BC)
val OnDarkMuted = Color(0xFF6E7684)
val OutlineDark = Color(0xFF2C2F36)

// ── 功能性场景色（大图查看器全屏黑底：看图场景例外，不受主题影响）──
val ViewerBackdrop = Color(0xFF000000)
val OnViewer = Color(0xFFEDEEF0)

// ── 语义色（浅色主题：深字 + 浅底，对比度稳过 4.5:1）─────────────
val Success = Color(0xFF15803D)
val SuccessContainer = Color(0xFFE7F6EC)
val OnSuccessContainer = Color(0xFF0B3D1E)

val Warning = Color(0xFFB45309)
val WarningContainer = Color(0xFFFDF1E3)
val OnWarningContainer = Color(0xFF5A2C05)

val Error = Color(0xFFC62828)
val ErrorContainer = Color(0xFFFCEAEA)
val OnErrorContainer = Color(0xFF5C1414)

val Info = Color(0xFF1D4ED8)
val InfoContainer = Color(0xFFE9F0FE)
val OnInfoContainer = Color(0xFF0E2A6B)

// ── 语义色（深色主题：亮字 + 暗底）─────────────────────────────
val SuccessDark = Color(0xFF4ADE80)
val SuccessContainerDark = Color(0xFF14291C)
val OnSuccessContainerDark = Color(0xFFB7F0C9)

val WarningDark = Color(0xFFFBBF24)
val WarningContainerDark = Color(0xFF2E2008)
val OnWarningContainerDark = Color(0xFFFDE3A7)

val ErrorDark = Color(0xFFF87171)
val ErrorContainerDark = Color(0xFF331416)
val OnErrorContainerDark = Color(0xFFFBC9C9)
val OnErrorDark = Color(0xFF4A0F0F)         // 错误容器前景（深色）

val InfoDark = Color(0xFF7DA6FA)
val InfoContainerDark = Color(0xFF131F38)
val OnInfoContainerDark = Color(0xFFCBDDFF)
