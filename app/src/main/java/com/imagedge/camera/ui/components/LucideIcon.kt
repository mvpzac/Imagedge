package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R

/**
 * Lucide 图标（来源：项目「图标」文件夹 Lucide 集，已批量转为 VectorDrawable，
 * 描边 2dp/24 视口，圆帽圆角，与官方 SVG 一致；白色描边 + Icon tint 着色）。
 *
 * 用法：`LucideIcon(Lucide.Home, contentDescription = "主页", size = 24.dp)`
 */
object Lucide {
    val Home = R.drawable.ic_lucide_home
    val Images = R.drawable.ic_lucide_images
    val Image = R.drawable.ic_lucide_images
    val Settings = R.drawable.ic_lucide_settings
    val ArrowLeft = R.drawable.ic_lucide_arrow_left
    val ArrowLeftRight = R.drawable.ic_lucide_arrow_left_right
    val Sparkles = R.drawable.ic_lucide_sparkles
    val ShieldCheck = R.drawable.ic_lucide_shield_check
    val CircleQuestionMark = R.drawable.ic_lucide_circle_question_mark
    val ChevronRight = R.drawable.ic_lucide_chevron_right
    val CircleCheck = R.drawable.ic_lucide_circle_check
    val Info = R.drawable.ic_lucide_info
    val TriangleAlert = R.drawable.ic_lucide_triangle_alert
    val Camera = R.drawable.ic_lucide_camera
    val Aperture = R.drawable.ic_lucide_aperture
    val Bluetooth = R.drawable.ic_lucide_bluetooth
    val Wifi = R.drawable.ic_lucide_wifi
    val Download = R.drawable.ic_lucide_download
    val RefreshCw = R.drawable.ic_lucide_refresh_cw
    val X = R.drawable.ic_lucide_x
    val CircleX = R.drawable.ic_lucide_circle_x
    val HardDrive = R.drawable.ic_lucide_hard_drive
    val Video = R.drawable.ic_lucide_video
    val SlidersHorizontal = R.drawable.ic_lucide_sliders_horizontal
    val QrCode = R.drawable.ic_lucide_qr_code
    val Keyboard = R.drawable.ic_lucide_keyboard
    val Palette = R.drawable.ic_lucide_palette
    val Trash2 = R.drawable.ic_lucide_trash_2
    val Check = R.drawable.ic_lucide_check
}

/**
 * Lucide 图标渲染（原生 Icon，视口几何居中）。
 *
 * 光学补偿：Lucide 字形在 24×24 视口内几何居中，但视觉重心略高于几何中心
 * （实测矢量渲染本身居中，全局 0；仅导航条图标需下移，见 RootScreen.NavIconExtraShiftY），全局下移补偿。若个别图标观感不同可调此值。
 */
private val OpticalShiftY = 0.dp

@Composable
fun LucideIcon(
    lucide: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    // 默认取内容色：填充按钮内自动为 onPrimary（白），描边按钮/页面内为 onSurface（深），
    // 避免矢量描边写死的白色在浅色背景上不可见
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp
) {
    Icon(
        painter = painterResource(lucide),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .offset(y = OpticalShiftY)
    )
}
