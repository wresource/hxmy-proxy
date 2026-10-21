package com.mzstd.hxmyproxy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.core.model.ThemeMode
import com.mzstd.hxmyproxy.data.repository.RuleImportRepository
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.rules.RuleLibraryViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RulesWorkbenchFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: RuleLibraryViewModel
    private lateinit var main: MainViewModel
    private fun fixture(settings: ProxySettings): ProxySettings {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        return settings.copy(language = if (args.getString("uiLanguage") == "en") AppLanguage.ENGLISH else AppLanguage.CHINESE,
            themeMode = if (args.getString("uiTheme") == "dark") ThemeMode.DARK else ThemeMode.LIGHT)
    }
    private fun capture(label: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val prefix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("capturePrefix") ?: return
        require(prefix.matches(Regex("[a-z0-9-]+")))
        val folder = java.io.File(instrumentation.targetContext.filesDir, "rules-qa").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(folder, "$prefix-$label.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun await(tag: String, enabled: Boolean = false) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).let { if (enabled) it.filter(isEnabled()) else it }.fetchSemanticsNodes().isNotEmpty() }
    }
    private fun open(): ProxySettings {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("nav_rules").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("onboarding_skip").performClick()
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity)[RuleLibraryViewModel::class.java]
            main = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        }
        return runBlocking { vm.library.settings.settings.first() }
    }
    private fun restore(settings: ProxySettings) = runBlocking { vm.library.settings.update { settings } }

    @Test fun broadConflictCanBePausedAndUndoneWithoutLosingTheDraft() {
        val before = open()
        try {
            runBlocking { vm.library.settings.update { fixture(ProxySettings(userRejectRules = listOf(RuleEntry("example.org")))) } }
            compose.waitUntil(10_000) { main.uiState.value.settings.userRejectRules.singleOrNull()?.value == "example.org" }
            compose.onNodeWithTag("nav_rules").performClick(); await("rule_add")
            compose.onNodeWithTag("rule_add").performScrollTo().performClick(); await("domain_rule_input")
            compose.onNodeWithTag("domain_rule_input").performTextInput("www.example.org")
            await("related_toggle_reject_example.org", true)
            compose.onNodeWithTag("related_toggle_reject_example.org").performScrollTo()
            capture("conflict")
            compose.onNodeWithTag("related_toggle_reject_example.org").performScrollTo().performClick()
            compose.waitUntil(10_000) { main.uiState.value.settings.userRejectRules.singleOrNull()?.enabled == false }
            await("related_undo", true)
            compose.onNodeWithTag("related_undo").performScrollTo().performClick()
            compose.waitUntil(10_000) { main.uiState.value.settings.userRejectRules.singleOrNull()?.enabled == true }
            compose.onNodeWithTag("domain_rule_input").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("www.example.org")))
            await("domain_rule_save", true)
        } finally { restore(before) }
    }

    @Test fun manualSummaryLeadsToRealPagesAndFindsTheLastRule() {
        val before = open()
        try {
            runBlocking { vm.library.settings.update { fixture(ProxySettings(userDirectRules = (1..120).map { RuleEntry("row-${it.toString().padStart(3, '0')}.example.org") })) } }
            compose.onNodeWithTag("nav_rules").performClick(); await("rule_workbench")
            capture("rules-home")
            compose.onNodeWithTag("rule_workbench").performScrollToNode(hasTestTag("rule_manual_all"))
            await("rule_manual_all")
            compose.onNodeWithTag("rule_manual_all").performScrollTo().performClick(); await("manual_rules_page")
            await("rule_page_next", true)
            compose.onNodeWithTag("rule_page_next").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("51–100", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("rule_page_next").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("101–120", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("rule_page_next").assertIsNotEnabled()
            capture("manual-last-page")
            compose.onNodeWithTag("rule_search").performScrollTo().performTextInput("row-120.")
            compose.onNodeWithTag("rule_search").performImeAction()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("1–1 ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("manual_rules_page").performScrollToNode(hasText("row-120.example.org"))
            compose.onNodeWithText("row-120.example.org").performScrollTo().assertIsDisplayed()
            capture("search-last-rule")
            assertEquals(120, runBlocking { vm.library.settings.settings.first() }.userDirectRules.size)
        } finally { restore(before) }
    }

    @Test fun pasteRangeReviewInvalidLinesSaveAndUndoThroughProductionUi() {
        val before = open()
        try {
            runBlocking { vm.library.settings.update { fixture(ProxySettings()) } }
            compose.onNodeWithTag("nav_rules").performClick(); await("rule_import")
            compose.onNodeWithTag("rule_import").performScrollTo().performClick(); await("import_paste")
            capture("import-start")
            compose.onNodeWithTag("import_paste").performScrollTo().performClick(); await("paste_rules_input")
            compose.onNodeWithTag("paste_rules_input").performTextInput("1.2.3.4 1.2.3.255\n1.2.3.4-1.2.3.255\n999.1.1.1\nexample.org")
            compose.onNodeWithTag("check_pasted_rules").performClick()
            compose.waitUntil(15_000) { vm.importState.value.job?.phase == RuleImportRepository.Phase.READY && !vm.importState.value.checking }
            assertEquals(2, vm.importState.value.job!!.valid); assertEquals(1, vm.importState.value.job!!.invalid)
            assertEquals(1, vm.importState.value.job!!.duplicates)
            capture("import-review")
            compose.onNodeWithTag("import_next").assertIsNotEnabled()
            compose.onNodeWithTag("import_skip_invalid").performClick(); await("import_next", true)
            compose.onNodeWithTag("import_next").performClick(); await("import_set_name")
            compose.onNodeWithTag("import_set_name").performScrollTo().performTextReplacement("UI range import")
            capture("import-target-keyboard")
            await("import_save", true); compose.onNodeWithTag("import_save").performClick()
            compose.waitUntil(15_000) { vm.importState.value.job?.phase == RuleImportRepository.Phase.DONE }
            val job = vm.importState.value.job!!
            capture("import-result")
            assertEquals(2, job.saved)
            assertEquals(listOf("1.2.3.4-1.2.3.255", "example.org"), runBlocking { vm.library.settings.settings.first() }.userRuleSets.single().domains)
            runBlocking { assertTrue(vm.library.settings.revertLibraryChange(job.undoId!!)) }
            assertTrue(runBlocking { vm.library.settings.settings.first() }.userRuleSets.isEmpty())
        } finally { restore(before) }
    }
}
