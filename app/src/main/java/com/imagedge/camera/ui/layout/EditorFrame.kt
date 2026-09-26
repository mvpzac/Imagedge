package com.imagedge.camera.ui.layout

import androidx.activity.compose.BackHandler
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
    val canReset: Boolean = false,
    /**
     * 这一屏有没有「保存」这回事。选素材页与结果页没有——在那里摆一个灰掉的主按钮，
     * 用户要先弄清它为什么是灰的，而它本来就不该出现在那儿。
     */
    val saveVisible: Boolean = true,
    /**
     * 有没有**还没存成副本的改动**。非空时离开这一屏要先问一句（设计 §2）：
     * 与 [canReset] 分开是必要的——三拼的「改动」是选好的三张素材，而它没有「重置」这一档。
     * 没改过就别问：每次返回都弹一个框，用户学会的是随手点掉，那时它保护不了任何东西。
     */
    val hasEdits: Boolean = false,
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
 * 3. **带着没存盘的改动离开要先问一句**（设计 §2）：左上角返回、系统返回键、返回手势
 *    三条路径走同一个判断；没改过就不问——每次都弹一个框，用户学会的是随手点掉，
 *    那时它什么都保护不了。
 * 4. 结果说明常驻在内容上方，成功与失败同一条位置；失败不清空编辑参数
 *    （四个编辑器的 ViewModel 本来就都保留参数，这里保证界面真的把它说出来）。
 *
 * @param content 预览 + 工具区。滚动与页面内边距由骨架负责，编辑器不再自己拼
 */
@Composable
fun EditorFrame(
    title: String,
    state: EditorFrameState,
    onSave: () -> Unit,
    onBack: () -> Unit,
    /** 主按钮文案。默认「保存副本」；分段生成的编辑器自己写当前那一步 */
    saveLabel: String = "",
    /** 为 null 表示这个编辑器没有「回到初始状态」这回事（比如三拼的清空会连素材一起丢） */
    onReset: (() -> Unit)? = null,
    /** 确认对话框正文。默认说「调整」，会连素材一起清掉的编辑器必须自己写清楚 */
    resetConfirmBody: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    // 三条系统返回的处理，条件互斥，所以同帧只会有一条生效。
    // 系统返回键/手势必须和左上角那颗返回按钮走同一判断，否则「两条路径行为不一致」
    // 会以另一种形式复现：按钮挡住的东西，手势直接绕过去
    BackHandler(enabled = state.exporting) {
        // 导出期间不离开，也不假装取消（见 KDoc 第 1 条）
    }
    BackHandler(enabled = !state.exporting && state.hasEdits && !confirmLeave) {
        confirmLeave = true
    }
    BackHandler(enabled = confirmLeave) {
        confirmLeave = false
    }

    /** 离开这一屏：改过东西先问，没改过直接走；导出期间不离开（见 KDoc 第 1 条） */
    fun requestLeave() {
        when {
            state.exporting -> Unit
            state.hasEdits -> confirmLeave = true
            else -> onBack()
        }
    }

    AppScreenFrame(
        modifier = modifier,
        topBar = {
            AppPageHeader(
                title = title,
                onBack = ::requestLeave,
                actions = {
                    if (onReset != null) {
                        AppLink(
                            text = stringResource(R.string.editor_reset),
                            enabled = state.canReset,
                            onClick = { confirmReset = true }
                        )
                    }
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
            if (state.saveVisible) {
                AppButton(
                    text = saveLabel.ifBlank {
                        stringResource(
                            if (state.exporting) R.string.editor_exporting else R.string.editor_save_copy
                        )
                    },
                    onClick = onSave,
                    enabled = state.hasSubject && state.busy == EditorBusy.None
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.editor_leave_title)) },
            text = { Text(stringResource(R.string.editor_leave_body)) },
            confirmButton = {
                AppLink(
                    text = stringResource(R.string.editor_leave_discard),
                    onClick = {
                        confirmLeave = false
                        onBack()
                    }
                )
            },
            dismissButton = {
                AppLink(
                    text = stringResource(R.string.editor_keep_editing),
                    onClick = { confirmLeave = false }
                )
            }
        )
    }

    if (confirmReset && onReset != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.editor_reset_confirm_title)) },
            text = { Text(resetConfirmBody ?: stringResource(R.string.editor_reset_confirm_body)) },
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
