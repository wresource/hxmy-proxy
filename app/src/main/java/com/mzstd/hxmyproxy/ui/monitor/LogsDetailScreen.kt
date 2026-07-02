package com.mzstd.hxmyproxy.ui.monitor

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.log.LogExport
import com.mzstd.hxmyproxy.ui.LogsViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold

/** 错误日志详情页：完整日志行 + 导出/清空。 */
@Composable
fun LogsDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: LogsViewModel = hiltViewModel()
    val logs by vm.logs.collectAsStateWithLifecycle()

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
        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.monitor_no_logs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // 沉浸式:DetailScaffold 的 padding(TopAppBar+手势条)进 contentPadding,内容可滚入系统栏后方。
            val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp + padding.calculateStartPadding(ld),
                    end = 12.dp + padding.calculateEndPadding(ld),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
            ) {
                items(logs) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
