package com.mzstd.hxmyproxy

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.RequestActivityRecord
import com.mzstd.hxmyproxy.core.proxy.RequestOutcome
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.ui.monitor.*
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ActivityFilterUiTest {
    @get:Rule val compose = createComposeRule()

    private fun record(id: Int, host: String, action: String, outcome: RequestOutcome) =
        RequestActivityRecord("1:$id", id, "HTTP", host, id * 1000L, id * 1000L, action, "DEFAULT", outcome)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(i.targetContext.filesDir, "activity-filter-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    /** Verify actual glyph centers (not just the Text node width) and complete visible lines. */
    private fun centered(parent: String, label: String) {
        val frame = compose.onNodeWithTag(parent).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag(label, useUnmergedTree = true)
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            val center = bounds.left + (layout.getLineLeft(line) + layout.getLineRight(line)) / 2
            assertTrue("$label line $line at $center, frame $frame", abs(center - frame.center.x) <= 2f)
            assertTrue("$label clipped vertically", bounds.top + layout.getLineBottom(line) <= frame.bottom + 1f)
        }
    }

    private fun controls(language: AppLanguage, fontScale: Float) {
        val query = mutableStateOf(ActivityQuery())
        var physicalDensity = 1f
        compose.setContent {
            physicalDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(physicalDensity, fontScale)) {
                ProvideAppLocale(language) { HxmyProxyTheme(darkTheme = language == AppLanguage.ENGLISH) {
                    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                        // Surface propagates its minimum width: use a Box so the fixture is truly 320dp.
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.width(320.dp).testTag("filter_host").verticalScroll(rememberScrollState()).padding(16.dp)) {
                                DomainMonitorControls(query.value, { query.value = it }, {}, {})
                            }
                        }
                    }
                } }
            }
        }
        assertEquals(320f * physicalDensity, compose.onNodeWithTag("filter_host").fetchSemanticsNode().boundsInRoot.width, 1f)
        val choices = listOf("activity_filter" to ActivityFilter.entries.map { it.name },
            "activity_route" to ActivityRoute.entries.map { it.name }, "activity_sort" to DomainSort.entries.map { it.name })
        choices.forEach { (tag, options) ->
            options.forEach { option ->
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
                val optionTag = "${tag}_option_$option"
                compose.onNodeWithTag(optionTag).performScrollTo().assertIsDisplayed()
                centered(optionTag, "${optionTag}_label")
                compose.onNodeWithTag(optionTag).performClick()
                centered(tag, "${tag}_label")
                assertTrue(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height >= 48 * physicalDensity - 1)
            }
        }
        compose.runOnIdle {
            assertEquals(ActivityFilter.UNKNOWN, query.value.filter)
            assertEquals(ActivityRoute.UNKNOWN, query.value.route)
            assertEquals(DomainSort.TRAFFIC, query.value.sort)
        }
        screenshot(if (language == AppLanguage.ENGLISH) "en-320-large" else "zh-320")
    }

    @Test fun englishLargeTextButtonsAndAllMenuChoicesAreCentered() = controls(AppLanguage.ENGLISH, 1.5f)
    @Test fun chineseButtonsAndAllMenuChoicesAreCentered() = controls(AppLanguage.CHINESE, 1f)

    @Test fun summaryAndFullListKeepBothFiltersAndNeverSplitRepeatedVisits() {
        val full = mutableStateOf(false)
        val query = mutableStateOf(ActivityQuery())
        var selected: DomainActivity? = null
        val snapshot = ActivitySnapshot(requests = listOf(
            record(1, "mixed.example", "DIRECT", RequestOutcome.RELAYED),
            record(2, "mixed.example", "PROXY", RequestOutcome.FAILED),
            record(3, "correct.example", "DIRECT", RequestOutcome.FAILED),
            record(4, "correct.example", "DIRECT", RequestOutcome.FAILED)),
            domains = listOf(DomainTraffic("mixed.example", ProxyProtocol.HTTP, 123, 456),
                DomainTraffic("correct.example", ProxyProtocol.HTTP, 10, 20)))
        compose.setContent { ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme {
            Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                if (full.value) TopDomainsDetailContent(snapshot.domains, { full.value = false }, { RuleAction.PROXY }, {},
                    requests = snapshot.requests, externalQuery = query.value, onQuery = { query.value = it }, onSelect = { selected = it })
                else ActivityContent(snapshot, query.value, true, onQuery = { query.value = it }, onPause = {},
                    onAllDomains = { full.value = true }, onAllClients = {}, onAddRule = {}, onChanges = {}, onDomain = {}, onClient = {},
                    actionForHost = { RuleAction.PROXY })
            }
        } } }
        fun choose(tag: String, value: String) {
            val list = if (full.value) "all_domain_list" else "activity_summary"
            compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag("${tag}_option_$value").performScrollTo().performClick()
        }
        choose("activity_route", "DIRECT")
        choose("activity_filter", "FAILED")
        compose.onNodeWithTag("activity_summary").performScrollToNode(hasTestTag("domain_HTTP:correct.example"))
        compose.onAllNodesWithTag("domain_HTTP:correct.example").assertCountEquals(1)
        compose.onNodeWithTag("domain_HTTP:mixed.example").assertDoesNotExist()
        compose.onNodeWithTag("activity_summary").performScrollToNode(hasTestTag("all_domains"))
        compose.onNodeWithTag("all_domains").performClick()
        compose.runOnIdle { assertTrue(full.value); assertEquals(ActivityRoute.DIRECT, query.value.route); assertEquals(ActivityFilter.FAILED, query.value.filter) }
        choose("activity_sort", "TRAFFIC")
        compose.runOnIdle { assertEquals(ActivityRoute.DIRECT, query.value.route); assertEquals(ActivityFilter.FAILED, query.value.filter) }
        choose("activity_filter", "ENDED")
        compose.onNodeWithTag("all_domain_list").performScrollToNode(hasTestTag("domain_HTTP:mixed.example"))
        compose.onAllNodesWithTag("domain_HTTP:mixed.example").assertCountEquals(1)
        compose.onNodeWithTag("domain_HTTP:mixed.example").performClick()
        compose.runOnIdle {
            assertEquals(1, selected?.displayedRequest?.requestId)
            assertEquals(2, selected?.latest?.requestId)
            assertEquals(579L, selected?.bytes)
        }
        screenshot("combined-full")
    }

    @Test fun aMatchingOldVisitDoesNotReplaceCurrentRuleOrCumulativeTrafficInDetails() {
        val snapshot = ActivitySnapshot(domains = listOf(DomainTraffic("mixed.example", ProxyProtocol.HTTP, 123, 456)),
            requests = listOf(record(1, "mixed.example", "DIRECT", RequestOutcome.RELAYED),
                record(2, "mixed.example", "PROXY", RequestOutcome.FAILED)))
        val row = domainActivities(snapshot, ActivityQuery(filter = ActivityFilter.ENDED, route = ActivityRoute.DIRECT)).single()
        compose.setContent { ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme {
            DomainDetailSheet(DomainDetailState(row, RuleAction.PROXY, "DEFAULT"), {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("Closed with traffic").assertExists()
        compose.onNodeWithText("Rule used: Direct").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Default proxy route").assertExists()
        compose.onNodeWithText("579 B").assertExists()
        compose.onNodeWithText("Recent connections · 2").assertExists()
    }
}
