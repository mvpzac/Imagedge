package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.imagedge.camera.R
import com.imagedge.camera.ui.glass.glassDialog
import com.imagedge.camera.ui.glass.glassDialogContainerColor

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-26
 *     desc   : 会丢东西的决定共用的确认框（UI 规范 §5）
 *     version: 1.0
 * </pre>
 */

/**
 * 确认对话框。原来只有相机档案页有一份私有的，传输页的三个「清空 / 全部取消」于是各弹各的
 * ——或者干脆不弹。
 *
 * [body] 必须说清**实际影响**，不是重复标题：新手手册 §6 的点是「清空」这种词太含糊，
 * 用户无法从按钮名字判断会不会碰到自己的照片。确认按钮用 error 色，
 * 因为按下它一定意味着失去某样东西（哪怕只是列表里的几行）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.glassDialog(),
        containerColor = glassDialogContainerColor(),
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            AppLink(text = confirmLabel, onClick = onConfirm, color = MaterialTheme.colorScheme.error)
        },
        dismissButton = {
            AppLink(text = stringResource(R.string.action_cancel), onClick = onDismiss)
        }
    )
}
