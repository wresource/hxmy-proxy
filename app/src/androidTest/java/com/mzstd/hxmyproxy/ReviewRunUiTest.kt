package com.mzstd.hxmyproxy

import android.app.ActivityManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.locale.localizedFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on authorized test devices; grant the app's runtime permissions before instrumentation. */
@RunWith(AndroidJUnit4::class)
class ReviewRunUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    private fun foregroundServiceRunning():Boolean =
        compose.activity.getSystemService(ActivityManager::class.java)
            .getRunningServices(100).any { it.service.className == ProxyForegroundService::class.java.name && it.foreground }

    private fun sharingOn() = compose.onAllNodesWithTag("sharing_primary_action").fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    @Test fun visiblePrimaryActionStartsStopsAndCancelsConfirmationAcrossTwoSessions() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("nav_run").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()
        }
        if(compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("onboarding_skip").performClick()
        }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("nav_run").fetchSemanticsNodes().isNotEmpty() }
        lateinit var vm: MainViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        compose.onNodeWithTag("nav_run").performClick()
        ProxyForegroundService.stop(compose.activity)
        compose.waitUntil(10000) { !sharingOn() && !foregroundServiceRunning() }
        try {
            repeat(2) {
                compose.onNodeWithTag("sharing_primary_action").performClick()
                // The switch is also On while starting, and the FGS exists before rule loading.
                // Clicking then correctly cancels preparation instead of opening the stop dialog.
                compose.waitUntil(15000) {
                    val state = vm.uiState.value.share
                    state.running && !state.starting && sharingOn() && foregroundServiceRunning()
                }
                val actionBounds=compose.onNodeWithTag("sharing_primary_action").fetchSemanticsNode().boundsInRoot
                val navBounds=compose.onNodeWithTag("nav_rules").fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue("button=$actionBounds must be above navigation=$navBounds",
                    actionBounds.height > 0 && actionBounds.bottom <= navBounds.top)
                compose.onNodeWithTag("sharing_primary_action").performClick()
                if (it == 0) {
                    compose.onNodeWithText(compose.activity.localizedFor(vm.uiState.value.settings.language).getString(R.string.cancel)).performClick()
                    org.junit.Assert.assertTrue(foregroundServiceRunning())
                    compose.onNodeWithTag("sharing_primary_action").performClick()
                }
                compose.onNodeWithTag("confirm_stop_sharing").performClick()
                compose.waitUntil(10000) { !sharingOn() && !foregroundServiceRunning() }
            }
        } finally { ProxyForegroundService.stop(compose.activity) }
    }
}
