package com.imagedge.camera.feature.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.feedback.SnackbarController
import com.imagedge.camera.ui.glass.GlassBackdropLayer
import com.imagedge.camera.ui.glass.GlassLevel
import com.imagedge.camera.ui.glass.glassReactive
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.glassPill
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.imagedge.camera.ui.theme.Motion
import com.imagedge.camera.ui.theme.PillShape
import com.imagedge.camera.ui.theme.Radius
import com.imagedge.camera.feature.album.AlbumHubScreen
import com.imagedge.camera.feature.album.AlbumScreen
import com.imagedge.camera.feature.album.BrowseMode
import com.imagedge.camera.feature.edit.EditHubScreen
import com.imagedge.camera.feature.edit.LutEditScreen
import com.imagedge.camera.feature.edit.VideoToLivePhotoScreen
import com.imagedge.camera.feature.edit.LiveTriptychScreen
import com.imagedge.camera.feature.edit.ExifFrameScreen
import com.imagedge.camera.feature.album.PhotoViewerScreen
import com.imagedge.camera.feature.download.DownloadScreen
import com.imagedge.camera.feature.control.RemoteShootingScreen
import com.imagedge.camera.feature.home.HomeScreen
import com.imagedge.camera.feature.settings.PermissionScreen
import com.imagedge.camera.feature.settings.SettingsScreen

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/27
 *     desc   : 根导航（底部 3-Tab：主页 / 相册 / 设置，交互规格 S3；
 *              悬浮磁吸胶囊导航：脱离底边 + 弹簧滑条过冲回弹，参数见 Motion token）
 *     version: 1.1
 * </pre>
 */

/**
 * 导航胶囊阴影（中性黑，黑白主题通用）
 * 注：Color(Long) 需完整 ARGB —— 24 位写法 alpha=0 会让阴影完全透明
 */
private val NavShadowAmbient = Color(0x1A1A1A1E)
private val NavShadowSpot = Color(0x331A1A1E)

/** 底部导航目的地定义 */
private enum class RootDestination(
    val route: String,
    val labelRes: Int,
    val lucide: Int
) {
    HOME("home", R.string.tab_home, Lucide.Home),
    ALBUM("album", R.string.tab_album, Lucide.Images),
    SETTINGS("settings", R.string.tab_settings, Lucide.Settings)
}

/** 非 Tab 子路由 */
object Route {
    const val DOWNLOAD = "download"
    const val REMOTE = "remote"

    const val ALBUM_SELECTION = "album_selection"
    const val ALBUM_FULL_CARD = "album_full_card"
    const val EDIT_HUB = "edit_hub"
    const val LUT_EDIT = "lut_edit"
    const val LIVE_PHOTO = "live_photo"
    const val LIVE_TRIPTYCH = "live_triptych"
    const val EXIF_FRAME = "exif_frame"
    const val PERMISSIONS = "permissions"

    /** 大图查看器（index = 相册列表起始位置） */
    const val PHOTO_VIEWER = "photo_viewer/{index}"

    fun photoViewer(index: Int) = "photo_viewer/$index"
}

/**
 * 根屏幕：悬浮磁吸导航 + NavHost
 */
@Composable
fun RootScreen(
    snackbarController: SnackbarController
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 全局轻提示：任意页面通过 SnackbarController.show() 发消息，
    // 这里以「顶部滑入弹窗」统一呈现，2 秒后自动从顶部滑回
    var bannerMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackbarController) {
        snackbarController.messages.collect { bannerMessage = it }
    }
    LaunchedEffect(bannerMessage) {
        if (bannerMessage != null) {
            kotlinx.coroutines.delay(2500)
            bannerMessage = null
        }
    }

    // 二级页面（相册子分区/查看器/遥控等）不显示底部导航，保持层级清晰
    val showBottomBar = currentDestination?.route in RootDestination.entries.map { it.route }

    // 液态玻璃（双背景源，各管一层，互不包含故无递归）：
    // - pageBackdrop：采集「页面背景层」（光晕），供页面内卡片/按钮/开关折射
    // - navBackdrop：采集「NavHost 页面内容」，供悬浮导航栏折射 —— 滚动时内容从
    //   导航栏底下滑过并透出，才是真正的悬浮玻璃（而不是折射一张静态背景）
    // 仅在玻璃可用时才做图层采集——离屏渲染有固定开销，不支持的设备不该白付。
    val glassLevel = rememberGlassLevel()
    val pageBackdrop = rememberLayerBackdrop()
    val navBackdrop = rememberLayerBackdrop()
    val captureBackdrop = glassLevel.warrantsBackdropCapture()

    Box(modifier = Modifier.fillMaxSize()) {
        // 页面背景层（光晕）：采集给 pageBackdrop，供页面内玻璃控件折射
        GlassBackdropLayer(backdrop = if (captureBackdrop) pageBackdrop else null)

        // 内容区填满全屏（含状态栏高度，但不预留导航栏高度）—— 这样滚动时
        // 列表项才能真正从底部导航栏底下滑过，被悬浮玻璃折射出来。
        // 之前放在 Scaffold 的 bottomBar 里会让 Scaffold 自动让出导航栏高度，
        // 内容就被顶在导航栏上方，"文字不出现于导航栏下方"。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (captureBackdrop) Color.Transparent
                    else MaterialTheme.colorScheme.background
                )
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
        ) {
            // 页面内玻璃控件引用 pageBackdrop（背景层，不含任何玻璃控件，无递归）
            androidx.compose.runtime.CompositionLocalProvider(
                LocalGlassBackdrop provides if (captureBackdrop) pageBackdrop else null
            ) {
            NavHost(
                navController = navController,
                startDestination = RootDestination.HOME.route,
                modifier = Modifier
                    .fillMaxSize()
                    // 采集页面内容，供悬浮导航栏折射（NavHost 不含导航栏自身）
                    .then(
                        if (captureBackdrop) {
                            Modifier.layerBackdrop(navBackdrop)
                        } else {
                            Modifier
                        }
                    )
            ) {
                composable(RootDestination.HOME.route) {
                    HomeScreen(
                        onOpenRemote = { navController.navigate(Route.REMOTE) },
                        snackbarController = snackbarController
                    )
                }
                // 相册 TAB = 中枢（不自动加载媒体），选片集/整卡拆为两个入口，子分区按需进入
                composable(RootDestination.ALBUM.route) {
                    AlbumHubScreen(
                        onOpenSelection = { navController.navigate(Route.ALBUM_SELECTION) },
                        onOpenFullCard = { navController.navigate(Route.ALBUM_FULL_CARD) },
                        onOpenTransfer = { navController.navigate(Route.DOWNLOAD) },
                        onOpenEdit = { navController.navigate(Route.EDIT_HUB) },
                        snackbarController = snackbarController
                    )
                }
                composable(Route.ALBUM_SELECTION) {
                    AlbumScreen(
                        browseMode = BrowseMode.SELECTION,
                        onOpenDownloads = { navController.navigate(Route.DOWNLOAD) },
                        onOpenViewer = { index -> navController.navigate(Route.photoViewer(index)) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.ALBUM_FULL_CARD) {
                    AlbumScreen(
                        browseMode = BrowseMode.FULL_CARD,
                        onOpenDownloads = { navController.navigate(Route.DOWNLOAD) },
                        onOpenViewer = { index -> navController.navigate(Route.photoViewer(index)) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Route.EDIT_HUB) {
                    EditHubScreen(
                        onBack = { navController.popBackStack() },
                        onOpenLivePhoto = { navController.navigate(Route.LIVE_PHOTO) },
                        onOpenLut = { navController.navigate(Route.LUT_EDIT) },
                        onOpenTriptych = { navController.navigate(Route.LIVE_TRIPTYCH) },
                        onOpenExifFrame = { navController.navigate(Route.EXIF_FRAME) }
                    )
                }
                composable(Route.LUT_EDIT) {
                    LutEditScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.LIVE_PHOTO) {
                    VideoToLivePhotoScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.LIVE_TRIPTYCH) {
                    LiveTriptychScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.EXIF_FRAME) {
                    ExifFrameScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.PHOTO_VIEWER) {
                    PhotoViewerScreen(onBack = { navController.popBackStack() })
                }
                composable(RootDestination.SETTINGS.route) {
                    SettingsScreen(
                        onOpenPermissions = { navController.navigate(Route.PERMISSIONS) }
                    )
                }
                composable(Route.PERMISSIONS) {
                    PermissionScreen(onBack = { navController.popBackStack() })
                }
                composable(Route.REMOTE) {
                    RemoteShootingScreen(
                        onBack = { navController.popBackStack() },
                        snackbarController = snackbarController
                    )
                }
                composable(Route.DOWNLOAD) {
                    DownloadScreen(
                        onBack = { navController.popBackStack() },
                        onGoAlbum = {
                            // 空队列「去相册选片」：切到相册 Tab（与底部导航同款切换语义）
                            navController.navigate(RootDestination.ALBUM.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
            }
        }
        // 悬浮玻璃导航栏：叠在外层 Box 底部（**不**放进 Scaffold bottomBar），
        // 这样页面内容才能真正延伸到导航栏下方——滚动时列表项从底下穿过、被玻璃折射。
        if (showBottomBar) {
            FloatingNavBar(
                backdrop = if (captureBackdrop) navBackdrop else null,
                glassLevel = glassLevel,
                selectedRoute = currentDestination?.route,
                onSelect = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
        // 顶部弹窗：从屏幕顶部滑入，2 秒后滑回
        TopBanner(
            message = bannerMessage,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/** 顶部滑入弹窗（替代底部 Snackbar）：圆角卡片 + 从顶部滑入/滑出 */
@Composable
private fun TopBanner(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        message?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(Radius.Container),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * 悬浮磁吸导航（方案 C）：
 * - 胶囊容器脱离屏幕底部（左右 16dp、底部悬空 12dp），阴影色见文件顶部常量（NavShadow*）
 * - 主色药丸指示条：弹簧在 tab 间滑动，到位过冲回弹（参数见 Motion token）—— 磁吸感
 * - 选中图标：上浮 + 变主色；切换瞬间朝来向轻微偏移再弹回（Animatable snapTo→animateTo），
 *   模拟「被吸住」的触感
 * - 图标承载语义（role=Tab + contentDescription），无标签文字（胶囊高度留给图标）
 */
@Composable
private fun FloatingNavBar(
    backdrop: LayerBackdrop?,
    glassLevel: GlassLevel,
    selectedRoute: String?,
    onSelect: (RootDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val destinations = RootDestination.entries
    // 玻璃自带投影，仅在降级（普通表面）时才需要外层阴影，避免双重投影
    val useGlass = glassLevel.warrantsBackdropCapture() && backdrop != null
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 22.dp)
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
            val itemWidth = maxWidth / destinations.size
            val selectedIndex = destinations
                .indexOfFirst { it.route == selectedRoute }
                .coerceAtLeast(0)

            // 磁吸滑条：弹簧产生过冲回弹（参数见 Motion token，两档弹簧统一）
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = Motion.springSoftDp,
                label = "navIndicator"
            )

            // 胶囊容器：玻璃表面（降级时自动退回与普通 Surface 一致的观感）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPill(
                        backdrop = backdrop,
                        level = glassLevel,
                        surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 指示条：与图标等宽的竖长药丸，随索引磁吸滑动
                    val indicatorWidth = itemWidth / 2
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((indicatorOffset + (itemWidth - indicatorWidth) / 2).roundToPx(), 0) }
                            .padding(vertical = 11.dp)
                            .height(44.dp)
                            .width(indicatorWidth)
                            // 磁吸指示器也改成玻璃：与主胶囊同样折射页面背景，
                            // 但用主色着色，于是呈现为「被点亮的一小块玻璃」。
                            // 它引用的是页面背景层（不含导航栏本体），不会产生自引用递归。
                            .glassPill(
                                backdrop = backdrop,
                                level = glassLevel,
                                surfaceColor = MaterialTheme.colorScheme.primary
                            )
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        destinations.forEachIndexed { index, destination ->
                            val selected = index == selectedIndex
                            NavBarIcon(
                                destination = destination,
                                selected = selected,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .glassReactive(
                                        onClick = { onSelect(destination) }
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个导航图标：选中时上浮 3dp + 主色；选中瞬间朝来向偏移 5dp 后弹回。
 * 图标下移 4dp（全局补偿已归零）—— 实测仅胶囊导航内图标先天偏高，页面图标居中即可。
 */
private val NavIconExtraShiftY = 4.dp
@Composable
private fun NavBarIcon(
    destination: RootDestination,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    // 磁吸偏移：选中瞬间从来向拉入（横向回弹，营造「被吸住」的触感）。
    // 注意：选中状态**不**做垂直上浮——指示条与主色已足够表达选中，
    // 图标上浮会让胶囊内视觉重心不稳（用户定稿去掉）。
    val shiftAnim = remember { Animatable(0.dp, Dp.VectorConverter) }
    val lastIndex = remember { mutableIntStateOf(-1) }
    val myIndex = RootDestination.entries.indexOf(destination)
    LaunchedEffect(selected) {
        if (selected && lastIndex.intValue != -1 && lastIndex.intValue != myIndex) {
            val direction = if (myIndex > lastIndex.intValue) 1 else -1
            shiftAnim.snapTo((direction * 5).dp)
            shiftAnim.animateTo(
                0.dp,
                Motion.springSnappyDp
            )
        }
        if (selected) lastIndex.intValue = myIndex
    }

    Box(
        modifier = modifier.semantics {
            role = Role.Tab
        },
        contentAlignment = Alignment.Center
    ) {
        LucideIcon(
            lucide = destination.lucide,
            contentDescription = stringResource(destination.labelRes),
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            size = 26.dp,
            modifier = Modifier.offset { IntOffset(shiftAnim.value.roundToPx(), NavIconExtraShiftY.roundToPx()) }
        )
    }
}
