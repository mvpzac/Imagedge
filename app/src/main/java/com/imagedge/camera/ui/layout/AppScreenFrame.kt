package com.imagedge.camera.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.imagedge.camera.ui.components.HeaderBackButton
import com.imagedge.camera.ui.glass.glassReactive
import com.imagedge.camera.ui.theme.Spacing
import com.imagedge.camera.ui.theme.UiSize

/**
 * <pre>
 *     author : Imagedge Team
 *     time   : 2026-09-25
 *     desc   : 统一页面骨架：安全区域的唯一所有者（批次 A）
 *     version: 1.0
 * </pre>
 */

/**
 * 普通页面的唯一安全区域所有者。
 *
 * 之前顶部 inset 被加了两次：根布局给 NavHost 外层补了一次 statusBars，
 * 旧标题栏自己又吃一次——所有带标题栏的二级页顶部都多出一条空隙。
 * 这里把所有权收在一处：top 只给 topBar，bottom + horizontal 给内容。
 *
 * 内容侧的内边距**在这里加完**，不往页面传 `PaddingValues`：
 * 实测过传参的写法会翻车——页面把 `padding(innerPadding)` 写在 `verticalScroll()` **之后**，
 * 内边距就成了滚动内容的一部分，往下滚时标题栏下方空出来，正文直接压在大字标题上
 * （设置页出现过）。顺序写错编译器不管、单测不管，只有跑起来才看得见，
 * 所以干脆不给页面写错的机会。
 *
 * 沉浸型页面（取景、看图）不用本组件，它们用各自的 ImmersiveFrame 语义。
 */
@Composable
fun AppScreenFrame(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        ),
        topBar = {
            Box(
                Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
            ) { topBar() }
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
        ) { content() }
    }
}

/**
 * 新标题栏：不消费系统边距（由 [AppScreenFrame] 负责），高度只设下限。
 *
 * 与它取代的旧标题栏只差这两点：旧的不吃边距就无法嵌套，而这里由骨架统一给。
 * 返回钮与标题字阶沿用同一套（[com.imagedge.camera.ui.components.HeaderBackButton]），
 * 迁移前后看不出区别。
 *
 * @param large 一级入口页（相机/照片/创作/设置）用 headlineMedium，子页用 headlineSmall
 * @param actions 尾部动作槽。规范上**最多一个主要动作**，其余降级为文字动作
 */
@Composable
fun AppPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = UiSize.HeaderMin)
            .padding(vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            HeaderBackButton(onClick = onBack)
        }
        Text(
            text = title,
            style = if (large) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.headlineSmall
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) Spacing.L else Spacing.M)
        )
        actions()
    }
}
