package com.imagedge.camera.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.imagedge.camera.R
import com.imagedge.camera.data.model.CameraCapability
import com.imagedge.camera.data.model.CapabilityState
import com.imagedge.camera.ui.components.AppLink
import com.imagedge.camera.ui.components.GroupTitle
import com.imagedge.camera.ui.theme.Spacing

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 拍摄参数面板（批次 E）：ISO / 光圈 / 快门收进底部面板，每行显示当前值与可写状态
 *     version: 1.0
 * </pre>
 */

/**
 * 一行参数的展示信息：能力项、标题、以及**一句白话解释**。
 *
 * 解释常驻（新手手册 §4）：这一屏不该要求用户先懂「EV」「F 值」才敢动。
 * 它不是错误提示，所以不可调时也照样说——用户更需要在那时候知道这项是干什么的。
 */
private data class ParamRow(
    val capability: CameraCapability,
    @param:StringRes val labelRes: Int,
    @param:StringRes val explainRes: Int
)

/** 参数区展示顺序 */
private val PARAM_ROWS = listOf(
    ParamRow(
        CameraCapability.EXPOSURE_PROGRAM_MODE,
        R.string.control_shoot_mode,
        R.string.control_explain_shoot_mode
    ),
    ParamRow(CameraCapability.ISO, R.string.control_iso, R.string.control_explain_iso),
    ParamRow(CameraCapability.F_NUMBER, R.string.control_fnumber, R.string.control_explain_fnumber),
    ParamRow(CameraCapability.SHUTTER_SPEED, R.string.control_shutter, R.string.control_explain_shutter),
    ParamRow(CameraCapability.WHITE_BALANCE, R.string.control_wb, R.string.control_explain_wb),
    ParamRow(CameraCapability.EXPOSURE_BIAS, R.string.control_eb, R.string.control_explain_eb)
)

/**
 * 遥控页的参数面板。
 *
 * 收进面板不是为了少占地方：取景 + 快门是这一屏的主角，参数是偶尔动一下的东西。
 * 平铺在快门下面时，每次进遥控都要先滚过一屏参数才看到快门。
 *
 * 每行仍然**自己说可不可写**（[CapabilityParamRow]）：档位与可调性一律来自 0x9209
 * 描述符，读不到就写「未知」，绝不回退到内置档位表（docs/HANDOFF.md 已知坑 14）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSettingsSheet(
    params: Map<CameraCapability, ParamUiState>,
    stale: Boolean,
    onDismiss: () -> Unit,
    onSelect: (CameraCapability, Long) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.L)
                .padding(bottom = Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.M)
        ) {
            GroupTitle(stringResource(R.string.control_params_sheet))
            PARAM_ROWS.forEach { row ->
                CapabilityParamRow(
                    label = stringResource(row.labelRes),
                    explanation = stringResource(row.explainRes),
                    param = params[row.capability],
                    stale = stale,
                    onSelect = { raw -> onSelect(row.capability, raw) }
                )
            }
        }
    }
}

/**
 * 一行参数：左标签、右当前值（可写时是可点的下拉，不可写时是纯文本 + 原因）。
 *
 * 「界面禁用」与「不会发命令」必须是同一件事：editable 由 `ParamUiState` 带下来，
 * 而 `CameraRepository` 那侧还有同一份校验兜底，所以这里禁用不是装饰。
 */
@Composable
private fun CapabilityParamRow(
    label: String,
    explanation: String,
    param: ParamUiState?,
    stale: Boolean,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = param?.options.orEmpty()
    val editable = param?.editable == true && options.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (editable) {
                Box {
                    AppLink(
                        text = param.currentLabel ?: "--",
                        onClick = { expanded = true }
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        options.forEach { (name, code) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onSelect(code)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = param?.currentLabel ?: "--",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        if (!editable) {
            Text(
                text = stringResource(blockReasonOf(param, stale)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 不可调整的原因 → 文案资源（顺序即优先级） */
private fun blockReasonOf(param: ParamUiState?, stale: Boolean): Int = when {
    param == null -> R.string.control_capability_unknown
    param.detail.state == CapabilityState.UNKNOWN -> R.string.control_capability_unknown
    param.detail.state == CapabilityState.UNSUPPORTED -> R.string.control_capability_unsupported
    param.detail.state == CapabilityState.READ_ONLY -> R.string.control_capability_read_only
    stale -> R.string.control_capability_stale
    else -> R.string.control_capability_no_options
}
