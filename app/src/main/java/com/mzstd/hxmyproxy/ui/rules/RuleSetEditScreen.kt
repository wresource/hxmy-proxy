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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mzstd.hxmyproxy.ui.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardHeader
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 规则集编辑（Bento 化）：沉底提示条 + 域名清单卡（实时计数头 + 等宽多行编辑框）+
 * 保存/导入/导出等宽按钮行。逻辑不变——多行文本整体编辑 + 导入/导出 txt。
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

    var pendingImport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write((text ?: "").toByteArray()) } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
            pendingImport = content
        }
    }

    DetailScaffold(title = title, onBack = onBack) { padding ->
        Column(
            // 沉浸式:inset padding 放 verticalScroll 之后,内容可滚入系统栏后方。
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 编辑说明:沉底信息条
            BentoCard(tier = CardTier.Sunken, contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_b_info),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.ruleset_edit_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val t = text
            if (t == null) {
                CircularProgressIndicator()
            } else {
                val parsed = com.mzstd.hxmyproxy.core.rules.RuleInput.parseLines(t)
                // 非法行保留在编辑器中，不能静默丢掉后保存一份残缺规则集。
                if (parsed.invalidLines.isNotEmpty()) Text(
                    stringResource(R.string.ruleset_invalid_lines, parsed.invalidLines.joinToString(", ")),
                    color = MaterialTheme.colorScheme.error,
                )
                // 域名清单卡:实时计数头 + 等宽多行编辑框
                BentoCard(tier = CardTier.Primary, contentPadding = 12.dp) {
                    CardHeader(
                        title = pluralStringResource(R.plurals.ruleset_domains_count, parseDomains(t).size, parseDomains(t).size),
                        icon = painterResource(R.drawable.ic_b_doc),
                    )
                    OutlinedTextField(
                        value = t, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                // 保存/导入/导出:等宽三按钮(中英文文案都不挤不断行)
                com.mzstd.hxmyproxy.ui.components.AdaptiveActions(
                    listOf(stringResource(R.string.ruleset_save), stringResource(R.string.ruleset_import), stringResource(R.string.ruleset_export)),
                    extraWidth = 32.dp,
                ) { button ->
                    Button(
                        enabled = parsed.invalidLines.isEmpty(),
                        onClick = {
                            val domains = parseDomains(t)
                            if (isBuiltin) viewModel.setGroupOverride(id, domains) else viewModel.setRuleSetDomains(id, domains)
                            onBack()
                        },
                        modifier = button,
                    ) { Text(stringResource(R.string.ruleset_save), maxLines = 1) }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("text/plain")) },
                        modifier = button,
                    ) { Text(stringResource(R.string.ruleset_import), maxLines = 1) }
                    OutlinedButton(
                        onClick = { exportLauncher.launch("$title.txt") },
                        modifier = button,
                    ) { Text(stringResource(R.string.ruleset_export), maxLines = 1) }
                }
                if (isBuiltin && hasOverride) {
                    TextButton(onClick = { viewModel.clearGroupOverride(id); onBack() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ruleset_restore_default))
                    }
                }
            }
        }
    }

    val pending = pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.ruleset_import)) },
            text = { Text(pluralStringResource(R.plurals.ruleset_import_how, parseDomains(pending).size, parseDomains(pending).size)) },
            confirmButton = {
                TextButton(onClick = {
                    text = text.orEmpty().trimEnd() + "\n" + pending
                    pendingImport = null
                }) { Text(stringResource(R.string.ruleset_import_append)) }
            },
            dismissButton = {
                TextButton(onClick = { text = pending; pendingImport = null }) {
                    Text(stringResource(R.string.ruleset_import_replace))
                }
            },
        )
    }
}

/** 与快速列表共用校验；保留 *. / = 和 IPv6/CIDR。非法行由界面显示并阻止保存。 */
private fun parseDomains(text: String): List<String> =
    com.mzstd.hxmyproxy.core.rules.RuleInput.parseLines(text).rules

/** 读内置集 assets 全部域名（仅对小集调用）。 */
private suspend fun loadFullAsset(context: Context, assetPath: String): List<String> = withContext(Dispatchers.IO) {
    if (assetPath.isEmpty()) return@withContext emptyList()
    runCatching {
        context.assets.open(assetPath).bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }
    }.getOrDefault(emptyList())
}
