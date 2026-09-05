package com.imagedge.camera.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.ui.theme.PillShape

/**
 * 玻璃参数（集中在此，方便真机调优）。
 *
 * 目标观感接近 iOS 26 Liquid Glass：**通透 + 强边缘折射**。
 * 通透靠低模糊 + 低表面色实现；「玻璃感」主要来自 lens 在边缘的
 * 放大式折射与色差——而不是把背景糊掉（那只是磨砂，不是玻璃）。
 */
object GlassSpec {
    /**
     * 背景模糊半径。
     * 注意不要过大：iOS 玻璃的模糊偏轻（保留背后内容的剪影），
     * 真正让它区别于「半透明板」的是边缘折射而非模糊。
     */
    val BlurRadius = 14.dp

    /** 折射带高度（从边缘向内多宽的区域发生折射） */
    val RefractionHeight = 24.dp

    /** 折射强度：正值让边缘把背后画面向中心放大，是「玻璃凸透镜」感的关键 */
    val RefractionAmount = 46.dp

    /**
     * 玻璃表面色不透明度。
     * 越低越通透（能看到背后画面），iOS 观感约 0.1–0.25。
     */
    const val SurfaceAlpha = 0.2f

    /** 背后内容饱和度增益（vibrancy），提升玻璃下画面的鲜活度 */
    const val Vibrancy = true

    /** 玻璃深度效果：边缘出现白亮的内发光（近真实玻璃的厚度感） */
    const val DepthEffect = true

    /** 色差：边缘折射带出轻微 RGB 分离（玻璃的最标志性细节） */
    const val ChromaticAberration = true
}

/**
 * 玻璃表面 —— 一站式视觉改造的核心修饰符。
 *
 * 三段式降级：
 * - [GlassLevel.FULL]：模糊 + 边缘折射（真正的液态玻璃）
 * - [GlassLevel.BLUR_ONLY]：只有背景模糊，无折射
 * - [GlassLevel.NONE]：**退回普通半透明表面**，观感与改造前完全一致
 *
 * 最后一条是「不破坏现有功能」的兜底：任何不支持/被禁用的场景，
 * 用户看到的仍是原来的界面，不会变成一块莫名其妙的半透明板。
 *
 * @param backdrop 背景源（页面内容通过 `Modifier.layerBackdrop()` 提供）；传 null 直接降级
 * @param surfaceColor 玻璃表面色调，默认取当前主题的表面色
 */
@Composable
fun Modifier.glassSurface(
    backdrop: LayerBackdrop?,
    level: GlassLevel,
    shape: Shape = RoundedCornerShape(Radius.Card),
    blurRadius: Dp = GlassSpec.BlurRadius,
    refractionHeight: Dp = GlassSpec.RefractionHeight,
    refractionAmount: Dp = GlassSpec.RefractionAmount,
    surfaceColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
): Modifier {
    if (level == GlassLevel.NONE || backdrop == null) {
        // 降级：与改造前一致的不透明表面
        return this.background(color = surfaceColor, shape = shape)
    }

    val tint = surfaceColor.copy(alpha = GlassSpec.SurfaceAlpha)
    val specular = surfaceColor.luminance() < 0.5f
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(blurRadius.toPx())
            if (GlassSpec.Vibrancy) vibrancy()
            // lens 需要 AGSL（API 33+），且只支持圆角形状——
            // 低版本由分级挡在门外，这里无需再判断版本
            if (level == GlassLevel.FULL) {
                lens(
                    refractionHeight.toPx(),
                    refractionAmount.toPx(),
                    depthEffect = GlassSpec.DepthEffect,
                    chromaticAberration = GlassSpec.ChromaticAberration
                )
            }
        },
        onDrawSurface = {
            // 表面色越淡越通透
            drawRect(tint)
        }
    ).then(
        // iOS 玻璃的边缘细描边：沿形状走线（比在 draw 内部画矩形描边更贴合圆角）
        if (level == GlassLevel.FULL) {
            Modifier.border(
                width = androidx.compose.ui.unit.Dp.Hairline,
                color = if (specular) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.14f),
                shape = shape
            )
        } else {
            Modifier
        }
    )
}

/**
 * 玻璃弹窗（AlertDialog / Dialog）的容器色。
 *
 * 启用玻璃时返回透明——让弹窗自身的容器让位，露出底下的玻璃层；
 * 否则沿用 Material3 默认容器色，与改造前一致。
 */
@Composable
fun glassDialogContainerColor(default: Color = androidx.compose.material3.AlertDialogDefaults.containerColor): Color {
    val backdrop = LocalGlassBackdrop.current
    val level = rememberGlassLevel()
    return if (backdrop != null && level.warrantsBackdropCapture()) Color.Transparent else default
}

/**
 * 玻璃弹窗的玻璃底层，配合 [glassDialogContainerColor] 使用。
 *
 * 弹窗位于 Popup 之中，不会被页面背景层采集，因此引用背景层是安全的（无递归）。
 */
@Composable
fun Modifier.glassDialog(
    shape: Shape = RoundedCornerShape(Radius.Container),
    surfaceColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
): Modifier = glassSurface(
    backdrop = LocalGlassBackdrop.current,
    level = rememberGlassLevel(),
    shape = shape,
    surfaceColor = surfaceColor
)

/**
 * 胶囊形玻璃（底部导航、Chip、状态标签用）。
 * 胶囊形状是 `RoundedCornerShape(50%)`，属于 lens 支持的形状。
 */
@Composable
fun Modifier.glassPill(
    backdrop: LayerBackdrop?,
    level: GlassLevel,
    surfaceColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
): Modifier = glassSurface(
    backdrop = backdrop,
    level = level,
    shape = com.imagedge.camera.ui.theme.PillShape,
    surfaceColor = surfaceColor
)
