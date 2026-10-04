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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 * **参数是 dp，不是像素。** 数量决定抽多少帧，也就决定这条带的内存开销，
 * 所以它必须与跑在哪块屏上无关——除数若是像素，density=1 上 360dp 的轨道给 8 张，
 * density=2.75 上同一段轨道就给 14 张，低端机 OOM 的概率跟着屏幕走。
 * 改成 dp 之后函数体内**没有任何密度因子**，密度无关性由构造保证，
 * 不依赖调用方记得换算（换算错了就是静默错一半的帧数）。
 * 调用方传 `BoxWithConstraints` 作用域里的 `maxWidth.value`——它本来就是 [Dp]。
 *
 * `/48` 的含义是「每张缩略图约 48dp 宽」（也正好是规范 §8.1 的最小触控目标）：
 * 360dp 轨道 → 8 张、每张 45dp；≥ 672dp 才触到 14 的上限，此后单张只会更宽。
 * 上游只给了 `6` / `14` 这两个夹取值（见测试的 KDoc），**没给 48**——除数是本项目自己的取值。
 *
 * 内存量级（**按尺寸算出来的估算，不是本组件的实测值**）：前提有三条，
 * 每帧 ARGB_8888、帧尺寸已知、数量取到 14 上限。在此前提下 264×469 的竖帧约
 * `14 × 264 × 469 × 4 B` ≈ 6.6MB，264×264 的方形帧约 3.9MB。
 * 尺寸由**调用方**决定：本组件只接收已经抽好的 [Bitmap]，既不抽帧也不知道它多大。
 *
 * 名字是历史遗留——它数的是**缩略图**不是手柄（手柄恒为三个）。
 * 纯函数而不是在 Composable 里算：它不该需要真机才能验。
 *
 * @param trackDp 轨道宽度（**dp**，不是像素）。任何实数输入都落进 `[6, 14]`：
 *   未约束宽（`Dp.Infinity`，`.value` 是 `Float.POSITIVE_INFINITY`）取上限，
 *   0、负值与 `NaN` 取下限。
 */
fun filmstripHandleCount(trackDp: Float): Int {
    // 先夹再取整：Float.roundToInt() 在结果超出 Int 范围时抛 IllegalArgumentException，
    // 而未约束宽（`Dp.Infinity.value`）与 Float 极大值都会走到那儿。纯函数不该抛。
    // NaN 单独挡：`coerceIn` 对 NaN 无效，漏了它会一路传到 roundToInt。
    if (trackDp.isNaN()) return 6
    return (trackDp / 48f).coerceIn(6f, 14f).roundToInt().coerceIn(6, 14)
}

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
 * @param thumbs 缩略图帧，**个数由调用方按 [filmstripHandleCount] 的同一策略抽**；
 *   本组件把它们等分铺满，不校验个数。多给少给都不崩，少给只是画面上更粗。
 * @param spec 当前选区（`startMs`/`endMs`）与封面时刻（`coverMs`），拖拽后回传给调用方。
 * @param durationMs 素材时长（ms）。**必须 > 0**：它是 `msToPx` 的除数，为 0 时无意义。
 * @param enabled 当前 tab 开放哪些手柄。**先按它过滤再判定**（见 [filmstripHandleFor]）。
 * @param coverOutOfRange 由 [ClipMath.coverOutOfRange] 算出，本组件自己不调（签名由 brief 定死）。
 *   封面**不被钳进选区**（spec §2.1：先挑最好的帧，再决定裁哪一段），越界只在这里显形。
 *
 *   但「不钳」只针对**选区**这一个方向，别读成两个方向都放开：位移经 [ClipMath.pxToMs]，
 *   而它把像素比钳在 `0..1`，于是 `targetMs = anchorMs + [0, durationMs]`——
 *   已经贴在 x=0 的封面**再也拖不更左**，而贴在 x=trackPx 的封面能拖到约 2 倍时长。
 *   这个不对称是 Task 1 里 `pxToMs` 的契约（比例钳制），不是这里的裁定，要改得改 [ClipMath]。
 *
 * **退化输入什么都不画。** `durationMs <= 0 || thumbs.isEmpty()` 时直接 `return`，
 * 一个节点都不发出：组件量出 0×0，外层那一行会随之塌掉。这是刻意的——
 * 画一条等宽的空轨道等于宣称「这里有帧」。**调用方须自带 loading / 空态。**
 * `durationMs <= 0` 属**调用方错误**（素材还没解出时长），本组件只保证不崩，
 * 不负责兜底成任何时长。
 *
 * **可读性只做到三个手柄**（规范 §8.2 的 contentDescription），缩略图 `contentDescription = null`
 * 是有意的：它们是装饰，逐张播报只会把一屏缩略图念完。这里不是完整无障碍审计。
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
    // 退化输入：见上面 KDoc 的「退化输入什么都不画」——不画而不是画空轨道
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
                                    // 封面**不钳进选区**（spec §2.1）：先挑最好的帧，再决定裁哪一段。
                                    // 越界由 coverOutOfRange 标成红色，钳制留给导出前的收口。
                                    // 仍存在的上/下界只有 pxToMs 的 0..1 比例钳制——见本函数 KDoc。
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
            // 缩略图等分铺满，与时间轴 1:1 对齐。
            // contentDescription = null 是有意的：它们是装饰，逐张播报只会把一屏
            // 缩略图念完；这一带可读的信息由三个手柄的语义承载。
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
                StripHandle(
                    modifier = Modifier.offset {
                        IntOffset(msToPx(spec.startMs).roundToInt(), 0)
                    },
                    label = "起手柄",
                    stateMs = spec.startMs,
                )
            }
            if (FilmstripHandle.Out in enabled) {
                StripHandle(
                    modifier = Modifier.offset {
                        IntOffset(
                            msToPx(spec.endMs).roundToInt() - handlePx.roundToInt(),
                            0,
                        )
                    },
                    label = "止手柄",
                    stateMs = spec.endMs,
                )
            }
            if (FilmstripHandle.Cover in enabled) {
                CoverMark(
                    modifier = Modifier.offset {
                        IntOffset(msToPx(spec.coverMs).roundToInt() - coverHalfPx.roundToInt(), 0)
                    },
                    coverMs = spec.coverMs,
                    outOfRange = coverOutOfRange,
                )
            }
        }
    }
}

/**
 * 起手 / 止手柄。
 *
 * 语义只有 `contentDescription` + `stateDescription`，**故意不给 [Role]**：
 * 手柄只响应拖拽、没有点击动作，报成 `Role.Button` 会向 TalkBack 承诺一个
 * 双击手势，而那个手势在这里不做任何事（规范 §8.2 要的是 contentDescription，已给）。
 * `stateDescription` 带上当前毫秒值，否则读屏只会得到一个没有位置的概念。
 */
@Composable
private fun StripHandle(
    modifier: Modifier = Modifier,
    label: String,
    stateMs: Long,
) {
    Box(
        modifier
            .fillMaxHeight()
            .width(HANDLE_WIDTH)
            .background(MaterialTheme.colorScheme.primary)
            .semantics {
                contentDescription = label
                stateDescription = "$stateMs ms"
            }
    )
}

/** 封面竖线。越界时把「越界」并进描述——颜色之外另给一路信号（规范 §8.3） */
@Composable
private fun CoverMark(
    modifier: Modifier = Modifier,
    coverMs: Long,
    outOfRange: Boolean,
) {
    Box(
        modifier
            .fillMaxHeight()
            .width(COVER_WIDTH)
            .background(
                if (outOfRange) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary
            )
            .semantics {
                contentDescription = if (outOfRange) "封面手柄（已越出选区）" else "封面手柄"
                stateDescription = "$coverMs ms"
            }
    )
}
