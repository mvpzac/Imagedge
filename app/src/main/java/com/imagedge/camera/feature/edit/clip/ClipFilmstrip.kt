package com.imagedge.camera.feature.edit.clip

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius
import kotlin.math.roundToInt

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 选片段用的缩略图条——起手/止手/封面三个手柄，单个拖拽检测器
 * </pre>
 */

/** 缩略图条上可拖的三个手柄 */
enum class FilmstripHandle { In, Out, Cover }

/** 起手/止手柄的宽度。左边贴选区起边、右边贴选区止边，两侧对称 */
private val HANDLE_WIDTH: Dp = 6.dp

/** 封面竖线的宽度，整条画在选区之上 */
private val COVER_WIDTH: Dp = 3.dp

/**
 * 缩略图数量 = 轨道宽度 / 48dp，钳在 `[6, 14]`。
 *
 * 名字是历史遗留——它数的是**缩略图**不是手柄（手柄恒为三个）。
 * 纯函数而不是在 Composable 里算：缩略图条是本项目内存占用的大头
 * （每张 264px，约 7MB），数量算错一次就是低端机 OOM，
 * 而它不该需要真机才能验。
 */
fun filmstripHandleCount(trackPx: Float): Int =
    (trackPx / 48f).roundToInt().coerceIn(6, 14)

/**
 * 按下位置 → 该拖哪个手柄，**启用集合先过滤、再定区**。
 *
 * 顺序是这份实现里唯一一处非平凡的裁定：先 [ClipMath.resolveZone] 再看 `enabled` 的写法
 * 在「封面」tab 上会把靠近起手柄的按下判给被禁用的起手柄，表现为**死区**——
 * 手指按住封面竖线旁边却一个字也不动。故禁用者的位置先被挪出命中半径，
 * 剩下的候选才交给 [ClipMath.resolveZone]（半径、同距时的全序都由它一家说了算）。
 *
 * 挪多远是算出来的，不是猜的：离 `x` 恰好比 [ClipMath.TOUCH_RADIUS_PX] 远 1px，
 * 于是它在半径过滤那一步必然被滤掉，与三个手柄的实际位置无关。
 *
 * 坐标单位是 **density=1 的像素**——[ClipMath.TOUCH_RADIUS_PX] 是密度为 1 时的基准值
 * 而 `resolveZone` 收不到半径参数，调用方能做的换算只有换坐标空间。
 *
 * @return 命中的手柄；一个启用手柄都不在半径内（或 `enabled` 为空）时返回 `null`
 */
fun filmstripHandleFor(
    x: Float,
    startPx: Float,
    endPx: Float,
    coverPx: Float,
    enabled: Set<FilmstripHandle>,
): FilmstripHandle? {
    val outOfReach = x - (ClipMath.TOUCH_RADIUS_PX + 1f)
    val zone = ClipMath.resolveZone(
        x = x,
        startPx = if (FilmstripHandle.In in enabled) startPx else outOfReach,
        endPx = if (FilmstripHandle.Out in enabled) endPx else outOfReach,
        coverPx = if (FilmstripHandle.Cover in enabled) coverPx else outOfReach,
    )
    return when (zone) {
        ClipMath.Zone.Start -> FilmstripHandle.In
        ClipMath.Zone.End -> FilmstripHandle.Out
        ClipMath.Zone.Cover -> FilmstripHandle.Cover
        ClipMath.Zone.None -> null
    }
}

/**
 * 选片段的缩略图条。
 *
 * 三条来自两个独立开源项目的教训，实现时不要「简化」掉：
 *
 * 1. **单个 `detectDragGestures`，`onDragStart` 一次定区。**
 *    ClearCut `Timeline.kt:2060-2062` 的注释记着他们踩过的坑：父子各挂一个检测器时
 *    父级会先吃掉边缘触摸事件，导致多设备上裁切边拖不动。
 * 2. **锚点位移，不用绝对位置。** `targetMs = anchorMs + pxToMs(pos.x - anchorPx)`。
 *    绝对位置会让首次 `onDrag` 直接把手柄跳到手指下（OpenLoop `:487`）。
 * 3. **`systemGestureExclusion()`。** 起手柄初始就在 x≈0，不排掉会被系统后滑手势抢走
 *    （OpenLoop `:450`）。注意它来自 `androidx.compose.foundation`，不是 `…foundation.layout`。
 *
 * @param enabled 当前 tab 开放哪些手柄。**先按它过滤再判定**（见 [filmstripHandleFor]）。
 * @param coverOutOfRange 由 [ClipMath.coverOutOfRange] 算出。封面**不钳**进选区
 *   （spec §2.1：先挑最好的帧，再决定裁哪一段），但越界必须显形。
 */
@Composable
fun ClipFilmstrip(
    thumbs: List<Bitmap>,
    spec: ClipSpec,
    durationMs: Long,
    enabled: Set<FilmstripHandle>,
    coverOutOfRange: Boolean,
    onSpecChange: (ClipSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0L || thumbs.isEmpty()) return
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.clip(RoundedCornerShape(Radius.Tag))) {
        // 0 宽与未约束宽（maxWidth = Infinity）都要挡掉：前者让所有偏移退化成 0、
        // 三个手柄叠在 x=0 上互相抢，后者让 msToPx 算出 NaN 再喂给 width()
        val trackPx = with(density) { maxWidth.toPx() }
            .let { if (it.isFinite() && it > 0f) it else 1f }
        val handlePx = with(density) { HANDLE_WIDTH.toPx() }
        val coverHalfPx = with(density) { (COVER_WIDTH / 2f).toPx() }
        // 命中判定整体在 density=1 空间里做，见 filmstripHandleFor 的说明
        val hitScale = density.density.let { if (it > 0f) it else 1f }
        val trackHitPx = trackPx / hitScale
        val scrim = MaterialTheme.colorScheme.scrim

        fun msToPx(ms: Long): Float =
            (ms.toFloat() / durationMs * trackPx).coerceIn(0f, trackPx)
        fun msToHitPx(ms: Long): Float = msToPx(ms) / hitScale
        fun pxToDp(px: Float): Dp = with(density) { px.toDp() }

        var dragging by remember { mutableStateOf<FilmstripHandle?>(null) }
        var anchorHitPx by remember { mutableFloatStateOf(0f) }
        var anchorMs by remember { mutableLongStateOf(0L) }

        Box(
            Modifier
                // 显式铺满：拖拽层宽度必须等于上面的 trackPx，否则锚点位移会整体偏掉
                .fillMaxWidth()
                .fillMaxHeight()
                .systemGestureExclusion()
                // 1.12 里 detectDragGestures 只是 PointerInputScope 的扩展，
                // Modifier 版本已不存在；单键 pointerInput 让指针输入协程整条只跑一份。
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val handle = filmstripHandleFor(
                                x = pos.x / hitScale,
                                startPx = msToHitPx(spec.startMs),
                                endPx = msToHitPx(spec.endMs),
                                coverPx = msToHitPx(spec.coverMs),
                                enabled = enabled,
                            )
                            dragging = handle
                            if (handle != null) {
                                anchorHitPx = pos.x / hitScale
                                anchorMs = when (handle) {
                                    FilmstripHandle.In -> spec.startMs
                                    FilmstripHandle.Out -> spec.endMs
                                    FilmstripHandle.Cover -> spec.coverMs
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            val handle = dragging ?: return@detectDragGestures
                            change.consume()
                            val targetMs = anchorMs + ClipMath.pxToMs(
                                px = change.position.x / hitScale - anchorHitPx,
                                trackPx = trackHitPx,
                                durationMs = durationMs,
                            )
                            onSpecChange(
                                when (handle) {
                                    // 封面**不钳**（spec §2.1）：先挑最好的帧，再决定裁哪一段。
                                    // 越界由 coverOutOfRange 标成红色，钳制留给导出前的收口。
                                    FilmstripHandle.Cover -> spec.copy(coverMs = targetMs)
                                    FilmstripHandle.In ->
                                        spec.copy(startMs = ClipMath.clampStart(targetMs, spec.endMs))
                                    FilmstripHandle.Out ->
                                        spec.copy(
                                            endMs = ClipMath.clampEnd(
                                                targetMs,
                                                spec.startMs,
                                                durationMs,
                                            )
                                        )
                                }
                            )
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                    )
                }
        ) {
            // 缩略图等分铺满，与时间轴 1:1 对齐
            Row(Modifier.matchParentSize()) {
                thumbs.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            // 选区外的暗色蒙层：让「哪一段会被保留」一眼可见。
            // 两块都挂在选区边界上（左侧靠 Box 的 TopStart、右侧靠 CenterEnd），
            // 不用累计偏移，故 trackPx 取不到、或片段就是整段素材时都不会漂移。
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(pxToDp(msToPx(spec.startMs)))
                    .background(scrim.copy(alpha = 0.5f))
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(pxToDp(trackPx - msToPx(spec.endMs)))
                    .background(scrim.copy(alpha = 0.5f))
            )

            // 手柄：起手柄左缘贴选区起边、止手柄右缘贴选区止边。
            // 偏移一律减自己的宽度而不是触控半径——减半径会留下 24dp-6dp 的缝，
            // 手柄看着没压在边界上。
            if (FilmstripHandle.In in enabled) {
                StripHandle(Modifier.offset { IntOffset(msToPx(spec.startMs).roundToInt(), 0) })
            }
            if (FilmstripHandle.Out in enabled) {
                StripHandle(
                    Modifier.offset {
                        IntOffset(msToPx(spec.endMs).roundToInt() - handlePx.roundToInt(), 0)
                    }
                )
            }
            if (FilmstripHandle.Cover in enabled) {
                CoverMark(
                    modifier = Modifier.offset {
                        IntOffset(msToPx(spec.coverMs).roundToInt() - coverHalfPx.roundToInt(), 0)
                    },
                    outOfRange = coverOutOfRange,
                )
            }
        }
    }
}

@Composable
private fun StripHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxHeight()
            .width(HANDLE_WIDTH)
            .background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun CoverMark(modifier: Modifier = Modifier, outOfRange: Boolean) {
    Box(
        modifier
            .fillMaxHeight()
            .width(COVER_WIDTH)
            .background(
                if (outOfRange) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary
            )
    )
}
