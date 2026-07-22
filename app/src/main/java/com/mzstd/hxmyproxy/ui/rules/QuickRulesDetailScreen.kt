package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.DetailScaffold

/**
 * 快速名单管理页（拦截/放行共用一套 UI，reject 参数切换数据源——两侧界面完全一致，用户设计）：
 * 胶囊输入行（与规则页卡片同款，拦截粉/放行蓝）+ 等宽格式提示 + 从历史添加 + 完整列表增删
 * （条目带域名/IP 段类型徽章）。规则页卡片只留 2 条预览，全量管理在这。
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
    // 语义配色与规则页两卡一致：拦截=粉 tertiary / 放行=蓝 primary。
    val accent = if (reject) rejectAccent() else allowAccent()
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
            // 与规则页卡片同款胶囊输入行（前置加号 + 语义色填充「添加」钮）。
            RulePillInputRow(accent) { add(it) }
            RuleFormatHint()
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
            // 完整列表：发丝线分隔，条目=类型徽章 + 等宽地址 + 删除（与规则页预览行同款）。
            rules.forEach { rule ->
                HorizontalDivider(color = ruleHairline())
                RuleEntryRow(rule, accent) { remove(rule) }
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
