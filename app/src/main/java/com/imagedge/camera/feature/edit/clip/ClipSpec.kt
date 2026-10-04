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
 * @param coverMs 封面时间，相对**原始**素材。允许越界（用户可以先选最好的一帧再决定裁哪里），
 *   越界时由 [ClipMath.effectiveCoverMs] 收口，且 UI 必须显式标出。
 */
data class ClipSpec(
    val startMs: Long,
    val endMs: Long,
    val coverMs: Long,
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