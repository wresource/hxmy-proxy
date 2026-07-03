package com.mzstd.hxmyproxy.ui.monitor

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.log.LogExport
import com.mzstd.hxmyproxy.ui.LogEntry
import com.mzstd.hxmyproxy.ui.LogsViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold

/** 错误日志详情页：按条目（最近在前）折叠展示——每条默认 2 行、可展开看堆栈；顶部显示总条数。 */
@Composable
fun LogsDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: LogsViewModel = hiltViewModel()
    val entries by vm.entries.collectAsStateWithLifecycle()

    DetailScaffold(
        title = stringResource(R.string.error_logs),
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                runCatching {
                    val uri = LogExport.buildShareUri(context)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, context.getString(R.string.export_logs)))
                }.onFailure {
                    Toast.makeText(context, it.message ?: "export failed", Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.export_logs)) }
            TextButton(onClick = {
                vm.clear()
                Toast.makeText(context, context.getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.clear_logs)) }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.monitor_no_logs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
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
                // 总条数（+ 错误/警告分项）：一眼看清数量。
                item(key = "__count") {
                    val err = entries.count { it.level == "E" }
                    val warn = entries.count { it.level == "W" }
                    Text(
                        stringResource(R.string.log_count, entries.size) +
                            if (err + warn > 0) "  ·  E $err · W $warn" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(entries) { entry ->
                    LogEntryRow(entry)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

/** 单条日志：折叠态显示元信息一行 + 消息最多 2 行；有堆栈则可点开看全文。 */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = entry.hasMore
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 元信息行：等级色点 + 时间戳 + tag。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.level,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = levelColor(entry.level),
            )
            Text(entry.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 消息：折叠 2 行、展开全显。
        Text(
            entry.message,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 展开后附堆栈；折叠时给出可展开提示。
        if (expanded && entry.detail != null) {
            Text(
                entry.detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canExpand) {
            Text(
                stringResource(if (expanded) R.string.log_show_less else R.string.log_show_more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 等级配色：E=错误红、W=警告橙、I=中性。 */
@Composable
private fun levelColor(level: String): Color = when (level) {
    "E" -> com.mzstd.hxmyproxy.ui.theme.StatusColors.bad()
    "W" -> com.mzstd.hxmyproxy.ui.theme.StatusColors.warn()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
