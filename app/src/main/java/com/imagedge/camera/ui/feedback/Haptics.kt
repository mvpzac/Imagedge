package com.imagedge.camera.ui.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 触觉反馈统一入口。触发逻辑：
 * - tick   选择确认（长按选中/取消、筛选切换）
 * - click  开关切换
 * - thud   重要动作（快门、拍照、录像开关、保存/连接成功）
 * - double 错误（连接/保存/扫码失败）
 * 不震：滚动、翻页、滑条拖动过程、后台轮询事件。
 *
 * 三道闸：应用内开关（默认开）→ 系统「触摸震动」设置 → 设备有震动器。
 */
@Singleton
class Haptics @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun tick() = vibrate(Pattern.TICK)
    fun click() = vibrate(Pattern.CLICK)
    fun thud() = vibrate(Pattern.THUD)
    fun double() = vibrate(Pattern.DOUBLE)

    private fun vibrate(pattern: Pattern) {
        if (!_enabled.value) return
        if (!systemHapticsEnabled()) return
        val vibrator = resolveVibrator() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val effectId = when (pattern) {
                    Pattern.TICK -> VibrationEffect.EFFECT_TICK
                    Pattern.CLICK -> VibrationEffect.EFFECT_CLICK
                    Pattern.THUD -> VibrationEffect.EFFECT_HEAVY_CLICK
                    Pattern.DOUBLE -> VibrationEffect.EFFECT_DOUBLE_CLICK
                }
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                val (timings, amplitudes) = when (pattern) {
                    Pattern.TICK -> longArrayOf(0, 10) to intArrayOf(0, 60)
                    Pattern.CLICK -> longArrayOf(0, 15) to intArrayOf(0, 90)
                    Pattern.THUD -> longArrayOf(0, 30) to intArrayOf(0, 160)
                    Pattern.DOUBLE -> longArrayOf(0, 20, 80, 20) to intArrayOf(0, 120, 0, 120)
                }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            }
        }
    }

    private fun resolveVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }

    private fun systemHapticsEnabled(): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) == 1
    }.getOrDefault(true)

    private enum class Pattern { TICK, CLICK, THUD, DOUBLE }

    companion object {
        private const val KEY_ENABLED = "haptics_enabled"
    }
}
