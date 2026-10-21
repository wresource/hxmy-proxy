package com.mzstd.hxmyproxy.ui.monitor

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.log.LogArchive
import com.mzstd.hxmyproxy.core.log.LogExport
import com.mzstd.hxmyproxy.ui.LogsViewModel
import com.mzstd.hxmyproxy.ui.components.AppAlertDialog
import com.mzstd.hxmyproxy.ui.components.InteractionSheet
import com.mzstd.hxmyproxy.ui.rules.RuleDetailLayout
import com.mzstd.hxmyproxy.ui.rules.RuleNotice
import com.mzstd.hxmyproxy.ui.rules.RuleSearch
import com.mzstd.hxmyproxy.ui.rules.RuleTabs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LogLevelFilter(val labelRes: Int, private val floor: Int) {
    ALL(R.string.logs_filter_all, 0), WARN(R.string.logs_filter_warn, 1), ERROR(R.string.logs_filter_error, 2);
    fun accepts(level: String): Boolean = when (level) { "E" -> 2; "W" -> 1; "I", "D", "V" -> 0; else -> Int.MAX_VALUE } >= floor
}

/** Filter the complete retained snapshot first, then select a bounded cursor page. */
@Composable
fun LogsDetailScreen(onBack: () -> Unit, initialHost: String = "") {
    val vm: LogsViewModel = hiltViewModel()
    val state by vm.page.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by rememberSaveable { mutableStateOf(LogLevelFilter.ALL) }
    var search by rememberSaveable(initialHost) { mutableStateOf(initialHost) }
    var crashes by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val exportTitle = stringResource(R.string.export_logs)
    LaunchedEffect(level, search, crashes) { selected = null; vm.filter(level.name, search, crashes) }
    val entry = state.page?.entries?.firstOrNull { it.id == selected }
    if (entry != null) {
        BackHandler { selected = null }
        LogRecordScreen(vm, entry, { selected = null }); return
    }
    RuleDetailLayout(stringResource(R.string.error_logs), onBack, actions = {
        IconButton(onClick = { vm.reload() }, enabled = !state.loading) { Icon(painterResource(R.drawable.ic_b_refresh), stringResource(R.string.lg_refresh)) }
        IconButton(onClick = { more = true }) { Icon(painterResource(R.drawable.ic_ui_more), stringResource(R.string.ui_more_actions)) }
    }, footer = {
        val page = state.page
        if (page != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = vm::previous, enabled = state.number > 0 && !state.loading) { Text(stringResource(R.string.rl_previous)) }
            Text(stringResource(R.string.rl_page, if (page.entries.isEmpty()) 0 else state.number * 50 + 1,
                minOf(state.number * 50 + page.entries.size, page.total), page.total), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::next, enabled = page.hasMore && !state.loading) { Text(stringResource(R.string.rl_next)) }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("logs_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { RuleSearch(search, { search = it.take(256) }, R.string.ui_log_search) }
            item { RuleTabs(LogLevelFilter.entries.map { stringResource(it.labelRes) } + stringResource(R.string.lg_crashes),
                if (crashes) 3 else level.ordinal, { value -> crashes = value == 3; level = if (crashes) LogLevelFilter.ALL else LogLevelFilter.entries[value] }, "log_filters") }
            item { Text(stringResource(R.string.lg_snapshot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.page?.let { page -> item {
                Text(pluralStringResource(R.plurals.lg_counts, page.all, page.all, page.errors, page.warnings), style = MaterialTheme.typography.labelLarge)
                if (page.total != page.all) Text(stringResource(R.string.logs_filtered_count, page.total), style = MaterialTheme.typography.bodySmall)
            } }
            when {
                state.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error != null -> item { RuleNotice(stringResource(R.string.rl_operation_failed), true) { TextButton(onClick = vm::reload) { Text(stringResource(R.string.rl_retry)) } } }
                state.page?.entries.isNullOrEmpty() -> item { Text(stringResource(if (search.isNotBlank() || crashes || level != LogLevelFilter.ALL) R.string.logs_no_match else R.string.monitor_no_logs)) }
            }
            if (exportError != null) item { RuleNotice(exportError.orEmpty(), true) }
            items(state.page?.entries.orEmpty(), key = { it.id }) { row ->
                Surface(Modifier.fillMaxWidth().testTag("log_entry"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.clickable { selected = row.id }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.level, color = when (row.level) { "E" -> MaterialTheme.colorScheme.error; "W" -> com.mzstd.hxmyproxy.ui.theme.StatusColors.warn(); else -> MaterialTheme.colorScheme.onSurfaceVariant }, style = MaterialTheme.typography.labelLarge)
                            Text(row.timestamp, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(row.tag, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(row.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.log_show_more), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    if (more) InteractionSheet(stringResource(R.string.error_logs), { more = false }) {
        TextButton(onClick = {
            if (!exporting) scope.launch {
                exporting = true
                try {
                    val uri = withContext(Dispatchers.IO) { LogExport.buildShareUri(context.applicationContext) }
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, exportTitle)); more = false
                } catch (error: Exception) { exportError = error.message; more = false }
                finally { exporting = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !exporting) { Text(stringResource(R.string.lg_export_all)) }
        TextButton(onClick = { more = false; clearConfirm = true }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_logs)) }
    }
    if (clearConfirm) AppAlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text(stringResource(R.string.clear_logs)) },
        text = { Text(stringResource(R.string.ui_clear_logs_confirm)) },
        confirmButton = { TextButton(onClick = { clearConfirm = false; vm.clear { success ->
            if (success) Toast.makeText(context, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        } }) { Text(stringResource(R.string.clear_logs)) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun LogRecordScreen(vm: LogsViewModel, entry: LogArchive.Entry, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var offsets by rememberSaveable(entry.id) { mutableStateOf(listOf(0)) }
    var index by rememberSaveable(entry.id) { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val text by produceState<LogArchive.TextPage?>(null, entry.id, offsets[index]) {
        value = null; error = null
        try { value = vm.text(entry, offsets[index]) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "Unable to read record" }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try { withContext(Dispatchers.IO) { vm.exportEntry(entry, requireNotNull(context.contentResolver.openOutputStream(uri))) } }
            catch (failure: Exception) { error = failure.message }
        }
    }
    RuleDetailLayout(stringResource(R.string.lg_record), onBack, actions = {
        TextButton(onClick = { export.launch("hxmyproxy-log-entry.txt") }) { Text(stringResource(R.string.export_logs)) }
    }, footer = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { index-- }, enabled = index > 0) { Text(stringResource(R.string.rl_previous)) }
            Text(stringResource(R.string.lg_page, index + 1), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { val next = offsets[index] + (text?.text?.length ?: 0); offsets = offsets.take(index + 1) + next; index++ }, enabled = text?.hasMore == true) { Text(stringResource(R.string.rl_next)) }
        }
        OutlinedButton(onClick = { scope.launch {
            try { clipboard.setText(AnnotatedString(vm.copyText(entry))); Toast.makeText(context, R.string.lg_copied, Toast.LENGTH_SHORT).show() }
            catch (failure: Exception) { error = failure.message }
        } }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.lg_copy_record)) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (error != null) item { RuleNotice(error.orEmpty(), true) }
            else if (text == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(safeLogChunks(text?.text.orEmpty())) { chunk ->
                SelectionContainer { Text(chunk, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

private fun safeLogChunks(text: String): List<String> = buildList {
    var offset = 0
    while (offset < text.length) {
        var end = minOf(offset + 2048, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(offset, end)); offset = end
    }
}
