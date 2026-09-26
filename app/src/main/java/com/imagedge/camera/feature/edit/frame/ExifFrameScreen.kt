package com.imagedge.camera.feature.edit.frame

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.layout.EditorBusy
import com.imagedge.camera.ui.layout.EditorFrame
import com.imagedge.camera.ui.layout.EditorFrameState
import com.imagedge.camera.ui.components.AppSection
import com.imagedge.camera.ui.components.AppSwitch
import com.imagedge.camera.ui.components.AppSwitchRow
import com.imagedge.camera.ui.components.AppTextField
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.components.AppButtonType
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.ProcessingView
import com.imagedge.camera.ui.components.ResultMessage
import com.imagedge.camera.ui.theme.Radius

/**
 * 边框水印：选照片 → EXIF 自动读取（型号/焦距/快门/ISO/光圈/拍摄时间，可逐项开关与改写）
 * → 5 套模板实时预览（含自定义文字与圆角）→ 导出。
 * 实况图输入时自动保留动态（画框静态图 + 原视频重新合成）。
 */
@Composable
fun ExifFrameScreen(
    onBack: () -> Unit = {},
    viewModel: ExifFrameViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    // 统一编辑器骨架（批次 E）：导出期间返回不假装取消、重置过确认、结果常驻
    EditorFrame(
        title = "边框水印",
        state = EditorFrameState(
            hasSubject = state.sourceUri != null,
            busy = when {
                state.exporting -> EditorBusy.Exporting
                state.rendering -> EditorBusy.Preparing
                else -> EditorBusy.None
            },
            canReset = state.hasEdits,
            hasEdits = state.hasEdits,
            // 还没选图时不摆主按钮：一个灰掉的「保存副本」只会让人先想它为什么是灰的
            saveVisible = state.sourceUri != null,
            result = state.message,
            resultOk = state.success
        ),
        onSave = { viewModel.export() },
        onReset = { viewModel.resetStyle() },
        onBack = onBack
    ) {
            when {
                state.sourceUri == null -> {
                    EmptyState(
                        title = "边框水印",
                        icon = Lucide.Camera,
                        desc = "为照片添加品牌 LOGO、相机型号、等效焦距、快门、ISO、拍摄时间等信息边框，可加自定义文字；EXIF 缺失可手动编辑，实况图加框后动态保留",
                        actionLabel = "选择照片",
                        onAction = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }

                else -> {
                    if (state.message != null) {
                        ResultMessage(text = state.message.orEmpty(), ok = state.success)
                    }

                    // ── 实时预览（所见即所得）──
                    val preview = state.preview
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "边框预览",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.Card))
                        )
                    } else if (state.rendering) {
                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(Modifier.padding(16.dp))
                        }
                    }

                    // ── 模板选择 ──
                    AppSection(title = "模板") {
                        AppChipRow(
                            items = ExifFrameViewModel.FrameTemplate.entries.toList(),
                            selected = state.template,
                            label = { it.label },
                            onSelect = { viewModel.setTemplate(it) },
                            scrollable = true
                        )
                    }

                    // ── 样式开关（LOGO / 圆角）──
                    AppSection(title = "样式") {
                        AppSwitchRow(
                            title = "显示品牌标识",
                            checked = state.keepLogo,
                            onCheckedChange = { viewModel.setKeepLogo(it) }
                        )
                        AppSwitchRow(
                            title = "照片圆角",
                            checked = state.rounded,
                            onCheckedChange = { viewModel.setRounded(it) }
                        )
                    }

                    // ── 字段编辑（EXIF 预填，可手动修正）──
                    AppSection(
                        title = "拍摄信息",
                        trailing = {
                            Text(
                                text = if (state.isMotion) "实况图 · 动态保留" else "EXIF 自动读取",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    ) {
                        state.fields.forEach { field ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppSwitch(
                                    checked = field.enabled,
                                    onCheckedChange = { viewModel.toggleField(field.label, it) },
                                    contentDescription = "${field.label} 显示开关"
                                )
                                AppTextField(
                                    value = field.value,
                                    onValueChange = { viewModel.setField(field.label, it) },
                                    label = field.label,
                                    enabled = field.enabled,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = Spacing.S)
                                )
                            }
                        }
                    }

                    // ── 自定义文字（署名 / 地点 / 版权）──
                    AppSection {
                        AppTextField(
                            value = state.customText,
                            onValueChange = { viewModel.setCustomText(it) },
                            label = "自定义文字（署名 / 地点 / ©）"
                        )
                    }

                    // 换一张是另一个动作，不能和「重置样式」混成一个按钮（批次 E）
                    AppButton(
                        text = "重新选择照片",
                        onClick = { viewModel.reset() },
                        type = AppButtonType.SECONDARY
                    )
                }
            }
    }
}
