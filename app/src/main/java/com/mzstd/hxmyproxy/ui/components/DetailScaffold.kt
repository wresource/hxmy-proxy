package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R

/**
 * 详情页统一骨架（返回箭头 + 标题 + 可选 actions）。
 *
 * 沉浸式与主页一致：**不**用 Material Scaffold/TopAppBar（那会在状态栏区画一块不透明条、
 * 把内容硬切在下方）。改为——内容用 [content] 收到的 contentPadding 穿透系统栏、滚入状态栏
 * 后方；顶部叠一条「背景色→透明」渐变（同主页 StatusBarProtection），返回/标题/actions 浮在
 * 渐变上、固定可用。这样详情页滚动时内容柔和淡入状态栏后方，而非被不透明顶栏切断。
 */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val toolbar = 56.dp
    val surface = MaterialTheme.colorScheme.surface

    // 根容器**不透明** surface 背景：详情页整屏是实底,push/pop 转场时完全盖住下层,
    // 不再透出上一页(此前漏背景 → 转场残影)。
    Box(Modifier.fillMaxSize().background(surface)) {
        // 内容：top 留出状态栏 + 工具栏高（首屏不被挡），滚动时穿入其后方（各页 contentPadding/滚动后置 padding）。
        content(PaddingValues(top = statusTop + toolbar, bottom = navBottom))

        // 状态栏 + 工具栏区渐变保护：多数为 surface（托住图标/标题），底部 ~1/4 渐隐 → 内容柔和穿入。
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(statusTop + toolbar)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0f to surface,
                        0.78f to surface,
                        1f to Color.Transparent,
                    ),
                ),
        )

        // 固定工具栏（透明底，浮在渐变上）：返回(左) · 标题(居中) · actions(右)。
        // 标题绝对居中(全 app 详情页统一)，两侧留出按钮宽度，超长省略。
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = statusTop)
                .height(toolbar),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 56.dp),
            )
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}
