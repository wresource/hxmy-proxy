package com.mzstd.hxmyproxy.ui.monitor

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.StatusDot

/**
 * 日志级别下限筛选：按「这一级**及以上**」保留。
 * 顺序即严重度，[accepts] 用序号比较，未知级别一律放行（宁可多显示也不吞日志）。
 */
enum class LogLevelFilter(val labelRes: Int, private val floor: Int) {
    ALL(R.string.logs_filter_all, 0),
    WARN(R.string.logs_filter_warn, 1),
    ERROR(R.string.logs_filter_error, 2);

    fun accepts(level: String): Boolean = severity(level) >= floor

    private fun severity(level: String): Int = when (level) {
        "E" -> 2
        "W" -> 1
        "I", "D", "V" -> 0
        else -> Int.MAX_VALUE   // 未知级别不被任何筛选吞掉
    }
}

/** 错误日志详情页（Bento 轻改）：每条一张卡（等级圆点+等宽字），默认 2 行、可展开看堆栈；顶部显示总条数。 */
@Composable
fun LogsDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: LogsViewModel = hiltViewModel()
    val all by vm.entries.collectAsStateWithLifecycle()
    // 级别下限筛选。默认 ALL：不擅自替用户隐藏内容；但 I 级(心跳/规则判定)条数占绝对多数，
    // 排障时一键收到 W 及以上能立刻把噪音压掉。
    var minLevel by rememberSaveable { mutableStateOf(LogLevelFilter.ALL) }
    val entries = remember(all, minLevel) { all.filter { minLevel.accepts(it.level) } }

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
        if (all.isEmpty()) {
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 级别筛选 + 计数：计数按**全量**算(E/W 数量不随筛选变),筛选态另行标注,
                // 否则「切到 E 之后 W 计数变 0」会让人以为日志丢了。
                item(key = "__filter") {
                    val err = all.count { it.level == "E" }
                    val warn = all.count { it.level == "W" }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LogLevelFilter.entries.forEach { f ->
                                val on = minLevel == f
                                Text(
                                    stringResource(f.labelRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (on) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        )
                                        .clickable { minLevel = f }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                        Text(
                            pluralStringResource(R.plurals.count_entries, all.size, all.size) +
                                (if (err + warn > 0) "  ·  E $err · W $warn" else "") +
                                (if (minLevel != LogLevelFilter.ALL) {
                                    "  ·  " + stringResource(R.string.logs_filtered_count, entries.size)
                                } else ""),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "__empty") {
                        Text(
                            stringResource(R.string.logs_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(entries) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

/** 单条日志卡：折叠态显示元信息一行 + 消息最多 2 行；有堆栈则整卡可点开看全文。 */
@Composable
private fun LogEntryRow(entry: LogEntry) {
    var expanded by remember { mutableStateOf(false) }
    // 消息本身是否被 2 行截断（onTextLayout 回调给出真实溢出，不靠猜字数）。
    // 1.17.0 起日志是结构化单行 `evt=… k=v`，长行常被截断却没有堆栈续行，
    // 若只按 hasMore(有无堆栈) 判断，这类条目点了没反应 —— 用户报的「显示不了详情」就是它。
    var messageOverflows by remember(entry) { mutableStateOf(false) }
    val canExpand = entry.hasMore || messageOverflows
    BentoCard(
        Modifier.fillMaxWidth(),
        tier = CardTier.Default,
        onClick = if (canExpand) ({ expanded = !expanded }) else null,
        contentPadding = 12.dp,
        spacing = 3.dp,
    ) {
        // 元信息行：等级圆点 + 等级字母 + 时间戳 + tag（等宽小字）。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(levelColor(entry.level), 8.dp)
            Text(
                entry.level,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = levelColor(entry.level),
            )
            Text(
                entry.timestamp,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 消息：折叠 2 行、展开全显。
        Text(
            entry.message,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) messageOverflows = it.hasVisualOverflow },
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
