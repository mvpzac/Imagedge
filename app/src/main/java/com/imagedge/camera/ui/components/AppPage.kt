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
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.layout.AppPageHeader
import com.imagedge.camera.ui.layout.AppScreenFrame
import com.imagedge.camera.ui.theme.Spacing

/**
 * 二级页统一骨架（UI 规范 §5.1）——**[AppScreenFrame] 的薄封装**，不是第二套实现。
 *
 * 为什么留着它而不是把每个页面都改成直接写 AppScreenFrame：这两处页面要的骨架完全一样
 * （标题 → 可滚动内容 → 底部让位），把它们逐个展开只换来一大片缩进 diff。
 * 设计 §9 说的「保留可单独回退的适配层」就是这个意思——**但适配层不许持有第二份 inset 逻辑**，
 * 所以这里只转发，边距与滚动归它自己。
 *
 * 原来它自己拼 `Scaffold + PageHeader`，并在 `padding(innerPadding)` 之后**又**加了一次
 * `windowInsetsPadding(navigationBars)`：底部让位被算两遍，滚到底时最后一行下面凭空多一条
 * 空白（设计 §8.3 明令禁止的就是这种「两处都用」）。
 *
 * @param scrollable 是否整页滚动（工具型页面默认 true；含 LazyColumn 的页面传 false 自行滚动）
 * @param contentPadding 内容内边距；默认左右 16dp、上下 16dp。**改这个前先确认规范允许**
 */
@Composable
fun AppPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.L, vertical = Spacing.L),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.L),
    content: @Composable ColumnScope.() -> Unit
) {
    AppScreenFrame(
        modifier = modifier,
        topBar = {
            AppPageHeader(title = title, onBack = onBack, actions = actions)
        }
    ) {
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

/**
 * 内容区留白（不带头部）：给沉浸型页面或自管滚动的页面单独取用，
 * 保证纵向节奏与 [AppPage] 一致（区块间距 16dp）。
 */
val PageContentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.L)

/** 区块标准间距，供自管布局的页面复用 */
val SectionSpacing = 16.dp
