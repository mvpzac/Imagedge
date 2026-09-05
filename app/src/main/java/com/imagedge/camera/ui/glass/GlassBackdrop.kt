package com.imagedge.camera.ui.glass

import androidx.compose.runtime.compositionLocalOf
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
