package com.imagedge.camera.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
 * 取值偏保守：Imagedge 是极简黑白风格，玻璃应当像一层薄雾——
 * 能看出背后内容的轮廓与折射，而不是把背景糊成一片色块。
 */
object GlassSpec {
    /** 背景模糊半径：够柔但不至于完全看不清背后 */
    val BlurRadius = 18.dp

    /** 折射带高度（边缘到内部多宽的区域发生折射） */
    val RefractionHeight = 14.dp

    /** 折射强度（越大边缘扭曲越明显） */
    val RefractionAmount = 26.dp

    /**
     * 玻璃表面色（叠在模糊结果之上）。
     * 越淡越通透：0 是纯玻璃，1 是不透明色块。
     */
    const val SurfaceAlpha = 0.42f

    /**
     * 是否启用 vibrancy（把背后内容饱和度 ×1.5，令玻璃下的画面更鲜活）。
     *
     * 这是 backdrop 2.0 相对 1.0.x 新增的效果，也是本次升级的主要收益点。
     * 默认**关闭**：Imagedge 是极简黑白风格，增艳会让照片在中性界面里显得跳脱。
     * 若你想要更「满」的玻璃观感（例如玻璃浮在彩色照片上时），可将其打开。
     */
    const val Vibrancy = false
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
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(blurRadius.toPx())
            if (GlassSpec.Vibrancy) vibrancy()
            // lens 需要 AGSL（API 33+），且只支持圆角形状——
            // 低版本由分级挡在门外，这里无需再判断版本
            if (level == GlassLevel.FULL) {
                lens(refractionHeight.toPx(), refractionAmount.toPx())
            }
        },
        onDrawSurface = {
            drawRect(tint)
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
