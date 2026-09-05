package com.imagedge.camera.ui.glass

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 液态玻璃（Liquid Glass）能力分级 —— 动态降级策略。
 *
 * 玻璃效果依赖两个平台能力，低版本上会**静默失效**（库内部直接 return）：
 * - `blur` 需要 `RenderEffect` → **Android 12（API 31）** 起
 * - `lens`（边缘折射，玻璃感的灵魂）需要 `RuntimeShader/AGSL` → **Android 13（API 33）** 起
 *
 * 因此在旧设备上若仍走玻璃路径，只会得到一块半透明色块（没有模糊也没有折射），
 * 既没有观感收益，还要白白付出每帧离屏渲染的代价。这里按能力分级，
 * 决定「用完整玻璃 / 只模糊 / 完全不用」。
 */
enum class GlassLevel {
    /** 完整玻璃：模糊 + 折射（API 33+） */
    FULL,

    /** 仅背景模糊，无边缘折射（API 31–32） */
    BLUR_ONLY,

    /** 不用玻璃，退回普通半透明表面 */
    NONE
}

/**
 * 判定当前可用的玻璃等级。
 *
 * 触发「完全不用」的情况：
 * - 系统省电模式：玻璃是每帧 GPU 计算，与省电诉求直接冲突
 * - 低内存设备（`isLowRamDevice`）：额外的离屏图层容易引发卡顿甚至 OOM
 */
fun glassLevel(context: Context): GlassLevel {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val lowRam = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.isLowRamDevice == true
    }.getOrDefault(false)

    if (powerManager?.isPowerSaveMode == true || lowRam) return GlassLevel.NONE

    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassLevel.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> GlassLevel.BLUR_ONLY
        else -> GlassLevel.NONE
    }
}

/** 在组合中记住当前玻璃等级（省电模式变化时可感知） */
@Composable
fun rememberGlassLevel(): GlassLevel {
    val context = LocalContext.current
    return remember { glassLevel(context) }
}

/** 是否值得为玻璃效果付出「把内容渲染进离屏图层」的开销 */
fun GlassLevel.warrantsBackdropCapture(): Boolean = this != GlassLevel.NONE
