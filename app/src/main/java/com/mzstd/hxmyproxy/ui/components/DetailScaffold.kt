package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
    Scaffold(
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
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
        content = content,
    )
}
