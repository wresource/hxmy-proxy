package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold

/**
 * 快速名单管理页（拦截/放行共用一套 UI，reject 参数切换数据源——两侧界面完全一致，用户设计）：
 * 输入行 + 格式提示 + 从历史添加 + 完整列表增删。规则页卡片只留 2 条预览，全量管理在这。
 */
@Composable
fun QuickRulesDetailScreen(
    reject: Boolean,
    ui: MainUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val s = ui.settings
    val rules = (if (reject) s.userRejectRules else s.userDirectRules).sorted()
    val history by viewModel.domainHistory.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }
    fun add(rule: String) = if (reject) viewModel.addUserRejectRule(rule) else viewModel.addUserDirectRule(rule)
    fun remove(rule: String) = if (reject) viewModel.removeUserRejectRule(rule) else viewModel.removeUserDirectRule(rule)

    DetailScaffold(
        title = stringResource(if (reject) R.string.rules_reject_quick else R.string.rules_module_list),
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var input by remember { mutableStateOf("") }
            // 与 QuickRuleCard 同款：按钮 fillMaxHeight 与输入框等高，两处尺寸一致。
            Row(
                Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    // 与 QuickRuleCard 同款：placeholder 无 label 顶部预留，按钮与框同高对齐；圆角统一 16dp。
                    placeholder = { Text(stringResource(R.string.rules_add_domain)) },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { if (input.isNotBlank()) { add(input); input = "" } },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxHeight(),
                ) { Text(stringResource(R.string.rules_add)) }
            }
            Text(
                stringResource(R.string.rules_format_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { showHistory = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) { Text(stringResource(R.string.rules_add_from_history)) }
            if (rules.isEmpty()) {
                Text(
                    stringResource(R.string.rules_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rules.forEach { rule ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    OutlinedButton(onClick = { remove(rule) }, shape = MaterialTheme.shapes.large) {
                        Text(stringResource(R.string.rules_remove))
                    }
                }
            }
        }
    }
    if (showHistory) {
        HistoryAddDialog(
            history = history.filter { it !in rules }.sorted(),
            onAdd = { add(it) },
            onDismiss = { showHistory = false },
        )
    }
}
