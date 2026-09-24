package com.imagedge.camera.ui.glass

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 全局玻璃背景源。
 *
 * 玻璃元素需要「背后的内容」才能折射。按钮、卡片散布在各个页面里，
 * 逐个传参既啰嗦又容易漏，因此由根布局统一采集一次页面内容，
 * 通过 CompositionLocal 下发，任何层级的组件直接取用即可。
 *
 * 值为 null 表示当前不该使用玻璃（设备不支持 / 省电模式 / 未启用），
 * 组件取到 null 时应当回落到普通表面。
 */
val LocalGlassBackdrop = compositionLocalOf<LayerBackdrop?> { null }

/**
 * 全局玻璃能力等级（由 `navigation/AppRoot` 计算一次后下发）。
 *
 * 为什么要下发而不是各组件自己算：`rememberGlassLevel()` 会查 PowerManager 并
 * **注册一个省电模式广播接收器**。一屏有 5~10 个玻璃元素，每个都注册一次 =
 * 每次进页面 5~10 次 Binder 调用 + 同样次数的反注册，这是切页卡顿的固定开销之一。
 * 等级是全局状态，算一次就够。
 *
 * 默认值取 [GlassLevel.NONE]：万一有组件在 Provider 之外被组合（弹窗预览、测试），
 * 它会安全地退回普通表面，而不是在没有背景源时白付离屏渲染。
 */
val LocalGlassLevel = staticCompositionLocalOf { GlassLevel.NONE }
