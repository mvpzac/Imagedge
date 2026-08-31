package com.imagedge.camera.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/29
 *     desc   : 色彩 Token v4.0（Material 3 Expressive + UI 设计规范）
 *              三条硬规矩：
 *              1. 主色只有 1 个（紫罗兰 #5B50E6），强调色每屏 ≤1 处
 *              2. 不用纯黑 #000000 / 纯白 #FFFFFF
 *              3. 语义色只表状态，且状态必须「图标 + 文字」双保险，不单独靠颜色
 *     version: 4.0
 * </pre>
 */

// ── 品牌主色（唯一）────────────────────────────────────────────
val BrandViolet = Color(0xFF5B50E6)       // 主色（浅色主题）
val BrandVioletDark = Color(0xFFB0A6FF)   // 深色主题下的主色（提亮保证 4.5:1 对比度）

// ── 中性色：刻意避开 #000000 与 #FFFFFF ─────────────────────────
// 浅色（背景偏暖白，比纯白柔和）
val BgLight = Color(0xFFFAF9F7)
val SurfaceLight = Color(0xFFFFFEFD)
val SurfaceVariantLight = Color(0xFFF2F0EE)
val OnLight = Color(0xFF1A1A1E)           // 主文字（近黑）
val OnLightVariant = Color(0xFF5F6670)    // 次文字
val OnLightMuted = Color(0xFF9AA1AB)      // 弱文字
val OutlineLight = Color(0xFFDCDFE4)

// 深色
val BgDark = Color(0xFF0F1014)
val SurfaceDark = Color(0xFF17181D)
val SurfaceVariantDark = Color(0xFF22242B)
val OnDark = Color(0xFFF3F4F6)            // 主文字（近白）
val OnDarkVariant = Color(0xFFA8B0BC)     // 次文字
val OnDarkMuted = Color(0xFF6E7684)       // 弱文字
val OutlineDark = Color(0xFF2C2F36)

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

val InfoDark = Color(0xFF7DA6FA)
val InfoContainerDark = Color(0xFF131F38)
val OnInfoContainerDark = Color(0xFFCBDDFF)

// ── 兼容旧引用（历史代码仍在使用，逐步替换后可删）────────────────
val MonoBgDark = BgDark
val MonoSurfaceDark = SurfaceDark
val MonoSurfaceDark2 = SurfaceVariantDark
val MonoBorderDark = OutlineDark
val MonoOnDark = OnDark
val MonoOnDarkVariant = OnDarkVariant
val MonoOnDarkMuted = OnDarkMuted
val MonoBgLight = BgLight
val MonoSurfaceLight = SurfaceLight
val MonoSurfaceLight2 = SurfaceVariantLight
val MonoBorderLight = OutlineLight
val MonoOnLight = OnLight
val MonoOnLightVariant = OnLightVariant
val MonoOnLightMuted = OnLightMuted
val MonoError = Error
val MonoAccent = BrandViolet
val AccentAmber = BrandViolet
val AccentAmberDark = BrandViolet
val OnAccentAmber = SurfaceLight
val AccentBlue = Info

/**
 * 内置品牌色板（设置页可选）。
 * 每档提供浅色/深色两套主色——深色档为提亮变体，保证深底 4.5:1 对比度。
 * 紫罗兰为出厂默认；动态取色开启时优先生效（见 Theme.kt）。
 */
enum class BrandColor(val label: String, val lightPrimary: Color, val darkPrimary: Color) {
    ROSE("玫红", Color(0xFFCE1B77), Color(0xFFF48CC0)),
    VIOLET("紫罗兰", BrandViolet, BrandVioletDark),
    OCEAN("海洋蓝", Color(0xFF1976D2), Color(0xFFA3C9FA)),
    FOREST("森林绿", Color(0xFF2E7D32), Color(0xFFA5D6A7)),
    SUNSET("活力橙", Color(0xFFE65100), Color(0xFFFFB77D)),
    TEAL("青碧", Color(0xFF00897B), Color(0xFF8CD5CB)),
}
