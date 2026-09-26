package com.imagedge.camera.data.guidance

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 新手引导的记忆存储（批次 A）——只记「看过/做成过」，不记业务状态
 *     version: 1.0
 * </pre>
 */

/**
 * 引导记录：**只记「看过/已关闭」这一件事**。
 *
 * 手册原本还要分开存「这条路径做成过一次」。这里刻意不存第二份：
 * 「有没有成功存下照片」是传输历史表里已经存在的事实（`download_history` 非空即成立），
 * 在偏好设置里再记一个布尔就是第二个真相源——两边一旦不同步，界面就会在用户已经
 * 传成过几十张之后继续给他看「第一次怎么传」。本轮重构删掉的就是这类重复状态。
 *
 * 键里带 `guideId + version + 可选机型`：文案改版或换了机型，旧的「已看过」不该继续压制提示。
 *
 * 忙碌、权限被拒、连接错误**不读这里**——那些状态该不该显示由业务条件自己决定。
 */
@Singleton
class GuidanceStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 这条引导现在该不该出现（未被用户关闭） */
    fun shouldShow(guideId: String, model: String? = null): Boolean =
        !keyOf(guideId, model).let { it in prefs.getStringSet(SET_DISMISSED, emptySet()).orEmpty() }

    /** 用户主动关闭/确认已知晓 */
    fun markDismissed(guideId: String, model: String? = null) = put(guideId, model, SET_DISMISSED)

    /**
     * 设置里的「使用帮助」重新打开引导。
     *
     * 新手常常是在不该关的时候点了「知道了」，之后就没有第二条路把说明找回来。
     */
    fun reopenGuides() {
        prefs.edit { remove(SET_DISMISSED) }
    }

    private fun put(guideId: String, model: String?, set: String) {
        val key = keyOf(guideId, model)
        val current = prefs.getStringSet(set, emptySet()).orEmpty().toMutableSet()
        if (!current.add(key)) return
        prefs.edit { putStringSet(set, current) }
    }

    /** 机型参与键值：同一份引导在 ZV-E10 上关掉，不该在另一台机器上也消失 */
    private fun keyOf(guideId: String, model: String?): String = "$guideId@${model ?: "_"}"

    private companion object {
        /** 与其他应用设置同一个文件（主题、下载目录等键也在这里） */
        const val PREFERENCES = "settings"
        const val SET_DISMISSED = "guide_dismissed"
    }
}
