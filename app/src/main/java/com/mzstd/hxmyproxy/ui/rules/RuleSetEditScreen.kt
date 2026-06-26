package com.mzstd.hxmyproxy.ui.rules

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 规则集编辑：多行文本框（一行一个域名）整体编辑 + 导入/导出 txt。
 * [kind]="user"（自建集，id=UserRuleSet.id）或 "builtin"（内置集，id=groupId，保存为覆盖版、可恢复默认）。
 */
@Composable
fun RuleSetEditScreen(kind: String, id: String, ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val isBuiltin = kind == "builtin"
    val group = if (isBuiltin) RuleCatalog.byId(id) else null
    val userSet = if (!isBuiltin) ui.settings.userRuleSets.firstOrNull { it.id == id } else null
    val hasOverride = isBuiltin && ui.settings.ruleSetOverrides.containsKey(id)
    val title = group?.let { stringResource(it.titleRes) } ?: userSet?.name ?: ""

    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id, kind) {
        text = when {
            userSet != null -> userSet.domains.joinToString("\n")
            isBuiltin -> (ui.settings.ruleSetOverrides[id] ?: loadFullAsset(context, group?.assetPath ?: "")).joinToString("\n")
            else -> ""
        }
    }

    var pendingImport by remember { mutableStateOf<List<String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write((text ?: "").toByteArray()) } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
            pendingImport = parseDomains(content)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.ruleset_edit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val t = text
        if (t == null) {
            CircularProgressIndicator()
        } else {
            OutlinedTextField(
                value = t, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                label = { Text(stringResource(R.string.ruleset_domains_count, parseDomains(t).size)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val domains = parseDomains(t)
                    if (isBuiltin) viewModel.setGroupOverride(id, domains) else viewModel.setRuleSetDomains(id, domains)
                    onBack()
                }) { Text(stringResource(R.string.ruleset_save)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain")) }) { Text(stringResource(R.string.ruleset_import)) }
                OutlinedButton(onClick = { exportLauncher.launch("$title.txt") }) { Text(stringResource(R.string.ruleset_export)) }
            }
            if (isBuiltin && hasOverride) {
                TextButton(onClick = { viewModel.clearGroupOverride(id); onBack() }) {
                    Text(stringResource(R.string.ruleset_restore_default))
                }
            }
        }
    }

    val pending = pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.ruleset_import)) },
            text = { Text(stringResource(R.string.ruleset_import_how, pending.size)) },
            confirmButton = {
                TextButton(onClick = {
                    text = (parseDomains(text ?: "") + pending).distinct().joinToString("\n")
                    pendingImport = null
                }) { Text(stringResource(R.string.ruleset_import_append)) }
            },
            dismissButton = {
                TextButton(onClick = { text = pending.joinToString("\n"); pendingImport = null }) {
                    Text(stringResource(R.string.ruleset_import_replace))
                }
            },
        )
    }
}

/** 解析多行文本为域名后缀列表：去空/注释、小写、去 `*.` 前缀、要求含点、去重。 */
private fun parseDomains(text: String): List<String> =
    text.split("\n").map { it.trim().lowercase().removePrefix("*.") }
        .filter { it.isNotEmpty() && it[0] != '#' && it.contains('.') }
        .distinct()

/** 读内置集 assets 全部域名（仅对小集调用）。 */
private suspend fun loadFullAsset(context: Context, assetPath: String): List<String> = withContext(Dispatchers.IO) {
    if (assetPath.isEmpty()) return@withContext emptyList()
    runCatching {
        context.assets.open(assetPath).bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }
    }.getOrDefault(emptyList())
}
