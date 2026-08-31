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
 *     desc   : 主题模式控制器——设置页切换，SharedPreferences 持久化，
 *              MainActivity 收集后驱动全局配色。
 *     version: 1.0
 * </pre>
 */

/** 主题模式（品牌默认浅色） */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class ThemeController @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.DARK.name)!!) }
            .getOrDefault(ThemeMode.DARK)
    )
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    /**
     * Material You 动态取色开关。默认关闭——开启后主色会跟随系统壁纸变化，
     * 不再保证紫罗兰品牌色统一，故交由用户在设置里自行决定。
     * 仅在 Android 12+ 生效，低版本自动回落品牌色。
     */
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    /** 品牌色档位（动态取色关闭时生效） */
    private val _brandColor = MutableStateFlow(
        runCatching { BrandColor.valueOf(prefs.getString(KEY_BRAND_COLOR, BrandColor.ROSE.name)!!) }
            .getOrDefault(BrandColor.ROSE)
    )
    val brandColor: StateFlow<BrandColor> = _brandColor.asStateFlow()

    fun setBrandColor(color: BrandColor) {
        _brandColor.value = color
        prefs.edit().putString(KEY_BRAND_COLOR, color.name).apply()
    }

    companion object {
        private const val KEY_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_BRAND_COLOR = "brand_color"
    }
}
