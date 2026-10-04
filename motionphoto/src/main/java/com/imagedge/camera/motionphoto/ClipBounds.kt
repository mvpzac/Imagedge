package com.imagedge.camera.motionphoto

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-10-04
 *     desc   : 可选片段的时长边界——UI 侧与导出侧的唯一定义
 * </pre>
 */

/**
 * 选片段的时长边界。
 *
 * 存在的理由：UI 的手势钳制与 `VideoTrimmer` 的导出钳制若各写一份数字，
 * 两者一旦漂移，**用户的选择会被静默改写**——预览显示 400ms 而导出给出 1.5s，
 * 或用户选了 7s 而导出悄悄截成 5s。这两个边界必须同源。
 *
 * 放在 `:motionphoto` 且不依赖任何 Android API，是为了让它既能被
 * `internal object VideoTrimmer` 引用，也能被 `:app` 的 `ClipMath` 引用，
 * 同时保持 `:app` 侧那份仍是可在 JVM 上直接测的纯算术。
 */
object ClipBounds {
    /** 最小可选段长（低于此值实况观感太短） */
    const val MIN_CLIP_MS = 1_500L

    /** 单段最大时长 */
    const val MAX_CLIP_MS = 5_000L
}