package com.mzstd.hxmyproxy.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R

/**
 * 帮助页：可折叠分组（这是什么 / 常见问题 / 各端怎么连 / 故障排查 / 关于）。
 * 纯静态本地内容，不联网（符合「纯本地、不上云」）。文案通俗，尽量不用技术黑话。
 */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section(R.string.help_a_title, startOpen = true) { Para(R.string.help_a_body) }
            Section(R.string.help_b_title) { FaqList() }
            Section(R.string.help_c_title) { PlatformList() }
            Section(R.string.help_d_title) { Para(R.string.help_d_body) }
            Section(R.string.help_e_title) { Para(R.string.help_e_body) }
        }
    }
}

@Composable
private fun Section(titleRes: Int, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            }
            if (open) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun Para(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FaqList() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FAQ.forEach { (q, a) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(q), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(a), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PlatformList() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PLATFORMS.forEach { (name, steps) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(steps), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val FAQ = listOf(
    R.string.faq_q1 to R.string.faq_a1,
    R.string.faq_q2 to R.string.faq_a2,
    R.string.faq_q4 to R.string.faq_a4,
    R.string.faq_q5 to R.string.faq_a5,
    R.string.faq_q6 to R.string.faq_a6,
)

private val PLATFORMS = listOf(
    R.string.plat_win_title to R.string.plat_win_body,
    R.string.plat_mac_title to R.string.plat_mac_body,
    R.string.plat_ios_title to R.string.plat_ios_body,
    R.string.plat_android_title to R.string.plat_android_body,
    R.string.plat_browser_title to R.string.plat_browser_body,
)
