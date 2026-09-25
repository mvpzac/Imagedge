package com.imagedge.camera.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 遥控页的三件能力条（批次 E）：画面 / 快门 / 拍后保存 分别说
 *     version: 1.0
 * </pre>
 */

/**
 * 常驻状态条（设计 §4.6）。
 *
 * 为什么不是「蓝牙 已连接 / Wi-Fi 已连接」两行技术词：用户要回答的问题是
 * 「我现在能不能拍、拍完会不会自动存」，而不是「哪条射频通了」。
 * 蓝牙没连但相机支持 PTP 遥控时快门其实可用；蓝牙连上了但相机没回能力时
 * 快门其实**未知**——两种情况下「BLE: 已连接」都不构成答案。
 *
 * 位置在取景与快门之间：它是「能不能按」的答案，必须在按之前读到。
 * 图标 + 文字 + 颜色三重表达，不只靠红绿（UI 规范 §8.3）。
 */
@Composable
fun CaptureAvailabilityBar(
    availability: CaptureAvailability,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.S),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS)
    ) {
        LineRow(stringResource(R.string.control_avail_viewfinder), availability.viewfinder)
        LineRow(stringResource(R.string.control_avail_shutter), availability.shutter)
        LineRow(stringResource(R.string.control_avail_autosave), availability.autoSave)
    }
}

@Composable
private fun LineRow(name: String, line: AvailabilityLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        LucideIcon(
            when (line.state) {
                Availability.Ready -> Lucide.CircleCheck
                Availability.NotNow -> Lucide.CircleX
                Availability.Unknown -> Lucide.CircleQuestionMark
            },
            contentDescription = null,
            size = 16.dp,
            tint = when (line.state) {
                Availability.Ready -> MaterialTheme.colorScheme.primary
                Availability.NotNow -> MaterialTheme.colorScheme.onSurfaceVariant
                // 未知不是错误：用中性色 + 问号。染成红色就等于诱导用户去重连，
                // 而重连会让相机端句柄全部失效（已知坑 4、14）
                Availability.Unknown -> MaterialTheme.colorScheme.tertiary
            }
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = line.note ?: stringResource(
                when (line.state) {
                    Availability.Ready -> R.string.control_avail_ready
                    Availability.NotNow -> R.string.control_avail_not_now
                    Availability.Unknown -> R.string.control_avail_unknown
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
