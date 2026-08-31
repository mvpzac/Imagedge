package com.imagedge.camera.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 设计稿等比缩放（UI 锁定方案）。
 *
 * 以主力测试机为设计基准宽 [DESIGN_WIDTH_DP]，在宿主 [Density] 上按
 * 「宿主屏宽 / 基准宽」等比缩放 density —— 所有 dp 尺寸、间距、字号（sp 亦随
 * density 缩放）与约束布局整体等比，UI 在任何屏幕宽度上都保持设计稿的
 * 位置与结构，不因机型不同而换行、错位或改变比例。
 *
 * 防护：
 * - 缩放系数钳制在 [MIN_SCALE]~[MAX_SCALE]，超小/超宽设备（含平板、分屏）
 *   不会出现极端过小或过大的文字与控件
 * - 系统字体缩放（fontScale）保持叠加，无障碍设置仍然生效
 *
 * 约定：页面内不要直接用 LocalConfiguration 的 dp 值参与布局
 * （该值未随缩放变化），需要比例尺寸时用 BoxWithConstraints 的 max*。
 */
const val DESIGN_WIDTH_DP = 394f

/** 缩放系数下限：约 315dp 超窄屏（如分屏半宽） */
private const val MIN_SCALE = 0.8f

/** 缩放系数上限：约 532dp（大屏手机/小平板；更大平板按上限等比，不铺满变形） */
private const val MAX_SCALE = 1.35f

@Composable
fun DesignScaleLocked(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val current = LocalDensity.current
    val scale = (config.screenWidthDp / DESIGN_WIDTH_DP).coerceIn(MIN_SCALE, MAX_SCALE)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = current.density * scale,
            fontScale = current.fontScale
        ),
        content = content
    )
}
