package com.imagedge.camera.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.imagedge.camera.R
import com.imagedge.camera.data.model.MediaItem
import com.imagedge.camera.ptp.PhotoType
import com.imagedge.camera.ui.components.Lucide
import com.imagedge.camera.ui.components.LucideIcon
import com.imagedge.camera.ui.theme.Radius
import java.util.Locale

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 照片网格格子（批次 C）：整格一个触点，勾选只是它的视觉结果
 *     version: 1.0
 * </pre>
 */

/**
 * 一格照片。
 *
 * **整格承担点击与勾选语义，右上角的勾选标记不再注册第二个点击**（设计 §8.6）。
 * 之前选择只能长按，而长按在触屏上既不可见也不可发现；现在选择态下点格子任意位置
 * 都算勾选，读屏拿到的是一个 `Role.Checkbox` + `selected` 的节点，而不是一个
 * 只有几 dp 的隐形小方块。
 *
 * 缩略图由调用方按格订阅（见 [PhotoGridTile] 的 `bitmap` 参数）：让每格 collect 整张
 * 缓存 Map 的话，任意一张加载完成会让全部可见格子重组，几百项时滚动必掉帧（真机复现过）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGridTile(
    item: MediaItem,
    bitmap: Bitmap?,
    selectionMode: Boolean,
    selected: Boolean,
    savedLocally: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cellShape = RoundedCornerShape(Radius.Tag)
    val toggleDescription = stringResource(R.string.photos_tile_toggle_hint)
    val isSelected = selected
    Box(
        modifier = modifier
            .clip(cellShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = if (selectionMode) toggleDescription
                else stringResource(R.string.photos_tile_open_hint),
                role = if (selectionMode) Role.Checkbox else Role.Button
            )
            // 勾选状态走语义：视觉边框给眼睛，selected 给读屏，两者都要有。
            // 先落到局部变量再赋值：`selected = selected` 在 semantics 块里会被
            // 解析成属性自赋值（读到自己），永远得到 false
            .semantics {
                contentDescription = item.filename
                if (selectionMode) this.selected = isSelected
            }
    ) {
        val thumb = bitmap
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cellShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, cellShape)
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        cellShape
                    )
            )
        }

        item.formatBadge()?.let { badge ->
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(Radius.Tag)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // 已保存到手机：让用户知道这张不必再传一次（角标位置与类型角标错开）
        if (savedLocally) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(Radius.Tag)
                    )
                    .padding(2.dp)
            ) {
                LucideIcon(
                    lucide = Lucide.Check,
                    contentDescription = stringResource(R.string.photos_tile_saved),
                    size = 12.dp,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        if (selectionMode && selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(Radius.Tag)),
                contentAlignment = Alignment.Center
            ) {
                LucideIcon(
                    lucide = Lucide.Check,
                    contentDescription = null,
                    size = 14.dp,
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/** 类型角标：优先文件扩展名，无扩展名时按类型兜底。原样从旧相册页迁移 */
private fun MediaItem.formatBadge(): String? {
    val ext = filename.substringAfterLast('.', "")
        .uppercase(Locale.US)
        .takeIf { it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
    if (ext != null) return ext
    return when (photoType) {
        PhotoType.RAW -> "RAW"
        PhotoType.VIDEO -> "VIDEO"
        PhotoType.JPEG -> "JPG"
        else -> null
    }
}
