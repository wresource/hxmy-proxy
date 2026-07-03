package com.mzstd.hxmyproxy.ui.monitor

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

/** 历史代理入口详情页：完整列表（含上次使用日期）+ 复制/删除。 */
@Composable
fun HistoryDetailScreen(ui: MainUiState, mainViewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    DetailScaffold(title = stringResource(R.string.history_title), onBack = onBack) { padding ->
        if (ui.history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.monitor_no_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 按 IP 聚合：同一 IP 的 HTTP/SOCKS5/PAC 三条记录合成一行(原来每个 IP 三条太多——用户反馈)。
            // 复制=复制 IP,删除=删除该 IP 全部协议记录;时间取最近、可用性取任一可用。存储粒度不变。
            val grouped = ui.history.groupBy { it.entry.ip }.map { (ip, list) ->
                Triple(ip, list.maxOf { it.entry.lastUsedMillis }, list.any { it.available })
            }.sortedByDescending { it.second }
            // 沉浸式:DetailScaffold 的 padding(TopAppBar+手势条)进 contentPadding,内容可滚入系统栏后方。
            val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp + padding.calculateStartPadding(ld),
                    end = 16.dp + padding.calculateEndPadding(ld),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                items(grouped, key = { it.first }) { (ip, lastUsed, available) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ip, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                            Text(
                                dateFmt.format(Date(lastUsed)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!available) {
                                Text(
                                    stringResource(R.string.history_unavailable),
                                    color = com.mzstd.hxmyproxy.ui.theme.StatusColors.bad(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(ip))
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy)) }
                        TextButton(onClick = {
                            ui.history.filter { it.entry.ip == ip }.forEach { mainViewModel.removeHistoryEndpoint(it.entry) }
                        }) { Text(stringResource(R.string.delete)) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
