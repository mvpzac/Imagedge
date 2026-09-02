package com.imagedge.camera.feature.edit

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.image.EditStep
import com.imagedge.camera.share.ShareIntents
import com.imagedge.camera.ui.components.PageHeader
import com.imagedge.camera.ui.theme.Radius

/**
 * 基础调整界面（一站式「编辑」环节的第一步）。
 *
 * 只做相机出片后最常用的四项调整 + 旋转，刻意不做成 Lightroom：
 * 相机配套 App 的编辑应该服务于「快速出图」，深度精修交给专业工具。
 *
 * 调整基于 `:image` 的非破坏性管线，预览走降采样渲染。
 */
@Composable
fun BasicEditScreen(
    sourceUri: Uri,
    onBack: () -> Unit = {},
    viewModel: BasicEditViewModel = hiltViewModel()
) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val adjustments by viewModel.adjustments.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(sourceUri) { viewModel.load(sourceUri) }

    // 渲染完成 → 拉起系统分享面板
    LaunchedEffect(result) {
        val uri = result ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                ShareIntents.chooser(
                    ShareIntents.single(uri, "image/jpeg"),
                    null
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Scaffold(
        topBar = {
            PageHeader(
                title = stringResource(R.string.edit_basic_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 预览
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = preview
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = "无法加载该图片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AdjustmentSlider(
                label = stringResource(R.string.edit_brightness),
                value = adjustments.brightness,
                onValueChange = { viewModel.adjust(EditStep.Brightness(it)) }
            )
            AdjustmentSlider(
                label = stringResource(R.string.edit_contrast),
                value = adjustments.contrast,
                onValueChange = { viewModel.adjust(EditStep.Contrast(it)) }
            )
            AdjustmentSlider(
                label = stringResource(R.string.edit_saturation),
                value = adjustments.saturation,
                onValueChange = { viewModel.adjust(EditStep.Saturation(it)) }
            )
            AdjustmentSlider(
                label = stringResource(R.string.edit_temperature),
                value = adjustments.temperature,
                onValueChange = { viewModel.adjust(EditStep.Temperature(it)) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.rotate90() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.Control)
                ) {
                    Text(stringResource(R.string.edit_rotate))
                }
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Radius.Control)
                ) {
                    Text(stringResource(R.string.edit_reset))
                }
            }

            Button(
                onClick = { viewModel.finish() },
                enabled = !busy && !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(Radius.Control)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.edit_finish))
                }
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (value == 0f) "—" else "%+.0f".format(value * 100),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
