package com.mzstd.hxmyproxy

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.components.*
import com.mzstd.hxmyproxy.ui.dashboard.QrSheet
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.monitor.*
import com.mzstd.hxmyproxy.ui.rules.HistoryAddDialog
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class InteractionSheetUiTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(100)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir,"interaction-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    private fun state(host: String = "www.gstatic.com", outcome: RequestOutcome = RequestOutcome.RELAYED): DomainDetailState {
        val action = if (outcome == RequestOutcome.BLOCKED) RuleAction.REJECT else RuleAction.PROXY
        val source = if (outcome == RequestOutcome.BLOCKED) "USER_BLOCK" else "DEFAULT"
        val records = (1..2).map {
            val blocked = outcome == RequestOutcome.BLOCKED && it == 2
            RequestActivityRecord("1:$it", it, "HTTP", host, 1792316894000 + it * 1000L, 1792316896000 + it * 1000L,
                if (blocked) "REJECT" else "PROXY", if (blocked) "USER_BLOCK" else "DEFAULT", if (blocked) outcome else RequestOutcome.RELAYED,
                uploadBytes = if (blocked) 0 else if (outcome == RequestOutcome.BLOCKED) 18329 else if (it == 1) 8000 else 10329,
                downloadBytes = if (blocked) 0 else if (outcome == RequestOutcome.BLOCKED) 780288 else if (it == 1) 400000 else 380288)
        }.reversed()
        return DomainDetailState(DomainActivity(host, ProxyProtocol.HTTP, DomainTraffic(host,ProxyProtocol.HTTP,18329,780288),records), action,source)
    }
    @Composable private fun Host(dark: Boolean, language: AppLanguage, fontScale: Float = 1f, body: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        val context = LocalContext.current
        SideEffect {
            val activity = context as? Activity
            activity?.window?.let { WindowCompat.getInsetsController(it,it.decorView).isAppearanceLightStatusBars = !dark }
        }
        CompositionLocalProvider(LocalDensity provides Density(density,fontScale)) {
            ProvideAppLocale(language) { HxmyProxyTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) { body() } } }
        }
    }
    @Test fun domainPanelIsCompactAndConnectionDetailsReturnWithoutStackingWindows() {
        val data = mutableStateOf(state())
        val dark = mutableStateOf(true)
        val language = mutableStateOf(AppLanguage.ENGLISH)
        var edit = false
        var applied: RuleAction? = null
        compose.setContent { Host(dark.value,language.value) {
            DomainDetailSheet(data.value,{}, { edit=true },{}, {},{ applied=it })
        } }
        compose.onAllNodesWithText("www.gstatic.com").assertCountEquals(1)
        val card = compose.onNodeWithTag("detail_traffic_card").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("sheet_body").fetchSemanticsNode().boundsInRoot
        val padding = with(compose.density) { 20.dp.roundToPx() }
        assertEquals("Traffic summary fills the body inside its two 20dp margins",body.width - padding * 2,card.width,1f)
        compose.onNodeWithTag("domain_primary_action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(edit) }
        capture("domain-en-dark")
        compose.onNodeWithTag("fold_requests-HTTP:www.gstatic.com").performScrollTo().performClick()
        compose.onNodeWithTag("request_1:1").performScrollTo().performClick()
        compose.onAllNodesWithTag("interaction_sheet").assertCountEquals(1)
        compose.onNodeWithTag("sheet_back").assertIsDisplayed()
        capture("connection-en-dark")
        compose.onNodeWithTag("sheet_back").performClick()
        compose.onNodeWithTag("request_1:1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("domain_primary_action").assertIsDisplayed()
        compose.runOnIdle { data.value=state(outcome=RequestOutcome.BLOCKED); dark.value=false;language.value=AppLanguage.CHINESE }
        compose.onNodeWithText("累计流量").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
        capture("blocked-zh-light")
        compose.onNodeWithTag("domain_primary_action").performClick()
        compose.runOnIdle { assertEquals(RuleAction.PROXY,applied) }
    }

    @Test fun longHostAndLargeTextKeepFullValueAndMainActionAvailable() {
        val host = ("very-long-domain-label-".repeat(2)+"a")+"."+("subdomain-".repeat(5))+"example.org"
        compose.setContent { Host(false,AppLanguage.ENGLISH,1.5f) { DomainDetailSheet(state(host),{}, {},{}, {},{}) } }
        compose.onNodeWithTag("sheet_close").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("sheet_title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1.5f, layouts.single().layoutInput.density.fontScale, 0.001f)
        compose.onNodeWithTag("domain_primary_action").assertIsDisplayed()
        compose.onAllNodesWithText(host).onLast().performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy").assertExists()
        capture("long-host-large-font")
    }

    @Test fun deviceRuleAndQrPanelsUseTheSameReadableLayout() {
        val panel=mutableStateOf(0)
        var chosen: RuleAction?=null
        val iface=ShareInterface("wifi","Wi-Fi",InterfaceType.WIFI,InetAddress.getByName("192.0.2.1"),24,true,true,InterfaceStatus.UP)
        val ui=MainUiState(share=ShareState(running=true,admissionEmpty=false,interfaces=listOf(iface),
            recommendedEntries=listOf(ProxyEntry("192.0.2.1",8899,ProxyProtocol.PAC,"wifi")),
            diagnostics=DiagnosticsSummary(pacPortUp=true,pacBoundPort=8899)),settings=ProxySettings(selectedInterfaceIds=setOf("wifi")))
        compose.setContent { Host(true,AppLanguage.ENGLISH) { when(panel.value) {
            0 -> ClientActivityDialog(ClientSession(InetAddress.getByName("192.0.2.23"),"wifi",3,18329,780288,1024,8192,1792316896000),false,{})
            1 -> HostOverrideDialog("www.gstatic.com",RuleAction.DIRECT,{chosen=it},{},{},vpnActive=true)
            2 -> RuleEditDialog(RuleEntry("*.www.gstatic.com",addedAt=1792316896000),{},{},{})
            else -> QrSheet(ui,{})
        } } }
        compose.onAllNodesWithText("192.0.2.23").assertCountEquals(1)
        capture("device-en-dark")
        compose.runOnIdle { panel.value=1 }
        compose.onNodeWithText("Via proxy").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(RuleAction.PROXY,chosen) }
        capture("override-en-dark")
        compose.runOnIdle { panel.value=2 }
        compose.onNodeWithTag("rule_edit_input").assertIsDisplayed()
        compose.onNodeWithTag("rule_scope").assertIsDisplayed()
        capture("rule-edit-en-dark")
        compose.runOnIdle { panel.value=3 }
        compose.onNodeWithText("http://192.0.2.1:8899/").assertIsDisplayed()
        capture("qr-en-dark")
    }

    @Test fun savedRuleHistoryRemainsScrollableForLargeExistingHistory() {
        val history = (1..1500).map { "domain-$it.example" }
        var chosen = ""
        compose.setContent { Host(false, AppLanguage.ENGLISH) { HistoryAddDialog(history, { chosen = it }, {}) } }
        compose.onNodeWithTag("rule_history_list").performScrollToIndex(history.lastIndex)
        compose.onNodeWithText(history.last()).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(history.last(), chosen) }
        compose.onNodeWithTag("sheet_close").assertIsDisplayed()
        capture("history-en-light")
    }
    @Test fun scopeMenuAndBroadConfirmationKeepTheChosenAppLanguage() {
        lateinit var suffix: String
        lateinit var save: String
        lateinit var confirmation: String
        compose.setContent { Host(false, AppLanguage.CHINESE) {
            val context = LocalContext.current
            suffix = context.getString(R.string.rules_scope_suffix)
            save = context.getString(R.string.ruleset_save)
            confirmation = context.getString(R.string.rules_scope_confirm_title)
            RuleEditDialog(RuleEntry("=example.com"), {}, {}, {})
        } }
        compose.onNodeWithTag("rule_scope").performScrollTo().performClick()
        compose.onNodeWithText(suffix).performClick()
        compose.onNodeWithText(save).performClick()
        compose.onNodeWithText(confirmation).assertIsDisplayed()
        capture("scope-confirmation-zh")
    }

}
