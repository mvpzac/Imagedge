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
 * 同样不含 `uri` / `durationMs`——那是素材本身，由各自的 ViewModel 持有。
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
    /** 片段时长；至少 [ClipMath.MIN_CLIP_MS] */
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}