package com.mzstd.hxmyproxy

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.stats.*
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.dashboard.EntryCard
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.monitor.EgressCard
import com.mzstd.hxmyproxy.ui.rules.RuleSetManagerScreen
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Removing separators must never remove the first data item or the only search result. */
@RunWith(AndroidJUnit4::class)
class FirstListItemVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ProvideAppLocale(AppLanguage.ENGLISH) {
                    HxmyProxyTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxSize().systemBarsPadding()) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun viewModel(): MainViewModel {
        lateinit var vm: MainViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        return vm
    }

    @Test fun customSetsKeepFirstRowSingleResultCountsAndEditAction() {
        val first = UserRuleSet("first", "First custom set", RuleAction.DIRECT, listOf("one.example.org"))
        val second = UserRuleSet("second", "Second custom set", RuleAction.REJECT, listOf("two.example.org", "three.example.org"))
        val ui = mutableStateOf(MainUiState(settings = ProxySettings(userRuleSets = listOf(first))))
        val vm = viewModel()
        var edited: Pair<String, String>? = null
        show { RuleSetManagerScreen(ui.value, vm, {}, { kind, id -> edited = kind to id }) }
        compose.onNodeWithText(first.name).assertIsDisplayed()
        compose.onNodeWithText("1 domain").assertIsDisplayed()
        compose.onNodeWithText("Direct (phone network)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.runOnIdle {
            assertEquals("user" to first.id, edited)
            ui.value = ui.value.copy(settings = ui.value.settings.copy(userRuleSets = listOf(first, second)))
        }
        compose.onNodeWithText(first.name).assertIsDisplayed()
        compose.onNodeWithText(second.name).assertIsDisplayed()
        compose.onNodeWithText("2 domains").assertIsDisplayed()
        compose.onNodeWithText("Block").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("Second custom")
        compose.onNodeWithText(first.name).assertDoesNotExist()
        compose.onNodeWithText(second.name).assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.runOnIdle { assertEquals("user" to second.id, edited) }
    }

    @Test fun builtinSearchKeepsItsOnlyMatchingRowAndEditAction() {
        val group = RuleCatalog.all.single { it.id == "app-wechat" }
        val vm = viewModel()
        var edited: Pair<String, String>? = null
        show { RuleSetManagerScreen(MainUiState(), vm, {}, { kind, id -> edited = kind to id }) }
        compose.onNode(hasSetTextAction()).performTextReplacement(group.id)
        compose.onNodeWithText("WeChat").assertIsDisplayed()
        compose.onNodeWithText("1 set").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.runOnIdle { assertEquals("builtin" to group.id, edited) }
    }

    @Test fun oneAndMultipleEgressRowsKeepAmountsIncludingFirstSlice() {
        val vpn = EgressSlice(EgressKind.VPN, 1024, 2048)
        val wifi = EgressSlice(EgressKind.WIFI, 1024, 4096)
        val stats = mutableStateOf(PeriodStats(StatsPeriod.TODAY, vpn.up, vpn.down, listOf(vpn), emptyList(), 1, 0, 20000))
        show { EgressCard(stats.value) }
        compose.onNodeWithText("VPN tunnel").assertIsDisplayed()
        compose.onNodeWithText("3.0 KB").assertIsDisplayed()
        compose.onNodeWithText("100.0%").assertIsDisplayed()
        compose.runOnIdle { stats.value = stats.value.copy(up = vpn.up + wifi.up, down = vpn.down + wifi.down, slices = listOf(vpn, wifi)) }
        compose.onNodeWithText("VPN tunnel").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("3.0 KB").assertIsDisplayed()
        compose.onNodeWithText("5.0 KB").assertIsDisplayed()
    }

    @Test fun legacyEntryCardKeepsFirstEndpointAndPacUrl() {
        val http = ProxyEntry("192.0.2.17", 8090, ProxyProtocol.HTTP, "fixture")
        val pac = ProxyEntry("192.0.2.17", 8899, ProxyProtocol.PAC, "fixture")
        val ui = mutableStateOf(MainUiState(share = ShareState(running = true, recommendedEntries = listOf(http))))
        show { EntryCard(ui.value) }
        compose.onNodeWithText(http.displayEndpoint).assertIsDisplayed()
        compose.runOnIdle { ui.value = ui.value.copy(share = ui.value.share.copy(recommendedEntries = listOf(http, pac))) }
        compose.onNodeWithText(http.displayEndpoint).assertIsDisplayed()
        compose.onNodeWithText(pac.displayEndpoint).assertIsDisplayed()
    }
}
