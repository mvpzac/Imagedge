package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.Spacing

/** 页面正文默认内边距：水平 [Spacing.L]、上下 [Spacing.L] */
val PageContentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.L)

/**
 * 页面骨架的**下半层**：只负责标题栏 + 安全区，正文根节点交给调用方。
 *
 * 需要的页面在两种里挑一种：
 * - 正文是一列可以整体滚动的东西 → [AppPage]（它多包一层滚动 + 内边距 + 纵向节奏）
 * - 正文是 grid / 自带滚动的 lazy 容器 → 这个。把它塞进一个 `verticalScroll` 的 Column 里，
 *   里面的 lazy 容器会拿到无界高度，于是要么测量不出来要么把所有项一次全构建
 */
@Composable
fun AppPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /** 一级入口页（相机/照片/创作/设置）用大字标题 */
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    AppScreenFrame(
        modifier = modifier,
        topBar = {
            AppPageHeader(title = title, onBack = onBack, large = large, actions = actions)
        },
        bottomBar = bottomBar
    ) {
        content()
    }
}

/**
 * 页面骨架的**上半层**：[AppPageScaffold] + 一列可滚动正文。
 *
 * 滚动与内边距的顺序固定在这里，页面不再自己拼——`padding` 写在 `verticalScroll()` 之后时，
 * 内边距就成了滚动内容的一部分（往下滚时顶部空出来、正文压上标题栏），编译与单测都拦不住。
 */
@Composable
fun AppPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PageContentPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.L),
    content: @Composable ColumnScope.() -> Unit
) {
    AppPageScaffold(title = title, modifier = modifier, onBack = onBack, large = large, actions = actions) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}
