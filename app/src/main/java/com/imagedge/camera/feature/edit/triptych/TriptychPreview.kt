package com.imagedge.camera.feature.edit.triptych

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Radius

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 三拼常驻预览——静止的三格封面拼图，按成品画布比例装进高度预算
 * </pre>
 */

/**
 * 三拼页面上方的**静止**预览区：`state.previewBitmap` 就是成品画布
 * （VM 的 `buildTriptychBitmap`，预览与导出同源），本组件只负责按
 * [triptychCanvasSize] 给出的成品比例把它**完整**显示出来，不另算第二份画布算术。
 *
 * **长按顺序播放三段（规格 §4.1）没有做，这一版只有静止图。** 那需要 ExoPlayer
 * 播放头叠加到当前格的偏移、以及按对齐的垂直位移——都是只有真机才看得出来对不对
 * 的东西，而本轮无可运行设备（`adb devices` 为空，`:app` 没有 Compose UI 测试设施）。
 * 一个可能显示错格、或转场与所见不符的播放头，比没有播放头更坏：它会让人以为
 * 「预览验过了」。所以这里不写任何未验证的播放承诺，功能留到能上机核对的那一轮再做。
 *
 * **重建期间上一张继续挂着，并且这一块的高度是预留出来的**（两者都是为了一件事：
 * 拖选段手柄时这一屏不许闪、不许跳）。拖拽每移动一个像素就是一次 `setSpec` →
 * `invalidatePreview`，而它**同步把 `previewBitmap` 置空**（`LiveTriptychViewModel` 里那句
 * 「先把 previewBitmap 置空是有意的」——顺序变了之后继续挂着旧顺序的拼图更容易骗人）。
 * 于是旧版每一帧都走进 `bitmap == null && loading` 这一支：只画一枚 22dp 的菊花，
 * 整块高度从 320dp 塌成 22dp，底下选择条与参数区**上跳约 298dp**、新拼图回来再弹回去。
 * 现在留帧（`lastShown`）经 [previewToShow] 在 `loading` 时继续显示，高度由
 * [previewReservedHeightDp] 预留，菊花叠在预留块的角上——**作废的是状态里那个值，
 * 不是屏幕上那一块**。
 * 显示上一帧不是撒谎：此刻 `loading == true`，界面上同时有「正在重建」的记号，
 * 而 VM 侧 `previewDirty` 那套合并会保证最终一定有一轮用最新 slots 落定。
 *
 * 选择：**这一半修在组件里，VM 的置空语义原样不动**。状态里那个 `null` 是「当前没有
 * 已落定的拼图」这句话，读取方不止这一处（`ResultStage` 也直接画 `state.previewBitmap`），
 * 改成「留着旧值」等于把「这一帧还作不作数」变成每个读取方都要自己判的事；
 * 而「重建期间该画什么」只有这一处界面关心。VM 侧的 `previewDirty` 合批**保留**——
 * 它管的是重建次数，跟这里画不画旧帧无关。
 *
 * @param bitmap 拼图；`null` = 尚未建好或刚被参数改动作废——`null` 且 `loading` 时
 *   显示上一次成功的画面（如有），不显示空白。
 * @param loading 是否在重建（`UiState.previewLoading`）。
 *   `bitmap == null && !loading` 时这一带什么都不画、也不预留高度——没有东西可画且**构建
 *   已经停了**，留一块空白就是在宣称这里将会有画面；那种时刻宁可塌着。
 * @param canvas 成品画布像素（一格宽 × 总格高），来自 [triptychCanvasSize]。
 *   它也当作留帧的 key：换比例/换画质就是换画布，旧画布那张拼图不该被拉进新比例里显示。
 */
@Composable
fun TriptychPreview(
    bitmap: Bitmap?,
    loading: Boolean,
    canvas: CellSize,
    modifier: Modifier = Modifier,
) {
    // 最近一次**成功**的拼图。用普通数组容器而不是 snapshot state：这里在同一次组合里写、
    // 同一次组合里读，不需要触发重组（触发了才是 bug——白跑一轮）。
    // key 是 canvas：画布一变（比例/画质）旧的那张就不再是「同一件事的上一版」，直接丢
    val lastShown = remember(canvas) { arrayOfNulls<Bitmap>(1) }
    if (bitmap != null) lastShown[0] = bitmap
    val shown: Bitmap? = previewToShow(bitmap, lastShown[0], loading)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 只有「有画面可画」或「正在重建」时才预留高度；两者都没有就塌着（见 @param loading）
        val reservedHeightDp = if (shown != null || loading) {
            previewReservedHeightDp(maxWidth.value, canvas)
        } else {
            0f
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(reservedHeightDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                // 先给高度预算、再按画布比例定宽。`aspectRatio` 的默认行为是
                // 「先按满宽算高，高超过 maxHeight 时改为贴合 maxHeight」——
                // 于是竖排画布在窄屏上满宽、宽屏上被高度预算收住，两个约束谁也不裁谁。
                // maxHeight 取预留值，而预留值就是这一张的实际显示高度，所以两者算出来同一个数
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "三拼预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = PREVIEW_MAX_HEIGHT)
                        .aspectRatio(canvas.width.toFloat() / canvas.height)
                        .clip(RoundedCornerShape(Radius.Card)),
                )
            }
            if (loading) {
                // 没有留帧时菊花占在中间；有留帧时挪到角上，压住画面的一角而不是盖住它
                CircularProgressIndicator(
                    Modifier.align(if (shown != null) Alignment.TopEnd else Alignment.Center)
                        .size(22.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * 这一刻这一带该画哪一张。三档，一档都不能少：
 * - 新拼图回来了 → 画**新的**（预览永远跟着最新一次落定的结果，留帧不是拿旧的说成新的）；
 * - 新的还没回来而**正在重建** → 继续画上一帧，同时由调用点叠上转圈的记号。
 *   `invalidatePreview` 每像素把 `UiState.previewBitmap` 置空一次，旧版在这一档
 *   什么都不画、整块塌成一枚 22dp 的菊花——320dp 的预留减去它就是那 298dp 的跳动；
 * - 既没有新帧也没在重建 → `null`：构建已经停了还挂着上一帧，那是把旧画面说成当前结果，
 *   宁可空着（也不预留高度，见 [TriptychPreview] 的 `@param loading`）。
 *
 * 泛型是为了**让这句话能被测**：`android.graphics.Bitmap` 在 JVM 单测里造不出来
 * （本模块没有 Robolectric，`android.jar` 的方法是「not mocked」），
 * 而这一档判定与位图本身无关、只与「有没有」有关。测试见 `TriptychPreviewTest`。
 */
internal fun <B> previewToShow(latest: B?, previous: B?, loading: Boolean): B? =
    latest ?: previous.takeIf { loading }

/**
 * 这一带**该占多高**（dp）：拼图按满宽显示要多高就占多高，超过高度预算 [PREVIEW_MAX_HEIGHT]
 * 就按预算占——与 [TriptychPreview] 里那张 `Image`（`heightIn(max)` + `aspectRatio`）
 * 最终量出来的高度是同一条算术，所以预留不会多出空隙、也不会少出一截。
 *
 * 单独提成 internal 纯函数是为了让「预留高度」这一半**有可测的那一面**：
 * 组件本身在 `:app` 里没有 Compose UI 测试设施，而这一句折算与位图、与 Compose 都无关，
 * 于是它能被 JVM 跑（`TriptychPreviewTest`）。另有一条形状上的保证只能读代码核对：
 * 它**一个 bitmap 参数都不收**——「预留不许看有没有画面」写在签名上，不靠注释。
 *
 * 退化输入：`trackDp` 为 NaN 或 ≤ 0、画布任一边 ≤ 0 都回 `0f`（量不出来就不占地方）；
 * `trackDp` 无限大（未约束宽，`maxWidth == Dp.Infinity`）由 `minOf` 自然收成高度预算。
 *
 * 竖屏手机上的实际取值：`EditorFrame` 内容列左右各吃 `Spacing.L`（16dp，
 * `ui/layout/EditorFrame.kt:154` + `ui/theme/Spacing.kt:18`），360dp 宽的屏给 328dp 轨道。
 * 1080p 档四档画布（1920×3240 / 1080×3240 / 1080×4050 / 1080×1920）折出来的满宽高度是
 * 553.5 / 984 / 1230 / 583dp，720p 档四档（1280×2160 / 720×2160 / 720×2700 / 1080×1920）
 * 是 553.5 / 984 / 1230 / 583dp——**八个组合全部超过 320dp 的预算**，
 * 于是那一屏在这一维恒占 320dp（这八个数由测试逐条算过，不是手抄的）。
 */
internal fun previewReservedHeightDp(trackDp: Float, canvas: CellSize): Float = when {
    trackDp.isNaN() || trackDp <= 0f -> 0f
    canvas.width <= 0 || canvas.height <= 0 -> 0f
    else -> minOf(trackDp * canvas.height.toFloat() / canvas.width.toFloat(), PREVIEW_MAX_HEIGHT_DP)
}

/**
 * 预览的高度预算——**本项目的取值，不是规范 token**。
 *
 * 为什么需要上限：画布是竖排的（16:9 档三格堆成 16:27）。以 328dp 内容宽算，
 * 满宽显示 1920×3240 的画布要 328 × 3240 ÷ 1920 ≈ 554dp 高——参数区整块被推到
 * 折叠线以下，「改一个参数看一眼预览」就变成「改一个参数滚一次屏」。
 * 定 320dp，让三格始终**等比完整**可见（不裁切），参数区在典型竖屏上同屏可达；
 * 这个数是观感取舍，接上真机后应复核。
 *
 * 同一数字的两个形态：[PREVIEW_MAX_HEIGHT_DP] 给 [previewReservedHeightDp]（纯算术，dp 的
 * `.value`），[PREVIEW_MAX_HEIGHT] 由它派生、给 `heightIn`。**只有一个字面量**，别抄第二份。
 */
private const val PREVIEW_MAX_HEIGHT_DP = 320f
private val PREVIEW_MAX_HEIGHT = PREVIEW_MAX_HEIGHT_DP.dp
