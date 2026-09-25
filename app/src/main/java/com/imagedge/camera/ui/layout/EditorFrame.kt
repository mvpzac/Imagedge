package com.imagedge.camera.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.components.ResultMessage
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 编辑器统一骨架（批次 E）：返回/标题/重置 → 预览 → 工具区 → 保存副本
 *     version: 1.0
 * </pre>
 */

/** 编辑器此刻在忙什么。导出与读图要分开说：前者不许被打断，后者只是还没就绪 */
enum class EditorBusy { None, Preparing, Exporting }

/**
 * 骨架需要的四件事。各编辑器把自己的状态映射进来——
 * 骨架不认识 `PhotoEditState`，所以它不会退化成「参数是一堆 String」的通用壳。
 */
data class EditorFrameState(
    val hasSubject: Boolean,
    val busy: EditorBusy,
    /** 有可撤销的改动时才让「重置」可点；没有改动还摆一个按钮，点了只会丢东西 */
    val canReset: Boolean,
    val result: String? = null,
    val resultOk: Boolean = true
) {
    val exporting: Boolean get() = busy == EditorBusy.Exporting
}

/**
 * 四个编辑器共用的骨架（设计 §4.7）。
 *
 * 三条规矩由骨架统一保证，而不是每个编辑器各自想起来再做一遍：
 * 1. **导出期间返回不假装取消**。这个项目的导出没有安全取消点（Media3 转码与
 *    PTP 写入都不能中途丢下半成品），所以返回键只说明「仍在生成，请稍候」并留在原页；
 *    给一个不起作用的「取消」按钮，用户会以为产物已经废了。
 * 2. **重置是丢成果的决定**，所以过确认对话框（UI 规范 §5：会丢失成果的决定要确认）。
 * 3. 结果说明常驻在内容上方，成功与失败同一条位置；失败不清空编辑参数
 *    （四个编辑器的 ViewModel 本来就都保留参数，这里保证界面真的把它说出来）。
 *
 * @param content 预览 + 工具区。滚动与页面内边距由骨架负责，编辑器不再自己拼
 */
@Composable
fun EditorFrame(
    title: String,
    state: EditorFrameState,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }

    AppScreenFrame(
        modifier = modifier,
        topBar = {
            AppPageHeader(
                title = title,
                onBack = {
                    // 导出中不离开：见 KDoc 第 1 条
                    if (!state.exporting) onBack()
                },
                actions = {
                    AppLink(
                        text = stringResource(R.string.editor_reset),
                        enabled = state.canReset,
                        onClick = { confirmReset = true }
                    )
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.L)
                .padding(bottom = Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.L)
        ) {
            if (state.exporting) {
                Text(
                    text = stringResource(R.string.editor_exporting_stay),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.result?.let { ResultMessage(text = it, ok = state.resultOk) }

            content()

            if (state.busy == EditorBusy.Preparing) {
                ProcessingView(message = stringResource(R.string.editor_preparing))
            }
            AppButton(
                text = stringResource(
                    if (state.exporting) R.string.editor_exporting else R.string.editor_save_copy
                ),
                onClick = onSave,
                enabled = state.hasSubject && state.busy == EditorBusy.None
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.editor_reset_confirm_title)) },
            text = { Text(stringResource(R.string.editor_reset_confirm_body)) },
            confirmButton = {
                AppLink(
                    text = stringResource(R.string.editor_reset),
                    onClick = {
                        confirmReset = false
                        onReset()
                    }
                )
            },
            dismissButton = {
                AppLink(
                    text = stringResource(R.string.editor_keep_editing),
                    onClick = { confirmReset = false }
                )
            }
        )
    }
}
