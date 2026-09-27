package com.imagedge.camera.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.imagedge.camera.R
import com.imagedge.camera.image.LumaHistogram

/**
 * 亮度直方图。
 *
 * 放在设计系统里而不是就地画：取景那边已经在算直方图（live frame 的曝光分析里就带着），
 * 只是还没画。等它上屏时要用的是同一个形状和同一套归一化，不是第二份画法。
 *
 * 纵轴按**最高桶**归一化（[LumaHistogram.normalized]），不是按总像素数——
 * 这是看削顶的正确画法：高光溢出时最右桶会顶到满高，若按总量归一化就永远看不出来。
 */
@Composable
fun Histogram(
    histogram: LumaHistogram?,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fillColor = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.hist_luma)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(HistogramHeight)
            // 图表没有文字替身，至少让读屏说得出它是什么，而不是静默跳过一块空白
            .semantics { contentDescription = description }
    ) {
        val bars = histogram?.normalized() ?: return@Canvas
        if (bars.isEmpty()) return@Canvas
        val step = size.width / bars.size
        val path = Path().apply { moveTo(0f, size.height) }
        bars.forEachIndexed { index, value ->
            path.lineTo((index + 0.5f) * step, size.height * (1f - value))
        }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, color = fillColor.copy(alpha = 0.18f))
        // 左右两端各一条基准线：没有它，暗部贴边时看不出纵轴从哪里起算
        drawLine(
            color = lineColor.copy(alpha = 0.35f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        drawPath(path, color = lineColor.copy(alpha = 0.8f), style = Stroke(width = 1.dp.toPx()))
    }
}

private val HistogramHeight = 56.dp
