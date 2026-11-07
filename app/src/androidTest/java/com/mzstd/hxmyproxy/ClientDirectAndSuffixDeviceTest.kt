package com.mzstd.hxmyproxy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClientDirectAndSuffixDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo get() = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    private lateinit var main: MainViewModel

    private fun open(): ProxySettings {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("nav_rules").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("onboarding_skip").performClick()
        compose.runOnIdle { main = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        return runBlocking { repo.settings.first() }
    }
    private fun await(tag: String, enabled: Boolean = false) = compose.waitUntil(10000) {
        val nodes=compose.onAllNodesWithTag(tag)
        (if (enabled) nodes.filter(isEnabled()) else nodes).fetchSemanticsNodes().isNotEmpty()
    }
    private fun capture(name:String) {
        val i=InstrumentationRegistry.getInstrumentation()
        i.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(i.targetContext.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
    }

    @Test fun clientAddressModeCanBeRemovedFromTheRealClientScreenWithoutChangingOtherSettings() {
        val before=open()
        try {
            val key=ClientAddress.parse("2001:db8::10")!!
            runBlocking { repo.update { ProxySettings(language=AppLanguage.ENGLISH,directEgressChoice=DirectEgressChoice.WIFI,
                directClientIps=setOf("192.0.2.10",key),userDirectRules=listOf(RuleEntry("keep.example"))) } }
            compose.waitUntil(10000) { main.uiState.value.settings.directClientIps.size==2 && main.uiState.value.settings.language==AppLanguage.ENGLISH }
            compose.onAllNodesWithText("Activity").onFirst().performClick();await("all_clients")
            compose.onNodeWithTag("all_clients").performClick();await("client_direct_192.0.2.10",true)
            compose.onNodeWithTag("client_direct_192.0.2.10").assertIsOn()
            capture("client-direct-controls.png")
            compose.onNodeWithTag("client_direct_192.0.2.10").performClick()
            compose.waitUntil(10000) { main.uiState.value.settings.directClientIps==setOf(key) }
            compose.onNodeWithTag("client_direct_$key").assertIsOn().performClick()
            compose.waitUntil(10000) { main.uiState.value.settings.directClientIps.isEmpty() }
            val saved=runBlocking { repo.settings.first() }
            assertEquals(DirectEgressChoice.WIFI,saved.directEgressChoice)
            assertEquals(listOf("keep.example"),saved.userDirectRules.map { it.value })
            assertFalse(main.uiState.value.share.running)
        } finally { runBlocking { repo.update { before } } }
    }

    @Test fun suffixCanBeAddedAsDirectThroughTheProductionEditor() {
        val before=open()
        try {
            runBlocking { repo.update { ProxySettings(language=AppLanguage.ENGLISH) } }
            compose.waitUntil(10000) { main.uiState.value.settings.language==AppLanguage.ENGLISH }
            compose.onNodeWithTag("home_add_rule").performScrollTo().performClick();await("domain_rule_input")
            compose.onNodeWithTag("domain_rule_input").performTextInput(".cn")
            compose.onNodeWithText("Direct",useUnmergedTree=true).performScrollTo().performClick()
            await("domain_rule_save",true)
            compose.onNodeWithTag("rule_scope").assertDoesNotExist()
            capture("suffix-direct-editor.png")
            compose.onNodeWithTag("domain_rule_save").performClick();await("domain_rule_confirm_suffix")
            compose.onNodeWithTag("domain_rule_confirm_suffix").performClick()
            compose.waitUntil(10000) { main.uiState.value.settings.userDirectRules.any { it.value==".cn" } }
            val entry=runBlocking { repo.settings.first().userDirectRules.single() }
            assertEquals(".cn",entry.value)
            val matcher=RuleMatcher().apply { add(entry.value) }
            assertTrue(matcher.matches("a.b.example.com.cn"));assertFalse(matcher.matches("example.cn.com"))
        } finally { runBlocking { repo.update { before } } }
    }

    @Test fun backupRoundTripKeepsClientModesSuffixesAndRejectsAnInvalidClientAtomically() {
        val before=open()
        try {
            val chinese=RuleInput.normalize(".中国")!!
            assertEquals(".xn--fiqs8s",chinese)
            assertTrue(RuleMatcher().apply { add(chinese) }.matches("shop.中国"))
            runBlocking { repo.update { ProxySettings(directClientIps=setOf("192.0.2.10","2001:db8::10"),
                userDirectRules=listOf(RuleEntry(".cn"),RuleEntry(chinese))) } }
            val saved=runBlocking { repo.settings.first() }
            val backup=runBlocking { repo.exportJson() }
            runBlocking { repo.update { ProxySettings() };repo.importJson(backup) }
            val restored=runBlocking { repo.settings.first() }
            assertEquals(saved.directClientIps,restored.directClientIps)
            assertEquals(saved.userDirectRules.toList(),restored.userDirectRules.toList())
            val malformed=JSONObject(backup)
            malformed.getJSONObject("settings").getJSONObject("direct_client_ips").put("v",JSONArray().put("bad.example"))
            assertTrue(runCatching { runBlocking { repo.importJson(malformed.toString()) } }.isFailure)
            assertEquals(restored,runBlocking { repo.settings.first() })
        } finally { runBlocking { repo.update { before } } }
    }
}
