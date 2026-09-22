package com.imagedge.camera.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
 * - 只放大、不缩小：窄屏由 Compose 约束自适应，保证 48dp 触控目标不会被
 *   全局 Density 缩成 38.4dp，持续满足可访问性的最小触控尺寸
 * - 系统字体缩放（fontScale）保持叠加，无障碍设置仍然生效
 *
 * 约定：页面内不要直接用 LocalConfiguration 的 dp 值参与布局
 * （该值未随缩放变化），需要比例尺寸时用 BoxWithConstraints 的 max*。
 */
const val DESIGN_WIDTH_DP = 394f

/** 缩放系数下限：不允许全局缩小触控目标与文字。 */
private const val MIN_SCALE = 1f

/** 缩放系数上限：约 532dp（大屏手机/小平板；更大平板按上限等比，不铺满变形） */
private const val MAX_SCALE = 1.35f

@Composable
fun DesignScaleLocked(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    // Configuration.screenWidthDp 的 insets 行为随 targetSdk 变化且有 dp 取整误差；
    // 优先取窗口实际容器尺寸换算，异常（首帧可能为 0）时回退
    val containerWidthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = if (containerWidthPx > 0) with(current) { containerWidthPx.toDp().value }
    else DESIGN_WIDTH_DP
    val scale = (widthDp / DESIGN_WIDTH_DP).coerceIn(MIN_SCALE, MAX_SCALE)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = current.density * scale,
            fontScale = current.fontScale
        ),
        content = content
    )
}
