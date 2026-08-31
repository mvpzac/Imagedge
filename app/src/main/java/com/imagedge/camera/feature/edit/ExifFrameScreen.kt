package com.imagedge.camera.feature.edit

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.imagedge.camera.ui.components.EmptyState
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.PageHeader

/**
 * 边框水印（批次 C）：选照片 → EXIF 自动读取（可手动修正）→ 4 套模板实时预览
 * → 导出。实况图输入时自动保留动态（画框静态图 + 原视频重新合成）。
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

    Scaffold(
        topBar = { PageHeader(title = "边框水印", onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                state.sourceUri == null -> {
                    EmptyState(
                        icon = Lucide.Camera,
                        title = "边框水印",
                        description = "为照片添加品牌 LOGO、相机型号、等效焦距、快门、ISO 信息边框；EXIF 缺失可手动编辑。实况图加框后动态保留",
                        actionLabel = "选择照片",
                        onAction = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    )
                }

                state.exporting -> {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    Text(
                        "正在导出…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    if (state.message != null) {
                        Text(
                            text = state.message.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.success) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
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
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else if (state.rendering) {
                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(Modifier.padding(16.dp))
                        }
                    }

                    // ── 模板选择 ──
                    Text("模板", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExifFrameViewModel.FrameTemplate.entries.forEach { template ->
                            FilterChip(
                                selected = state.template == template,
                                onClick = { viewModel.setTemplate(template) },
                                label = { Text(template.label) }
                            )
                        }
                    }

                    // ── 字段编辑（EXIF 预填，可手动修正）──
                    Text(
                        "拍摄信息（EXIF 自动读取，可手动修改）" +
                            if (state.isMotion) " · 已识别为实况图，动态将保留" else "",
                        style = MaterialTheme.typography.titleSmall
                    )
                    state.fields.forEach { field ->
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { viewModel.setField(field.label, it) },
                            label = { Text(field.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = { viewModel.export() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    ) { Text("导出到相册") }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small
                    ) { Text("重新选择照片") }
                }
            }
        }
    }
}
