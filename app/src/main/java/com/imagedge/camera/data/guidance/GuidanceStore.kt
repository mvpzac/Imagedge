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
 * 引导记录。
 *
 * 两条独立的事实，刻意分开存：
 * - **看过/已关闭**：用户点过「知道了」。这只影响指导卡还显不显示；
 * - **任务成功过**：这条路径真的走通过一次。
 *
 * 把它们合成一个布尔就会出错：点「知道了」不等于已完成（新手常常是看懂了字面、
 * 仍然不知道在哪个设备上操作），反过来任务成功过也不代表该文案不需要再看。
 * 键里带 `guideId + version + 可选机型`：文案改版或换了机型，旧的「已看过」不该继续压制提示。
 *
 * 忙碌、权限被拒、连接错误**不读这里**——那些状态该不该显示由业务条件自己决定。
 */
@Singleton
class GuidanceStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 这条引导现在该不该出现（未被用户关闭、且该任务尚未成功过一次） */
    fun shouldShow(guideId: String, model: String? = null): Boolean = !anySet(guideId, model, SET_DISMISSED)

    /** 用户主动关闭/确认已知晓。不影响「成功过」记录 */
    fun markDismissed(guideId: String, model: String? = null) = put(guideId, model, SET_DISMISSED)

    /** 该任务确实走通过一次（由业务成功事件调用，不是由看了引导调用） */
    fun markTaskSucceeded(guideId: String, model: String? = null) = put(guideId, model, SET_SUCCEEDED)

    fun hasSucceeded(guideId: String, model: String? = null): Boolean = anySet(guideId, model, SET_SUCCEEDED)

    /**
     * 设置里的「使用帮助」重新打开引导。
     *
     * 只清「已关闭」，**不清「成功过」**：重看教程不该把已经做成的事实抹掉，
     * 否则老用户会重新看到一堆他早就会的操作提示。
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

    private fun anySet(guideId: String, model: String?, vararg sets: String): Boolean {
        val key = keyOf(guideId, model)
        return sets.any { set -> key in prefs.getStringSet(set, emptySet()).orEmpty() }
    }

    /** 机型参与键值：同一份引导在 ZV-E10 上关掉，不该在另一台机器上也消失 */
    private fun keyOf(guideId: String, model: String?): String = "$guideId@${model ?: "_"}"

    private companion object {
        /** 与其他应用设置同一个文件（主题、下载目录等键也在这里） */
        const val PREFERENCES = "settings"
        const val SET_DISMISSED = "guide_dismissed"
        const val SET_SUCCEEDED = "guide_succeeded"
    }
}
