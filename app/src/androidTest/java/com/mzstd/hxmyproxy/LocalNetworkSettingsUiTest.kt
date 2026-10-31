package com.mzstd.hxmyproxy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalNetworkSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun positiveSwitchPersistsBothDirectionsWithoutTogglingSharing() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("onboarding_skip").performClick()
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        val before = runBlocking { repo.settings.first() }
        lateinit var vm: MainViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        val running = vm.uiState.value.share.running
        try {
            runBlocking { repo.update { it.copy(language = AppLanguage.ENGLISH, blockPrivateLanEgress = true) } }
            compose.waitUntil(5000) { vm.uiState.value.settings.language == AppLanguage.ENGLISH && vm.uiState.value.settings.blockPrivateLanEgress }
            compose.onNodeWithTag("nav_settings").performClick()
            compose.onNodeWithText("Privacy", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("settings_privacy").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("settings_privacy").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("settings_local_network_targets").fetchSemanticsNodes().isNotEmpty() }
            fun toggle() = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("settings_local_network_targets")))
            compose.onNodeWithTag("settings_local_network_targets").performScrollTo()
            toggle().assertIsOff().performClick()
            compose.waitUntil(5000) { !vm.uiState.value.settings.blockPrivateLanEgress }
            toggle().assertIsOn()
            assertFalse(runBlocking { SettingsRepository(ApplicationProvider.getApplicationContext()).settings.first().blockPrivateLanEgress })
            toggle().performClick()
            compose.waitUntil(5000) { vm.uiState.value.settings.blockPrivateLanEgress }
            toggle().assertIsOff()
            assertEquals(running, vm.uiState.value.share.running)
        } finally { runBlocking { repo.update { before } } }
    }
}
