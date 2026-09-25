package com.imagedge.camera.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import com.imagedge.camera.ui.theme.UiSize

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 主快门控件（批次 E）：72dp 圆形视觉 + 文字，只交出「按下 / 抬起」两个事件
 *     version: 1.0
 * </pre>
 */

/**
 * 主快门。
 *
 * **拍摄时序不在这里**。已验证的 BLE 半按序列（半按对焦 → 全按 → 等相机 ff02 回报 →
 * 释放 → 半按释放 → 写卡沉降）由 `CameraControlViewModel.runBleCapture()` 拥有，
 * 本组件只把按下/抬起两个事件交出去。手势体与迁移前逐字相同：
 * 这段路径每次改动都要真机验证，宁可写得直白也不重排（docs/HANDOFF.md 已知坑 22）。
 *
 * 触摸与无障碍做的是同一件事：读屏拿到一个 `Role.Button`，一次点击 = 按下 + 抬起。
 * 半按对焦对读屏用户没有意义，也不该要求他们按住不放。
 *
 * @param enabled 为 false 时一个事件都不发：忙、未连接、能力未知都不该触达通道
 */
@Composable
fun ShutterControl(
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape
            )
            // 禁用态要能看出边界：灰底压在浅页面上几乎不存在，
            // 没有这圈描边，用户看到的是「一块和背景同色的空白」而不是一个按不动的快门
            .border(
                width = 1.dp,
                color = if (enabled) Color.Transparent
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (enabled) {
                            onPress()      // 按下：对焦
                            tryAwaitRelease()
                            onRelease()    // 抬起：拍摄
                        }
                    }
                )
            }
            .semanticsShutter(label, enabled, onPress, onRelease),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 快门尺寸就是设计定的 72dp 视觉直径，取自 token，不散写 */
val ShutterSize = UiSize.ShutterDiameter

/**
 * 语义单独抽出来写：`pointerInput` 与 `semantics` 谁在外层会影响读屏节点是否可点击，
 * 混在一长链里看不清。
 */
@Composable
private fun Modifier.semanticsShutter(
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
): Modifier = this.semantics(mergeDescendants = true) {
    role = Role.Button
    contentDescription = label
    stateDescription = if (enabled) "可拍摄" else "暂不可用"
    onClick {
        if (enabled) {
            onPress()
            onRelease()
        }
        enabled
    }
}
