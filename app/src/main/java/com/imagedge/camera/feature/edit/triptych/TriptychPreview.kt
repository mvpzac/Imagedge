package com.imagedge.camera.feature.edit.triptych

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 三拼常驻预览——静止的三格封面拼图，按成品画布比例装进高度预算
 * </pre>
 */

/**
 * 三拼页面上方的**静止**预览区：`state.previewBitmap` 就是成品画布
 * （VM 的 `buildTriptychBitmap`，预览与导出同源），本组件只负责按
 * [triptychCanvasSize] 给出的成品比例把它**完整**显示出来，不另算第二份画布算术。
 *
 * **长按顺序播放三段（规格 §4.1）没有做，这一版只有静止图。** 那需要 ExoPlayer
 * 播放头叠加到当前格的偏移、以及按对齐的垂直位移——都是只有真机才看得出来对不对
 * 的东西，而本轮无可运行设备（`adb devices` 为空，`:app` 没有 Compose UI 测试设施）。
 * 一个可能显示错格、或转场与所见不符的播放头，比没有播放头更坏：它会让人以为
 * 「预览验过了」。所以这里不写任何未验证的播放承诺，功能留到能上机核对的那一轮再做。
 *
 * @param bitmap 拼图；`null` = 尚未建好或刚被参数改动作废。
 * @param loading 是否在重建（`UiState.previewLoading`）。`bitmap == null && !loading`
 *   时这一带什么都不画——没有东西可画且不撒谎。
 * @param canvas 成品画布像素（一格宽 × 总格高），来自 [triptychCanvasSize]。
 */
@Composable
fun TriptychPreview(
    bitmap: Bitmap?,
    loading: Boolean,
    canvas: CellSize,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            // 先给高度预算、再按画布比例定宽。`aspectRatio` 的默认行为是
            // 「先按满宽算高，高超过 maxHeight 时改为贴合 maxHeight」——
            // 于是竖排画布在窄屏上满宽、宽屏上被高度预算收住，两个约束谁也不裁谁。
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "三拼预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = PREVIEW_MAX_HEIGHT)
                    .aspectRatio(canvas.width.toFloat() / canvas.height)
                    .clip(RoundedCornerShape(Radius.Card)),
            )
        } else if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * 预览的高度预算——**本项目的取值，不是规范 token**。
 *
 * 为什么需要上限：画布是竖排的（16:9 档三格堆成 16:27）。以 328dp 内容宽算，
 * 满宽显示 1920×3240 的画布要 328 × 3240 ÷ 1920 ≈ 554dp 高——参数区整块被推到
 * 折叠线以下，「改一个参数看一眼预览」就变成「改一个参数滚一次屏」。
 * 定 320dp，让三格始终**等比完整**可见（不裁切），参数区在典型竖屏上同屏可达；
 * 这个数是观感取舍，接上真机后应复核。
 */
private val PREVIEW_MAX_HEIGHT = 320.dp
