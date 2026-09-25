package com.imagedge.camera.navigation

import androidx.annotation.StringRes
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 一级目的地与非 Tab 子路由的定义（批次 B）
 *     version: 1.0
 * </pre>
 */

/**
 * 四个一级入口：相机 / 照片 / 创作 / 设置。
 *
 * 「传输」**不是**第五个 Tab：它是跨页面的后台任务，占一个入口会让人以为
 * 必须切页面才能看进度，而实际想要的是随时可扫一眼（故有全局子路由 + 迷你任务条）。
 *
 * 每个目的地都带**文字标签**：只有图标的底栏要求用户先猜出「三个横条」是编辑、
 * 「星形」是创作，这是把识别成本转嫁给用户（UI 规范 §8）。
 */
enum class TabDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val lucide: Int
) {
    CAMERA("camera", R.string.tab_camera, Lucide.Home),
    PHOTOS("photos", R.string.tab_photos, Lucide.Images),
    CREATE("create", R.string.tab_create, Lucide.Sparkles),
    SETTINGS("settings", R.string.tab_settings, Lucide.Settings);

    companion object {
        /** 未知路由（子页面）时返回 null，调用方据此决定隐藏底栏 */
        fun fromRoute(route: String?): TabDestination? = entries.firstOrNull { it.route == route }
    }
}

/**
 * 非 Tab 子路由。
 *
 * 创作已升为一级入口，这里**不再有** `EDIT_HUB`：同一个页面留两条进入路径
 * 就会有两套返回语义，用户按返回键时不知道该回到哪一层。
 */
object Route {
    /** 全局传输页（队列 + 记录），从任意页可达、返回原页 */
    const val TRANSFER = "transfer"

    /** 遥控拍摄 */
    const val REMOTE = "remote"

    const val PHOTO_EDIT = "photo_edit"
    const val LIVE_PHOTO = "live_photo"
    const val LIVE_TRIPTYCH = "live_triptych"
    const val EXIF_FRAME = "exif_frame"

    const val PERMISSIONS = "permissions"

    /** 相机档案与参数预设 */
    const val PROFILES = "profiles"

    /** 大图查看器（index = 相册列表起始位置） */
    const val PHOTO_VIEWER = "photo_viewer/{index}"

    fun photoViewer(index: Int) = "photo_viewer/$index"
}
