package com.imagedge.camera.navigation

import androidx.annotation.StringRes
import com.imagedge.camera.R
import com.imagedge.camera.feature.connection.ConnectPurpose
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

    /**
     * 连接向导。带「为什么连接」进去，成功页的继续按钮才说得出人话
     * （「查看照片」/「进入遥控」，而不是一个含糊的「完成」）。
     */
    const val CONNECT_WIZARD = "connect/{purpose}"

    /** purpose 缺省 = Browse：用户只是从工作台点「连接相机」，还没有单一目标 */
    fun connectWizard(purpose: ConnectPurpose) = "connect/${purpose.name}"

    /** 遥控拍摄 */
    const val REMOTE = "remote"

    /**
     * 照片调整。uri 可空：从创作 Tab 进来是「自己选图」，从查看器进来是「就编辑这一张」。
     * 传的一律是**已落盘的原图** Uri，不是查看器里的预览位图（设计 §4.4）。
     */
    const val PHOTO_EDIT_PATTERN = "photo_edit?uri={uri}"

    fun photoEdit(uri: android.net.Uri? = null) =
        if (uri == null) "photo_edit" else "photo_edit?uri=${android.net.Uri.encode(uri.toString())}"
    const val LIVE_PHOTO = "live_photo"
    const val LIVE_TRIPTYCH = "live_triptych"
    const val EXIF_FRAME = "exif_frame"

    const val PERMISSIONS = "permissions"

    /** 相机档案与参数预设 */
    const val PROFILES = "profiles"

    /**
     * 大图查看器（mediaId = MediaItem.thumbKey 指纹）。
     *
     * 不再传列表下标：相册在后台刷新一次，同一下标就是另一张照片（设计 §8.1）。
     * 指纹里带 `|`，拼进路由前必须编码。
     */
    const val PHOTO_VIEWER = "photo_viewer/{mediaId}"

    fun photoViewer(mediaId: String) = "photo_viewer/${android.net.Uri.encode(mediaId)}"
}
