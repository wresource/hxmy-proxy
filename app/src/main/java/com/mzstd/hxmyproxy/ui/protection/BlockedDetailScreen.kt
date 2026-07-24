package com.mzstd.hxmyproxy.ui.protection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.HostOverrideDialog
import com.mzstd.hxmyproxy.ui.components.OverrideBadge
import com.mzstd.hxmyproxy.ui.components.RatioBar

/**
 * 拦截明细页（Bento 重设计）：本次共享会话被拦的域名/IP **完整**列表（按命中次数降序），
 * 行=排名 + 等宽域名（+覆盖徽章）+ ×次数 + tune，下衬粉 RatioBar（按榜首归一），供排查误封。
 * 点某一行 → [HostOverrideDialog] 设三态覆盖（走代理/直连/拦截，最高优先级），误封一键放行。
 */
@Composable
fun BlockedDetailScreen(ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    var editHost by remember { mutableStateOf<String?>(null) }
    DetailScaffold(title = stringResource(R.string.blocked_detail_title), onBack = onBack) { padding ->
        val list = ui.share.topBlockedDomains
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.monitor_no_blocked), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val ld = LocalLayoutDirection.current
            val maxCount = list.maxOf { it.count }.coerceAtLeast(1L)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp + padding.calculateStartPadding(ld),
                    end = 16.dp + padding.calculateEndPadding(ld),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                itemsIndexed(list, key = { _, b -> b.host }) { i, b ->
                    val ovr = ui.settings.hostOverrides[b.host]
                    // 行本就可点；tune 图标是可见的「可调整」提示（用户反馈：不然谁知道要点）。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { editHost = b.host }
                            .padding(vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${i + 1}",
                                modifier = Modifier.width(24.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    b.host,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (ovr != null) OverrideBadge(ovr)
                            }
                            Text(
                                "×${b.count}",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                                color = if (i == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                painterResource(R.drawable.ic_tune),
                                contentDescription = stringResource(R.string.override_adjust),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        // 比例条与域名列左对齐（缩进=排名宽 24 + 间距 8）。
                        RatioBar(
                            fraction = b.count.toFloat() / maxCount,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 32.dp),
                            height = 5.dp,
                        )
                    }
                }
            }
        }
    }
    editHost?.let { host ->
        HostOverrideDialog(
            host = host,
            current = ui.settings.hostOverrides[host],
            onSet = { action -> viewModel.setHostOverride(host, action); editHost = null },
            onClear = { viewModel.clearHostOverride(host); editHost = null },
            onDismiss = { editHost = null },
        )
    }
}
