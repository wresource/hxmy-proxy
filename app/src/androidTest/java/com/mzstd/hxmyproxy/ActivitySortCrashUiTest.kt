package com.mzstd.hxmyproxy

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.monitor.*
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduces live recomposition after a request arrives before its cumulative counter. */
@RunWith(AndroidJUnit4::class)
class ActivitySortCrashUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun trafficSortedSummarySurvivesMixedAndAgedCounters() = exercise(full = false)
    @Test fun trafficSortedFullListSurvivesMixedAndAgedCounters() = exercise(full = true)

    private fun exercise(full: Boolean) {
        val known = DomainTraffic("with-counter.example", ProxyProtocol.HTTP, downloadBytes = 3_000_000_000L)
        val record = RequestActivityRecord("1:1", 1, "HTTP", "only-record.example", 1, 2, "PROXY", "DEFAULT", RequestOutcome.FAILED)
        val state = mutableStateOf(ActivitySnapshot(domains = listOf(known)))
        val query = mutableStateOf(ActivityQuery(sort = DomainSort.TRAFFIC))
        compose.setContent { ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme {
            if (full) TopDomainsDetailContent(state.value.domains, {}, { RuleAction.PROXY }, {},
                requests = state.value.requests, externalQuery = query.value, onQuery = { query.value = it })
            else ActivityContent(state.value, query.value, true, onQuery = { query.value = it }, onPause = {},
                onAllDomains = {}, onAllClients = {}, onAddRule = {}, onChanges = {}, onDomain = {}, onClient = {}, actionForHost = { RuleAction.PROXY })
        } } }
        val list = if (full) "all_domain_list" else "activity_summary"
        fun visible(host: String) {
            compose.onNodeWithTag(list).performScrollToNode(hasTestTag("domain_HTTP:$host"))
            compose.onNodeWithTag("domain_HTTP:$host").assertIsDisplayed()
        }
        visible(known.host)
        compose.runOnIdle { state.value = state.value.copy(requests = listOf(record)) }
        visible(record.host)
        compose.onNodeWithTag("domain_HTTP:${record.host}").assert(hasText("—", substring = true))
        compose.runOnIdle { state.value = state.value.copy(domains = emptyList()) }
        visible(record.host)
        compose.runOnIdle { state.value = state.value.copy(domains = listOf(known, DomainTraffic(record.host, ProxyProtocol.HTTP, downloadBytes = 10))) }
        visible(known.host)
        visible(record.host)
    }
}
