package com.imagedge.camera.feature.edit.clip

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 一个片段的选段结果——两个编辑器真正共用的东西只有这个
 * </pre>
 */

/**
 * 一段素材上「用户选了什么」。
 *
 * 刻意**不含** `Alignment`：裁切对齐是三拼拼贴独有的概念，视频转 LIVE 不做拼贴，
 * 放进共用模型就是给第二个调用方塞一个它永远不读的字段。
 *
 * 同样不带**素材身份**：没有 `uri`，也没有**源**素材的总时长——那是素材本身，
 * 由各自的 ViewModel 持有。本类的 [durationMs] 是由 start/end 推出的**片段**长度，
 * 不是源的长度；两者在 Task 3/4/6 的调用点上都不可混用。
 *
 * @param coverMs 封面时刻，相对**原始**素材。**可为 `null`，且 `null` 与 `0` 意思不同**：
 *   - `null` = 用户没有重选封面，画面取实况图里的**原始静态画面**；
 *   - `0L`（以及任何其他值）= 用户选中了那一帧。**0 是合法时刻**——三拼的封面候选
 *     条带按 `duration * i / count` 抽帧，最左边一张恰好是 0ms。
 *
 *   这两种状态**不能靠数值区分**。曾经用 `coverMs == 0L` 当「未重选」的哨兵，
 *   于是点最左边那张候选帧会读成一次「重置」：标签翻回「封面：原图静态画面」、
 *   恢复原图的入口消失、画面换成解码出来的静态图。封面手柄一旦能拖到 0（Task 6），
 *   这个重载会当场失效，所以哨兵必须写在类型里。
 *
 *   非 `null` 时允许越界（用户可以先选最好的一帧再决定裁哪里），
 *   越界由 [ClipMath.effectiveCoverMs] 收口，且 UI 必须显式标出。
 */
data class ClipSpec(
    val startMs: Long,
    val endMs: Long,
    val coverMs: Long?,
    val audioOn: Boolean = true,
) {
    /**
     * 片段时长 `max(0, endMs - startMs)`。
     *
     * **不保证**至少 [ClipMath.MIN_CLIP_MS]：区间为空或倒置时它是 0；素材本身短于
     * [ClipMath.MIN_CLIP_MS] 时，两个钳制塌陷到素材两端，本值退化为素材的真实时长
     * （例如 100ms 素材 → 100）。「至少 [ClipMath.MIN_CLIP_MS]」只在调用方传入
     * 未退化的钳制结果时成立。
     */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 一格的封面画面**从哪来**。决策与取帧分开：这里只回答「用哪一路」，
 * 真正的解码/抽帧是调用方的事——于是这个判断能被 JVM 单测钉住
 * （`ClipCoverSourceTest`），而它是「预览与导出画的是不是同一帧」的**全部**依据。
 */
sealed interface CoverSource {
    /** 未重选封面（`coverMs == null`）：用实况图里的原始静态画面 */
    data object Still : CoverSource

    /**
     * 重选了封面：抽视频在 [timeMs] 处的帧。
     *
     * **该值已按选段收口**，收口走的就是 [ClipMath.effectiveCoverMs]——放在这一层而不是
     * 让每个调用方各自记得调，正是因为漏调的后果是用户拿到一帧他在屏幕上没见过的画面，
     * 而那种错在预览与导出之间同步发生，从界面上根本看不出来。
     */
    data class Frame(val timeMs: Long) : CoverSource
}

/**
 * [ClipSpec.coverMs] → 这一格该画哪一路。
 *
 * 当前的读取方只有三拼：`buildTriptychBitmap` 调它一次，而预览那一屏与导出那张静图
 * 走的是**同一个** `buildTriptychBitmap`，所以两幅画面同源。视频转 LIVE 编辑器
 * 在 Task 6 接过来——在那之前别说「两个编辑器共用」，它现在还没有第二个调用方。
 *
 * `coverMs == null` → [CoverSource.Still]；其余一律是 [CoverSource.Frame]，
 * **`0L` 也算 Frame**（它是候选条带的第一格，见 [ClipSpec] 的 KDoc）。
 *
 * 返回 [CoverSource.Frame] 时**不再回退**到 Still：调用方若抽帧失败，正确的补救是
 * 显式地再试一次静态图（并记一笔），而不是让这里悄悄换掉用户选的时刻。
 */
fun coverSourceFor(clip: ClipSpec): CoverSource =
    clip.coverMs
        ?.let { CoverSource.Frame(ClipMath.effectiveCoverMs(clip.startMs, clip.endMs, it)) }
        ?: CoverSource.Still
