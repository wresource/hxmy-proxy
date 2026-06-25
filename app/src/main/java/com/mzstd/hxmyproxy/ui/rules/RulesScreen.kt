package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R

/**
 * 规则路由页（占位）。Phase 2b 落地三模块:IP/域名白名单、App/服务规则集、广告拦截。
 * 现阶段先把 tab 与三模块骨架立起来,后端规则引擎(Phase 2a)就绪后再填充开关与列表。
 */
@Composable
fun RulesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.rules_wip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(
            R.string.rules_module_list,
            R.string.rules_module_apps,
            R.string.rules_module_ads,
        ).forEach { titleRes ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(titleRes),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
