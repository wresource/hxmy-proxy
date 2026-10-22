package com.mzstd.hxmyproxy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.monitor.*
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class UnknownActivityUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unknownSummaryDoesNotFlashKnownRoutesDuringRefreshAndKeepAlive() = exercise(full = false)
    @Test fun unknownFullListDoesNotFlashKnownRoutesDuringRefreshAndKeepAlive() = exercise(full = true)

    private fun exercise(full: Boolean) {
        val store = RequestActivityStore().apply { setEnabled(true) }
        val accounting = TrafficAccounting()
        val missing = DomainTraffic("missing.example", ProxyProtocol.HTTP, connections = 1)
        val input = MutableStateFlow(ActivityInput(ShareState(running = true, sessionStartedAtMs = 1,
            topDomains = listOf(missing)), true))
        val frozen = MutableStateFlow<ActivitySnapshot?>(null)
        val ticks = MutableStateFlow(0)
        val revisions = AtomicLong()
        val delivered = AtomicReference(ActivitySnapshot())
        val rendered = AtomicReference(ActivitySnapshot())
        val source = activitySnapshots(input, frozen, store::snapshotState, ticks.map { Unit }, revisions::incrementAndGet)
            .onEach { delivered.set(it) }
        val query = mutableStateOf(ActivityQuery(route = ActivityRoute.UNKNOWN))
        compose.setContent {
            // Read in this composition scope: a delegated read only in nested content would
            // update the child UI without re-running this parent SideEffect observation.
            val snapshot = source.collectAsState(ActivitySnapshot()).value
            SideEffect { rendered.set(snapshot) }
            ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (full) TopDomainsDetailContent(snapshot.domains, {}, { RuleAction.PROXY }, {},
                        requests = snapshot.requests, externalQuery = query.value, onQuery = { query.value = it })
                    else ActivityContent(snapshot, query.value, true, onQuery = { query.value = it }, onPause = {},
                        onAllDomains = {}, onAllClients = {}, onAddRule = {}, onChanges = {}, onDomain = {}, onClient = {},
                        actionForHost = { RuleAction.PROXY })
                }
            } }
        }
        fun publishCounters() {
            input.value = input.value.copy(share = input.value.share.copy(topDomains = accounting.snapshot().topDomains + missing))
        }
        fun step(action: () -> Unit) {
            val before = rendered.get().capturedAt
            compose.runOnIdle { action(); ticks.value++ }
            compose.waitUntil(5000) { rendered.get().capturedAt > before && rendered.get() == delivered.get() }
            assertEquals(delivered.get(), rendered.get())
        }
        fun missingOnly() {
            val rows = domainActivities(rendered.get(), query.value)
            assertEquals(listOf("missing.example"), rows.map { it.host })
            compose.onNodeWithTag(if (full) "all_domain_list" else "activity_summary")
                .performScrollToNode(hasTestTag("domain_HTTP:missing.example"))
            compose.onNodeWithTag("domain_HTTP:missing.example").assertIsDisplayed()
            compose.onNodeWithTag("domain_HTTP:known.example").assertDoesNotExist()
        }
        compose.waitUntil(5000) { rendered.get().capturedAt > 0 }
        missingOnly()
        var first: RequestActivityStore.Token? = null
        lateinit var connection: TrafficAccounting.ConnTracker
        step {
            first = store.open(1, "HTTP"); store.rule(first, "known.example", "PROXY", "DEFAULT")
            store.connected(first, "192.0.2.1")
            connection = accounting.openConnection(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), ProxyProtocol.HTTP)
            connection.bindHost("known.example")
            publishCounters()
        }
        missingOnly()
        step { store.closed(first, "http-keepalive", 1, 100) } // No counter change; socket remains open.
        missingOnly()
        var second: RequestActivityStore.Token? = null
        step { second = store.open(2, "HTTP"); store.rule(second, "known.example", "PROXY", "DEFAULT") }
        missingOnly()
        step { store.connected(second, "192.0.2.1"); store.closed(second, "http-keepalive", 1, 100) }
        missingOnly()
        step { connection.close() } // UI still has the older count until the next publication.
        missingOnly()
        step { publishCounters() }
        missingOnly()
        step { store.setEnabled(false); input.value = input.value.copy(recording = false) }
        assertEquals(setOf("missing.example", "known.example"), domainActivities(rendered.get(), query.value).map { it.host }.toSet())
        compose.onNodeWithTag(if (full) "all_domain_list" else "activity_summary")
            .performScrollToNode(hasTestTag("domain_HTTP:known.example"))
        compose.onAllNodesWithTag("domain_HTTP:known.example").assertCountEquals(1)
        step {
            store.setEnabled(true); input.value = input.value.copy(recording = true)
            val known = store.open(3, "HTTP"); store.rule(known, "known.example", "PROXY", "DEFAULT")
        }
        missingOnly()
    }
}
