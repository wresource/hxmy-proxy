package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mzstd.hxmyproxy.R

/**
 * 详情页统一骨架：M3 [TopAppBar]（返回箭头 + 标题 + 可选 actions）+ 内容。
 * 取代各详情页手绘的「返回行」，让全部二级页顶部样式一致。
 *
 * Insets：外层 AppRoot 的 Scaffold 已 consume 系统栏 padding，本层 [Scaffold] 查询到的剩余
 * inset 为 0，不会双重留白（edge-to-edge 嵌套 consume 模式）；IME 仍排除，交给页内 imePadding。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // B(头部随滚动折叠)：enterAlways = 上滑内容时 TopAppBar 跟手收起、下滑时立即跟手落回。
    // 顶栏收起后其背景(含状态栏区)一并让位,长列表页(64 组规则集管理/历史 IP)多得一屏可视高度。
    // 动的是 app 顶栏、系统状态栏原地不动 → 无 inset reflow / 无闪烁(官方推荐的「滚动腾空间」)。
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
        // 沉浸式：外层不再消费 inset 后,TopAppBar 自动查到 statusBars,其背景延伸进状态栏（顶栏融合）;
        // padding(含 TopAppBar 高 + 手势条)交给页内列表 contentPadding,内容可滚入系统栏后方。
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        content = content,
    )
}
