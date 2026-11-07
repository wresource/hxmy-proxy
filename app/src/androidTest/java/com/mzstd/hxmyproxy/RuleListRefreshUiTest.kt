package com.mzstd.hxmyproxy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.rules.RuleLibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A real source revision used to remove every row for 120 ms and clamp the scroll position. */
class RuleListRefreshUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun addingAnotherRuleKeepsTheVisibleRowAndScrollOffset() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("nav_rules").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("onboarding_skip").performClick()
        lateinit var library: RuleLibraryViewModel
        lateinit var main: MainViewModel
        compose.runOnIdle {
            library = ViewModelProvider(compose.activity)[RuleLibraryViewModel::class.java]
            main = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        }
        val before = runBlocking { library.library.settings.settings.first() }
        try {
            runBlocking { library.library.settings.update { ProxySettings(language = AppLanguage.ENGLISH,
                userDirectRules = (1..200).map { RuleEntry("row-${it.toString().padStart(3, '0')}.example.org") }) } }
            compose.waitUntil(10000) { main.uiState.value.settings.userDirectRules.size == 200 }
            compose.onNodeWithTag("nav_rules").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("rule_workbench").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("rule_workbench").performScrollToNode(hasTestTag("rule_manual_all"))
            compose.onNodeWithTag("rule_manual_all").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("rule_search").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("rule_search").performTextInput("row-")
            compose.onNodeWithTag("rule_search").performImeAction()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("rule_page_next").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val target = "row-030.example.org"
            compose.onNodeWithTag("manual_rules_page").performScrollToNode(hasText(target))
            compose.onNodeWithText(target).assertIsDisplayed()
            val top = compose.onNodeWithText(target).fetchSemanticsNode().boundsInRoot.top
            runBlocking { library.library.settings.update { it.copy(userDirectRules =
                it.userDirectRules + RuleEntry("row-201.example.org")) } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("201", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(target).assertIsDisplayed()
            assertEquals(top, compose.onNodeWithText(target).fetchSemanticsNode().boundsInRoot.top, 2f)
            // Removing an earlier entry changes every later ordinal, but not their display identity.
            runBlocking { library.library.settings.update { it.copy(userDirectRules = it.userDirectRules.drop(1)) } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("200", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(target).assertIsDisplayed()
            assertEquals(top, compose.onNodeWithText(target).fetchSemanticsNode().boundsInRoot.top, 2f)
        } finally {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.filesDir, "rule-list-refresh.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            runBlocking { library.library.settings.update { before } }
        }
    }
}
