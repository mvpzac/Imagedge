package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.imagedge.camera.R
import com.imagedge.camera.share.ExportConfig
import com.imagedge.camera.share.ExportFormat
import com.imagedge.camera.share.ExifPolicy
import com.imagedge.camera.ui.theme.Spacing

/**
 * 导出配置控件：**格式 / 元数据与隐私 / 画质** 三组，分享面板与编辑器共用一份。
 *
 * 在此之前这三组只写在 `feature/share/ExportSettingsSheet` 里，编辑器要么没有导出设置、
 * 要么自己再抄一遍——抄的那份会跟着原件漂移，而「扩展名与实际编码不一致」「选了清除位置
 * 结果还是带 GPS」这类错正是漂移的产物。
 *
 * 刻意**不含「尺寸」**：那是分享侧的决策（社交平台会二次压缩，传原图没意义）；
 * 编辑器的导出尺寸由「全分辨率重算」那条路径自己定，摆一个调不动的档位等于骗人。
 *
 * 三个回调分开而不是给一个 `onConfigChange(ExportConfig)`：两个 ViewModel 对字段的
 * 收口不一样（`setQuality` 要 coerce，编辑器的 `setExportConfig` 直接整份替换），
 * 合成一个回调就得让组件去猜该怎么改。
 */
@Composable
fun ExportConfigControls(
    config: ExportConfig,
    onFormatChange: (ExportFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExifChange: (ExifPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.M)
    ) {
        ConfigGroup(stringResource(R.string.share_format)) {
            AppChipRow(
                items = ExportFormat.entries.toList(),
                selected = config.format,
                label = { it.name },
                onSelect = onFormatChange
            )
            // PNG 没有 EXIF 容器，必须明说，否则用户以为元数据被保留了
            if (!config.format.supportsExif) {
                ConfigHint(stringResource(R.string.share_png_no_exif))
            }
        }

        ConfigGroup(stringResource(R.string.share_privacy)) {
            AppChipRow(
                items = ExifPolicy.entries.toList(),
                selected = config.exif,
                label = { it.label },
                enabled = { config.format.supportsExif },
                onSelect = onExifChange
            )
            val hint = when (config.exif) {
                ExifPolicy.STRIP_LOCATION -> stringResource(R.string.share_strip_location_hint)
                ExifPolicy.STRIP_ALL -> stringResource(R.string.share_strip_all_hint)
                ExifPolicy.KEEP_ALL -> null
            }
            if (hint != null) ConfigHint(hint)
        }

        // PNG 无损，没有质量这个概念；AppSlider 自带标题，不再叠一层组名
        if (config.format != ExportFormat.PNG) {
            AppSlider(
                label = stringResource(R.string.share_quality),
                value = config.quality,
                onValueChange = onQualityChange,
                range = 60..100,
                steps = 7
            )
        }
    }
}

@Composable
private fun ConfigGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        content()
    }
}

@Composable
private fun ConfigHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
