package com.mzstd.hxmyproxy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.core.rules.HostRuleEdits
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWorkbenchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun awaitDestination(tag: String) {
        // Navigation and bottom sheets may compose on a later frame after an adaptive row scrolls.
        // Wait for the expected destination, without retrying clicks or changing its assertions.
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            compose.onRoot(useUnmergedTree = true).printToLog("NAVIGATION_FAILURE")
            throw failure
        }
    }
    private fun clickAboveNavigation(tag: String) {
        val action = compose.onNodeWithTag(tag).performScrollTo()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("nav_settings").fetchSemanticsNode().boundsInRoot
        if (bounds.left < navigation.right && bounds.right > navigation.left) {
            assertTrue("$tag must scroll above the navigation bar: $bounds vs $navigation", bounds.bottom <= navigation.top)
        }
        action.performClick()
    }
    @Test fun addExactRuleFromHomeAndRestoreItThroughRecentChanges() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("home_add_rule").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("onboarding_skip").performClick()
        lateinit var vm: MainViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        val host = "workbench-${System.currentTimeMillis()}.invalid"
        val before = HostRuleEdits.capture(vm.uiState.value.settings, host)
        val oldQuery = vm.activityQuery.value
        try {
            clickAboveNavigation("home_add_rule")
            awaitDestination("domain_rule_input")
            compose.onNodeWithTag("domain_rule_input").performTextInput("https://$host/path")
            compose.onNodeWithTag("domain_rule_input").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("https://$host/path")))
            compose.waitUntil(10000) { compose.onAllNodesWithTag("domain_rule_save").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("domain_rule_save").performClick()
            try {
                compose.waitUntil(10000) { vm.recentRuleChanges.value.any { it.host == host } && vm.uiState.value.settings.hostOverrides.keys.any { RuleScope.parse(it).second == host } }
            } catch (failure: Throwable) {
                compose.onRoot(useUnmergedTree = true).printToLog("RULE_SAVE_FAILURE")
                throw AssertionError("Rule $host not saved; changes=${vm.recentRuleChanges.value}; overrides=${vm.uiState.value.settings.hostOverrides}", failure)
            }
            val change = vm.recentRuleChanges.value.single { it.host == host }
            assertEquals(RuleAction.PROXY, change.action)
            assertEquals(RuleScope.EXACT, RuleScope.parse(change.rule).first)
            compose.onNodeWithTag("domain_search").assertTextEquals(host)
            compose.onNodeWithTag("activity_rule_changes").performScrollTo().performClick()
            compose.onNodeWithTag("revert_rule_${change.id}").performClick()
            compose.waitUntil(10000) { vm.recentRuleChanges.value.single { it.id == change.id }.reverted && HostRuleEdits.capture(vm.uiState.value.settings, host) == before }
        } finally {
            val active = vm.recentRuleChanges.value.firstOrNull { it.host == host && !it.reverted }
            if (active != null) {
                compose.runOnIdle { vm.revertDomainChange(active.id) {} }
                compose.waitUntil(5000) { HostRuleEdits.capture(vm.uiState.value.settings, host) == before }
            }
            compose.runOnIdle { vm.activityQuery.value = oldQuery }
        }
    }
    @Test fun firstScannedInterfaceIsVisibleAndItsSelectionPersistsAcrossDetailNavigation() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("onboarding_skip").performClick()
        lateinit var vm: MainViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        compose.waitUntil(15000) { vm.uiState.value.visibleInterfaces.isNotEmpty() }
        val first = vm.uiState.value.visibleInterfaces.first()
        val wasSelected = first.id in vm.uiState.value.settings.selectedInterfaceIds
        compose.onNodeWithTag("home_inbound").performScrollTo().performClick()
        compose.onNodeWithText(first.cidr).assertIsDisplayed()
        // The entire labeled row is a switch, not just the small trailing thumb.
        compose.onNodeWithText(first.cidr).performClick()
        try {
            compose.waitUntil(5000) { (first.id in vm.uiState.value.settings.selectedInterfaceIds) != wasSelected }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            clickAboveNavigation("home_ports")
            awaitDestination("port_input_HTTP")
            compose.onNodeWithTag("port_input_HTTP").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithText(first.cidr).assertIsDisplayed()
            compose.onNodeWithTag("port_input_HTTP").assertIsDisplayed()
            if (wasSelected) compose.onNodeWithTag("interface_${first.id}").assertIsOff() else compose.onNodeWithTag("interface_${first.id}").assertIsOn()
        } finally {
            compose.runOnIdle { vm.toggleInterface(first.id, wasSelected) }
        }
    }

    @Test fun portsAreEditableInSettingsWithoutAnotherPageAndPersistOnlyValidCompletedEdits() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("onboarding_skip").performClick()
        lateinit var vm: MainViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        val old = vm.uiState.value.settings
        val imeAnimating = java.util.concurrent.atomic.AtomicBoolean(false)
        compose.runOnIdle {
            androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(compose.activity.window.decorView,
                object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                        if (animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0) imeAnimating.set(true)
                    }
                    override fun onProgress(insets: androidx.core.view.WindowInsetsCompat, running: MutableList<androidx.core.view.WindowInsetsAnimationCompat>) = insets
                    override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                        if (animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime() != 0) imeAnimating.set(false)
                    }
                })
        }
        fun awaitEditorKeyboard() {
            compose.runOnIdle {
                androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(5000) {
                !imeAnimating.get() && androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
        }
        val candidates = (49152..49200).filter { it !in setOf(old.httpPort, old.socksPort, old.pacPort) }
        compose.onNodeWithTag("nav_settings").performClick()
        val http = compose.onNodeWithTag("port_input_HTTP")
        val socks = compose.onNodeWithTag("port_input_SOCKS5")
        try {
            val fields = listOf("HTTP", "SOCKS5", "PAC").map { protocol ->
                compose.onNodeWithTag("port_input_$protocol").performScrollTo().fetchSemanticsNode().boundsInRoot
            }
            fields.drop(1).forEach { bounds ->
                assertEquals("Protocol inputs must stay aligned at every font size", fields[0].left, bounds.left, 1f)
                assertEquals(fields[0].width, bounds.width, 1f)
            }
            http.performScrollTo().performTextReplacement(candidates[0].toString())
            awaitEditorKeyboard()
            compose.runOnIdle { assertEquals("Typing must not rebind the listener", old.httpPort, vm.uiState.value.settings.httpPort) }
            http.performImeAction()
            compose.waitUntil(5000) { vm.uiState.value.settings.httpPort == candidates[0] }
            // Range and duplicate values must not be persisted, even when focus leaves the field.
            listOf("1023", "65536", old.socksPort.toString()).forEach { invalid ->
                http.performScrollTo().performTextReplacement(invalid)
                awaitEditorKeyboard()
                socks.performScrollTo().performClick()
                socks.assertIsFocused()
                compose.onNodeWithTag("settings_body").assertHeightIsAtLeast(96.dp)
                compose.runOnIdle { assertEquals(candidates[0], vm.uiState.value.settings.httpPort) }
                http.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                    androidx.compose.ui.text.AnnotatedString(candidates[0].toString())))
            }
            http.performScrollTo().performTextReplacement(candidates[1].toString())
            awaitEditorKeyboard()
            socks.performScrollTo().performClick()
            compose.waitUntil(5000) { vm.uiState.value.settings.httpPort == candidates[1] }
            compose.onNodeWithTag("protocol_enabled_HTTP").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.uiState.value.settings.httpEnabled != old.httpEnabled }
            compose.onNodeWithTag("nav_run").performClick()
            clickAboveNavigation("home_ports")
            awaitDestination("port_input_HTTP")
            compose.onNodeWithTag("port_input_HTTP").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(candidates[1].toString())))
        } finally {
            compose.runOnIdle { androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(compose.activity.window.decorView, null) }
            compose.runOnIdle { vm.setHttpPort(old.httpPort); vm.setHttpEnabled(old.httpEnabled) }
            compose.waitUntil(5000) { vm.uiState.value.settings.httpPort == old.httpPort && vm.uiState.value.settings.httpEnabled == old.httpEnabled }
        }
    }
}
