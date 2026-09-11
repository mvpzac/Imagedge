package com.imagedge.camera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.imagedge.camera.ui.theme.Spacing

/**
 * 二级页统一骨架（UI 规范 §5.1）。
 *
 * 结构固定为：状态栏 inset → [PageHeader]（56dp）→ 内容（左右 [Spacing.L]）→ 底部导航栏 inset。
 * 所有工具型页面都走它，页面之间不再各自决定边距与滚动方式——
 * 这是「结构可预测」原则的载体，也是本轮 UI 规范化的第一个落点。
 *
 * 沉浸型页面（看图、取景、裁剪覆盖层）不使用本组件，它们需要自己控制 insets 与背景。
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
    Scaffold(
        modifier = modifier,
        topBar = { PageHeader(title = title, onBack = onBack, actions = actions) }
    ) { innerPadding ->
        val columnModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(contentPadding)
            .windowInsetsPadding(WindowInsets.navigationBars)
        Column(
            modifier = columnModifier,
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
