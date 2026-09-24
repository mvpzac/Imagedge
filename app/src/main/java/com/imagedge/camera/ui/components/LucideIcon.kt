package com.imagedge.camera.ui.components

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
 * 用法：`LucideIcon(Lucide.Home, contentDescription = "相机", size = 24.dp)`
 */
object Lucide {
    val Home = R.drawable.ic_lucide_home
    val Images = R.drawable.ic_lucide_images
    val Image = R.drawable.ic_lucide_images
    val Settings = R.drawable.ic_lucide_settings
    val ArrowLeft = R.drawable.ic_lucide_arrow_left
    val ArrowLeftRight = R.drawable.ic_lucide_arrow_left_right
    val ArrowUp = R.drawable.ic_lucide_arrow_up
    val ArrowDown = R.drawable.ic_lucide_arrow_down
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

    // ── 监看工作台（T1）新增：几何与 Lucide 官方 SVG 一致，同为描边 2dp/24 视口 ──
    val FlipHorizontal = R.drawable.ic_lucide_flip_horizontal
    val Pause = R.drawable.ic_lucide_pause
    val Play = R.drawable.ic_lucide_play
    val Maximize = R.drawable.ic_lucide_maximize
}

/**
 * Lucide 图标渲染（原生 Icon，视口几何居中）。
 *
 * 曾经怀疑 Lucide 字形的视觉重心高于几何中心、需要全局下移补偿，实测 24×24 视口
 * 内两者重合，补偿量为 0，所以这里不做任何全局偏移。个别图标若观感偏了，
 * 在使用处单独调，不要加回全局常量——那会让每个图标都为一个人的错觉付费。
 */
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
    )
}
