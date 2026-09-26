package com.imagedge.camera.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.glass.GlassLevel
import com.imagedge.camera.ui.glass.glassPill
import com.imagedge.camera.ui.glass.glassReactive
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.imagedge.camera.ui.theme.Motion
import com.imagedge.camera.ui.theme.orSnap
import com.imagedge.camera.ui.theme.PillShape
import com.imagedge.camera.ui.theme.UiSize
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 底部四入口导航（图标 + 文字标签，磁吸滑条）
 *     version: 2.0
 * </pre>
 */

/**
 * 导航胶囊阴影（中性黑，黑白主题通用）
 * 注：Color(Long) 需完整 ARGB —— 24 位写法 alpha=0 会让阴影完全透明
 */
private val NavShadowAmbient = Color(0x1A1A1A1E)
private val NavShadowSpot = Color(0x331A1A1E)

/** 标签字号下限。系统字体放大时由 Typography 缩放带动，不锁死行高 */
private val TabLabelSize = 12.sp

/**
 * 悬浮玻璃导航栏。
 *
 * 相比上一版的两处实质变化：
 * 1. **图标下配文字标签**：只有图标时「三个横条」是不是编辑、「星形」是不是创作要用户猜，
 *    识别成本被转嫁给用户（UI 规范 §8）；
 * 2. **高度只设下限**（[UiSize.NavigationMin]）：固定 56dp 在 200% 字体下会把标签裁成半行。
 *
 * 玻璃只有一层：胶囊容器折射页面内容，选中指示条用半透明填充，
 * **不**给指示条单独 drawBackdrop（移动时会多跑一遍离屏渲染）。
 *
 * [onClearanceChanged] 上报胶囊实高换算出的底部留白（见 [LocalNavClearance]）：
 * 胶囊浮在内容之上，页面不预留高度就会被最后一行撞上导航。
 *
 * 指示条用 `matchParentSize` 是安全的，前提与 GlassCard 的坑正好相反：
 * 这里同级的 Row 才是撑出高度的那一个，指示条只是跟随。
 */
@Composable
fun FloatingNavBar(
    backdrop: LayerBackdrop?,
    glassLevel: GlassLevel,
    selected: TabDestination?,
    onSelect: (TabDestination) -> Unit,
    onClearanceChanged: (Dp) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val destinations = TabDestination.entries
    val useGlass = glassLevel.warrantsBackdropCapture() && backdrop != null
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = NavBottomMargin)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (useGlass) {
                        Modifier
                    } else {
                        Modifier.shadow(
                            elevation = 12.dp,
                            shape = PillShape,
                            ambientColor = NavShadowAmbient,
                            spotColor = NavShadowSpot
                        )
                    }
                )
        ) {
            // 系统要求移除动画时，指示条与磁吸都直接到位（Compose 不自己读那个开关）
            val reducedMotion = Motion.animationsDisabled(LocalContext.current)
            val itemWidth = maxWidth / destinations.size
            val selectedIndex = destinations.indexOfFirst { it == selected }.coerceAtLeast(0)

            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = Motion.springSoftDp.orSnap(reducedMotion),
                label = "navIndicator"
            )

            // 磁吸：切换瞬间朝来向拉入 5dp 再弹回，模拟「被吸住」的触感。
            // 「上一次选中的是谁」是跨条目的全局事实，记在这里；各条目自己记的话，
            // 记到的是「我自己上次被选中的时刻」，A→C→A 会朝错误方向弹。
            val magneticKick = remember { Animatable(0.dp, Dp.VectorConverter) }
            val previousIndex = remember { mutableIntStateOf(-1) }
            LaunchedEffect(selectedIndex) {
                val from = previousIndex.intValue
                previousIndex.intValue = selectedIndex
                if (selected != null && from != -1 && from != selectedIndex) {
                    val direction = if (selectedIndex > from) 1 else -1
                    magneticKick.snapTo((direction * 5).dp)
                    magneticKick.animateTo(0.dp, Motion.springSnappyDp.orSnap(reducedMotion))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 量胶囊实高回传，页面据此留底部空白（见 [LocalNavClearance]）
                    .onSizeChanged { size ->
                        val capsuleHeight = with(density) { size.height.toDp() }
                        onClearanceChanged(navClearanceFor(capsuleHeight))
                    }
                    .glassPill(
                        backdrop = backdrop,
                        level = glassLevel,
                        surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = UiSize.NavigationMin),
                    // 条目**不能**用 fillMaxHeight：外层 Box 传下来的是松约束
                    // （maxHeight = 整屏），那会把 Row 直接顶成满屏高，
                    // 胶囊跟着变成一整屏，图标和文字被居中到屏幕中间。
                    // 垂直居中交给 Row 自己的 verticalAlignment。
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    destinations.forEach { destination ->
                        NavItem(
                            destination = destination,
                            isSelected = destination == selected,
                            onSelect = onSelect,
                            shift = if (destination == selected) magneticKick.value else 0.dp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // 指示条画在内容之上：半透明主色填充，与图标共用同一层玻璃底
                val indicatorWidth = itemWidth / 2
                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (indicatorOffset + (itemWidth - indicatorWidth) / 2).roundToPx(),
                                    0
                                )
                            }
                            .padding(vertical = 8.dp)
                            .fillMaxHeight()
                            .width(indicatorWidth)
                            .clip(PillShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                PillShape
                            )
                            .border(
                                Dp.Hairline,
                                Color.White.copy(alpha = if (useGlass) 0.24f else 0.12f),
                                PillShape
                            )
                    )
                }
            }
        }
    }
}

/**
 * 单个导航项：图标 + 文字标签。
 *
 * 语义走 [Role.Tab] 并带 `selected`，读屏才知道这是标签页切换、当前在哪一页；
 * 图标本身承载 contentDescription，标签文字重复同一句——两处不同名会比缺一角更糟。
 *
 * [shift] 由父级统一驱动（同一时刻只有一个条目在被吸动），本组件不持有动画状态。
 */
@Composable
private fun NavItem(
    destination: TabDestination,
    isSelected: Boolean,
    onSelect: (TabDestination) -> Unit,
    shift: Dp,
    modifier: Modifier = Modifier
) {
    val label = stringResource(destination.labelRes)

    Column(
        modifier = modifier
            .semantics {
                role = Role.Tab
                selected = isSelected
            }
            .glassReactive(onClick = { onSelect(destination) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LucideIcon(
            lucide = destination.lucide,
            contentDescription = label,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            size = 22.dp,
            modifier = Modifier.offset { IntOffset(shift.roundToPx(), 0) }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = TabLabelSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
