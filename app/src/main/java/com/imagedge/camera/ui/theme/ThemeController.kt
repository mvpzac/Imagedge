package com.imagedge.camera.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026/08/28
 *     desc   : 主题模式控制器——设置页切换，SharedPreferences 持久化。
 *              极简黑白主题（2026-08-31 改版）：无品牌色/动态取色，只有浅/深/跟随系统。
 *              新装默认浅色（浅灰底黑调）；已持久化选择的老用户不受影响。
 *     version: 2.0
 * </pre>
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class ThemeController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.LIGHT.name)!!) }
            .getOrDefault(ThemeMode.LIGHT)
    )
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val KEY_MODE = "theme_mode"
        // 旧键（brand_color / dynamic_color）残留无害，不清理
    }
}
