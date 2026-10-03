package com.imagedge.camera.data.model

import android.content.Context
import androidx.core.content.edit
import com.imagedge.camera.data.model.MonitoringSettings.Companion.KEY_ASPECT
import com.imagedge.camera.data.model.MonitoringSettings.Companion.KEY_FOCUS_PEAK
import com.imagedge.camera.data.model.MonitoringSettings.Companion.KEY_GRID
import com.imagedge.camera.data.model.MonitoringSettings.Companion.KEY_MIRRORED
import com.imagedge.camera.data.model.MonitoringSettings.Companion.KEY_ROTATION
import com.imagedge.camera.data.model.MonitoringSettings.Companion.KEY_ZEBRA
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-24
 *     desc   : 监看偏好存储（T1）
 *     version: 1.0
 * </pre>
 */

/**
 * 监看偏好读写。
 *
 * 落在与应用其余设置同一个 `settings` SharedPreferences 文件里（下载目录等键也在那儿），
 * 不再另开文件。之所以要走持久化而不是只活在 Compose 状态里：
 * ① 横竖屏切换与进程回收都要能还原；② 遥控页嵌入预览与监看工作台必须显示同一组设置，
 * 两个组合位置各自持有一份状态迟早会不一致。
 */
@Singleton
class MonitoringSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())

    val settings: StateFlow<MonitoringSettings> = _settings.asStateFlow()

    /** 更新并落盘。值没变就不写，避免每次手势回调都刷一遍 prefs */
    fun update(transform: (MonitoringSettings) -> MonitoringSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        prefs.edit {
            putString(KEY_ROTATION, next.rotation.name)
            putBoolean(KEY_MIRRORED, next.mirrored)
            putString(KEY_GRID, next.gridMode.name)
            putString(KEY_ASPECT, next.aspectMarker.name)
            // 存成字符串而不是 putBoolean：读取侧 [MonitoringSettings.fromStored] 只吃
            // `Map<String, String?>`，它要在 JVM 上单测。混用两套类型会在
            // SharedPreferences.getString 上抛 ClassCastException——只在真机上炸
            putString(KEY_FOCUS_PEAK, next.focusPeak.toString())
            putString(KEY_ZEBRA, next.zebra.toString())
        }
    }

    private fun load(): MonitoringSettings = MonitoringSettings.fromStored(
        stored = mapOf(
            KEY_ROTATION to prefs.getString(KEY_ROTATION, null),
            KEY_GRID to prefs.getString(KEY_GRID, null),
            KEY_ASPECT to prefs.getString(KEY_ASPECT, null),
            KEY_FOCUS_PEAK to prefs.getString(KEY_FOCUS_PEAK, null),
            KEY_ZEBRA to prefs.getString(KEY_ZEBRA, null)
        ),
        mirrored = prefs.getBoolean(KEY_MIRRORED, false)
    )

    private companion object {
        /** 与 CameraRepository 的下载目录设置同文件，保持单一小配置源 */
        const val PREFERENCES = "settings"
    }
}
