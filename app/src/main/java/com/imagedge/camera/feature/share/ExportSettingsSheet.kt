package com.imagedge.camera.feature.share

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.imagedge.camera.ui.glass.LocalGlassLevel
import com.imagedge.camera.ui.glass.LocalGlassBackdrop
import com.imagedge.camera.ui.glass.glassSurface
import com.imagedge.camera.ui.glass.rememberGlassLevel
import com.imagedge.camera.ui.glass.warrantsBackdropCapture
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imagedge.camera.R
import com.imagedge.camera.share.ExportFormat
import com.imagedge.camera.share.ExportSize
import com.imagedge.camera.share.ExifPolicy
import com.imagedge.camera.share.ShareIntents
import com.imagedge.camera.ui.theme.Radius
import androidx.compose.ui.Alignment
import com.imagedge.camera.ui.components.AppButton
import com.imagedge.camera.ui.components.AppChipRow
import com.imagedge.camera.ui.components.AppSlider
import com.imagedge.camera.ui.theme.Spacing

/**
 * 导出设置底部弹窗 —— 分享环节的入口。
 *
 * 让用户决定三件事：导出多大、什么格式、带不带元数据（尤其 GPS）。
 * 确认后生成副本并拉起系统分享面板；原图始终不被改写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSettingsSheet(
    onDismiss: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 导出完成 → 拉起系统分享面板（在 UI 层发起，ViewModel 不持有 Activity）
    LaunchedEffect(Unit) {
        viewModel.shareEvent.collect { payload ->
            launchShare(context, payload)
            onDismiss()
        }
    }

    // 玻璃弹窗：Sheet 自身容器设为透明，内容底下铺一层玻璃。
    // 玻璃引用的是页面背景层（弹窗在 Popup 中，不会被该图层采集）→ 无递归。
    val backdrop = LocalGlassBackdrop.current
    val glassLevel = LocalGlassLevel.current
    val useGlass = backdrop != null && glassLevel.warrantsBackdropCapture()
    val sheetShape = androidx.compose.material3.BottomSheetDefaults.ExpandedShape

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = sheetShape,
        containerColor = if (useGlass) {
            Color.Transparent
        } else {
            androidx.compose.material3.BottomSheetDefaults.ContainerColor
        }
    ) {
        Box(
            modifier = Modifier.glassSurface(
                backdrop = backdrop,
                level = glassLevel,
                shape = sheetShape,
                surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.share_settings_title),
                style = MaterialTheme.typography.titleMedium
            )

            // 尺寸
            SettingsSection(stringResource(R.string.share_size)) {
                AppChipRow(
                    items = ExportSize.entries.toList(),
                    selected = config.size,
                    label = { it.label },
                    onSelect = { viewModel.setSize(it) }
                )
            }

            // 格式
            SettingsSection(stringResource(R.string.share_format)) {
                AppChipRow(
                    items = ExportFormat.entries.toList(),
                    selected = config.format,
                    label = { it.name },
                    onSelect = { viewModel.setFormat(it) }
                )
                // PNG 无 EXIF 容器，需要明说，避免用户误以为元数据被保留
                if (!config.format.supportsExif) {
                    Text(
                        text = stringResource(R.string.share_png_no_exif),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 元数据与隐私
            SettingsSection(stringResource(R.string.share_privacy)) {
                AppChipRow(
                    items = ExifPolicy.entries.toList(),
                    selected = config.exif,
                    label = { it.label },
                    onSelect = { viewModel.setExif(it) },
                    enabled = { config.format.supportsExif }
                )
                val hint = when (config.exif) {
                    ExifPolicy.STRIP_LOCATION -> stringResource(R.string.share_strip_location_hint)
                    ExifPolicy.STRIP_ALL -> stringResource(R.string.share_strip_all_hint)
                    ExifPolicy.KEEP_ALL -> null
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 画质（PNG 无损，无质量概念）
            if (config.format != ExportFormat.PNG) {
                SettingsSection(label = stringResource(R.string.share_quality)) {
                    AppSlider(
                        label = stringResource(R.string.share_quality),
                        value = config.quality,
                        onValueChange = { viewModel.setQuality(it) },
                        range = 60..100,
                        steps = 7
                    )
                }
            }

            val message = error
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            AppButton(
                text = stringResource(R.string.share_export_action),
                onClick = { viewModel.export() },
                enabled = !exporting
            ) {
                // 导出中：内联菊花 + 文案（规范 §7「按钮级加载态」）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S)
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stringResource(R.string.share_export_action),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        content()
    }
}


private fun launchShare(context: Context, payload: SharePayload) {
    val intent = if (payload.uris.size == 1) {
        ShareIntents.single(payload.uris.first(), payload.mime)
    } else {
        ShareIntents.multiple(payload.uris, payload.mime)
    }
    runCatching {
        context.startActivity(
            ShareIntents.chooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
