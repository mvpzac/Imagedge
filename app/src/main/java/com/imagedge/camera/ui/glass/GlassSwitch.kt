package com.imagedge.camera.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.imagedge.camera.ui.theme.PillShape

/**
 * 玻璃开关：把 Switch 的轨道换成玻璃，滑块（thumb）保持实体。
 *
 * 做法是把轨道色设为透明露出底下的玻璃层——这样既保留了 Material3 Switch
 * 的手势、状态与无障碍语义，又让轨道呈现为一块折射背景的玻璃。
 * 滑块保持不透明，否则「开/关」会难以辨认（可读性别让位于观感）。
 *
 * 降级时直接返回普通 Switch，与改造前完全一致。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val backdrop = LocalGlassBackdrop.current
    val level = rememberGlassLevel()
    if (backdrop == null || !level.warrantsBackdropCapture()) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
        return
    }

    val trackColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    Box(
        modifier = modifier.glassSurface(
            backdrop = backdrop,
            level = level,
            shape = PillShape,
            surfaceColor = trackColor
        )
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color.Transparent,
                uncheckedTrackColor = Color.Transparent,
                disabledCheckedTrackColor = Color.Transparent,
                disabledUncheckedTrackColor = Color.Transparent
            )
        )
    }
}
