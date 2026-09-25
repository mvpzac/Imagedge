package com.imagedge.camera.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 沉浸页骨架（设计 §8.3 末段）：画面可以延伸，控件层自己避开安全区
 *     version: 1.0
 * </pre>
 */

/** 沉浸页的两种排布（设计 §4.6：竖屏画面上快门下，横屏画面左控件右） */
enum class ImmersiveLayout { Stacked, SideBySide }

/**
 * 横屏且宽度真的够两栏才并排。
 *
 * 两条各自挡一种情况：
 * - **宽大于高**（不含正方）：方形窗口通常是分屏或折叠屏展开，并排会把两栏都压窄，
 *   而画面本来就是按横向取景的，方窗口里上下排反而完整；
 * - **600dp 下限**：控件栏至少剩得下一个 72dp 快门加一行按钮。更窄的横屏
 *   （小屏手机、系统分屏）继续上下排——画面只是矮一点，没有变形。
 */
fun immersiveLayoutOf(widthDp: Float, heightDp: Float): ImmersiveLayout =
    if (widthDp > heightDp && widthDp >= MIN_SIDE_BY_SIDE_WIDTH_DP) ImmersiveLayout.SideBySide
    else ImmersiveLayout.Stacked

/** 并排时画面与控件的宽度比：取景是主角，但控件也不能瘦到按不动 */
private const val MEDIA_WEIGHT = 1.6f
private const val CONTROLS_WEIGHT = 1f
private const val MIN_SIDE_BY_SIDE_WIDTH_DP = 600f

/**
 * 遥控这类「画面为主」的页面骨架。
 *
 * 与 [AppScreenFrame] 的分工就一条：普通页把 inset 一次分给顶栏和内容，
 * 因为内容是列表；沉浸页的内容是**画面**，画面本来就该吃掉能吃的空间，
 * inset 是控件层的事。所以这里只有控件列和顶栏消费 safeDrawing，画面不消费。
 *
 * 颜色不在这儿决定：遥控页用正常表面，查看器用深色底，同一个排布两种皮肤。
 *
 * @param header 左上返回与标题；自己不加系统 inset，由这里统一让位。
 *   收到当前排布：横屏时标题下面再压三行状态，画面就没高度了（实测如此），
 *   所以那些行由页面自己决定放标题下还是控件栏里。
 * @param media 画面槽。骨架把**当前排布**告诉它：横屏时槽的高度是限死的，画面若仍按
 *   宽度定 3:2 就会顶出槽外压住状态行（实测如此），所以由画面自己选按宽还是按高
 * @param controls 控件槽。可滚动，滚动条不过画面
 */
@Composable
fun ImmersiveFrame(
    header: @Composable (ImmersiveLayout) -> Unit,
    media: @Composable (ImmersiveLayout) -> Unit,
    modifier: Modifier = Modifier,
    controls: @Composable ColumnScope.(ImmersiveLayout) -> Unit
) {
    val topBars = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val bottomBars = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = immersiveLayoutOf(maxWidth.value, maxHeight.value)
        if (layout == ImmersiveLayout.SideBySide) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.windowInsetsPadding(topBars)) { header(layout) }
                Row(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .weight(MEDIA_WEIGHT)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) { media(layout) }
                    ControlsColumn(
                        modifier = Modifier
                            .weight(CONTROLS_WEIGHT)
                            .fillMaxHeight(),
                        insets = bottomBars,
                        layout = layout,
                        content = controls
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(topBars)
                ) { header(layout) }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) { media(layout) }
                ControlsColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    insets = bottomBars,
                    layout = layout,
                    content = controls
                )
            }
        }
    }
}

/** 控件列：滚动 + 安全区 + 页级内边距，两个排布共用同一份 */
@Composable
private fun ControlsColumn(
    modifier: Modifier,
    insets: WindowInsets,
    layout: ImmersiveLayout,
    content: @Composable ColumnScope.(ImmersiveLayout) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(insets)
            .padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.L),
        content = { content(layout) }
    )
}
