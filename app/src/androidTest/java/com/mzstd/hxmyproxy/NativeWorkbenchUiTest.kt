package com.mzstd.hxmyproxy

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.rules.RuleScope
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.monitor.*
import com.mzstd.hxmyproxy.ui.rules.HostRuleEdits
import com.mzstd.hxmyproxy.ui.run.ConnectionCard
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class NativeWorkbenchUiTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "native-ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun snapshot(): ActivitySnapshot {
        var time = 1000L
        val accounting = TrafficAccounting(maxDomains = 20, clock = { ++time })
        repeat(9) { i ->
            accounting.openConnection(InetAddress.getByName(if (i % 2 == 0) "192.0.2.23" else "192.0.2.41"), ProxyProtocol.HTTP)
                .apply { bindHost("d$i.example.org"); add((i + 1L) * 1024, (i + 1L) * 1024 * 1024) }
        }
        val s = accounting.snapshot()
        return ActivitySnapshot(session = 1, clients = s.clients, domains = s.topDomains,
            totalBytes = s.clients.sumOf { it.uploadBytes + it.downloadBytes }, capturedAt = time)
    }

    @Test fun activitySummaryShowsDevicesAndOnlyFiveDomainsBeforeOpeningAll() {
        val snapshot = snapshot()
        var opened = false
        val query = mutableStateOf(ActivityQuery())
        val language = mutableStateOf(AppLanguage.CHINESE)
        val dark = mutableStateOf(false)
        compose.setContent { ProvideAppLocale(language.value) { HxmyProxyTheme(darkTheme = dark.value) {
            Surface(Modifier.fillMaxSize().systemBarsPadding()) { ActivityContent(snapshot, query.value, true, onQuery = { query.value = it }, onPause = {},
                onAllDomains = { opened = true }, onAllClients = {}, onAddRule = {}, onChanges = {}, onDomain = {}, onClient = {}, actionForHost = { RuleAction.PROXY }) }
        } } }
        compose.onNodeWithText("192.0.2.23").assertIsDisplayed()
        compose.onNodeWithText("192.0.2.41").assertIsDisplayed()
        screenshot("activity-zh-light")
        val seen = linkedSetOf<String>()
        val domains = SemanticsMatcher("domain row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("domain_HTTP:") == true }
        repeat(12) {
            compose.onAllNodes(domains).fetchSemanticsNodes().forEach { node -> seen.add(node.config[SemanticsProperties.TestTag]) }
            compose.onNodeWithTag("activity_summary").performTouchInput { swipeUp(startY = centerY, endY = height * 0.12f, durationMillis = 180) }
        }
        assertEquals(5, seen.size)
        compose.onNodeWithTag("activity_summary").performScrollToNode(hasTestTag("all_domains"))
        compose.onNodeWithTag("all_domains").performClick()
        compose.runOnIdle { assertTrue(opened); language.value = AppLanguage.ENGLISH; dark.value = true }
        compose.onNodeWithTag("activity_summary").performScrollToIndex(0)
        compose.onNodeWithText("192.0.2.23").assertIsDisplayed()
        android.os.SystemClock.sleep(100)
        screenshot("activity-en-dark")
    }

    @Test fun protocolPortsCopyCurrentEndpointAndKeepConflictIndependent() {
        val iface = ShareInterface("wifi", "Wi-Fi", InterfaceType.WIFI, InetAddress.getByName("192.0.2.1"), 24, true, true, InterfaceStatus.UP)
        val state = mutableStateOf(MainUiState(share = ShareState(running = true, admissionEmpty = false, interfaces = listOf(iface),
            recommendedEntries = listOf(ProxyEntry("192.0.2.1", 8080, ProxyProtocol.HTTP, "wifi"), ProxyEntry("192.0.2.1", 1080, ProxyProtocol.SOCKS5, "wifi"), ProxyEntry("192.0.2.1", 8899, ProxyProtocol.PAC, "wifi")),
            diagnostics = DiagnosticsSummary(httpPortUp = true, socksPortUp = true, pacPortUp = true, httpBoundPort = 8080, socksBoundPort = 1080, pacBoundPort = 8899)), settings = ProxySettings(selectedInterfaceIds = setOf("wifi"))))
        var copied = ""
        var settingsPage = ""
        compose.setContent { ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme {
            Surface(Modifier.fillMaxSize().systemBarsPadding()) { Column(Modifier.fillMaxSize().padding(16.dp)) {
                ConnectionCard(state.value, null, {}, { copied = it }, {}, {}, { settingsPage = it }, {})
            } }
        } } }
        compose.onNodeWithTag("endpoint_HTTP").performClick()
        compose.runOnIdle { assertEquals("192.0.2.1:8080", copied) }
        compose.onNodeWithTag("home_inbound").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("interfaces", settingsPage) }
        compose.onNodeWithTag("home_ports").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("ports", settingsPage) }
        screenshot("connection-running")
        compose.runOnIdle { state.value = state.value.copy(share = state.value.share.copy(portBindErrors = setOf(ProxyProtocol.HTTP))) }
        compose.onNodeWithTag("endpoint_HTTP").assertIsNotEnabled()
        compose.onNodeWithTag("endpoint_SOCKS5").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("192.0.2.1:1080", copied) }
        screenshot("connection-conflict")
        compose.runOnIdle { state.value = state.value.copy(share = state.value.share.copy(running = false, portBindErrors = emptySet())) }
        compose.onNodeWithTag("endpoint_HTTP").assertIsNotEnabled()
        compose.onNodeWithTag("endpoint_SOCKS5").assertIsNotEnabled()
        compose.onNodeWithTag("endpoint_PAC").assertIsNotEnabled()
        compose.onNodeWithTag("home_inbound").assertIsEnabled()
        compose.onNodeWithTag("home_ports").assertIsEnabled()
        screenshot("connection-stopped")
    }

    @Test fun explicitCidrProxyRuleMatchesOnActualAndroid() {
        val s = HostRuleEdits.apply(ProxySettings(), "192.0.2.0/24", RuleAction.PROXY, RuleScope.EXACT, 2)
        assertEquals(setOf("192.0.2.0/24"), s.hostOverrides.keys)
        val matcher = RuleMatcher().apply { s.hostOverrides.keys.forEach(::add) }
        assertTrue(matcher.matches("192.0.2.42")); assertFalse(matcher.matches("198.51.100.1"))
    }

    @Test fun fiveDigitPortsRemainCompleteAtLargeFontOnNarrowScreen() {
        val scale = mutableStateOf(1f)
        val ui = MainUiState(settings = ProxySettings(httpPort = 65535, socksPort = 54321, pacPort = 12345))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale.value)) {
                ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme {
                    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                        Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            ConnectionCard(ui, null, {}, {}, {}, {}, {}, {})
                        }
                    }
                } }
            }
        }
        listOf(1f, 1.5f, 2f).forEach { fontScale ->
            compose.runOnIdle { scale.value = fontScale }
            listOf("HTTP" to "65535", "SOCKS5" to "54321", "PAC" to "12345").forEach { (protocol, port) ->
                val node = compose.onNodeWithTag("port_value_$protocol", useUnmergedTree = true)
                node.performScrollTo().assertIsDisplayed().assertTextEquals(port)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.size)
                assertEquals("$protocol at font scale $fontScale", 1, layouts.single().lineCount)
                val layout = layouts.single()
                // Paragraph width can retain the parent's constraints even when the text wraps
                // its intrinsic width. Check every rendered digit, not that unused paragraph space.
                assertEquals(port.length, layout.getLineEnd(0, visibleEnd = true))
                assertFalse(layout.isLineEllipsized(0))
                port.indices.forEach { index ->
                    val bounds = layout.getBoundingBox(index)
                    assertTrue("$protocol digit $index at scale $fontScale: $bounds in ${layout.size}",
                        bounds.left >= 0 && bounds.right <= layout.size.width && bounds.top >= 0 && bounds.bottom <= layout.size.height)
                }
            }
        }
        screenshot("connection-large-font")
    }
}
