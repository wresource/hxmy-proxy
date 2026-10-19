package com.mzstd.hxmyproxy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkbenchAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeAndInboundSettingsPassPlatformAccessibilityChecks() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("onboarding_skip").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("onboarding_skip").performClick()
        compose.enableAccessibilityChecks()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onRoot().tryPerformAccessibilityChecks()
        compose.onNodeWithTag("nav_run").performClick()
        compose.onNodeWithTag("home_ports").performScrollTo().performClick()
        compose.onRoot().tryPerformAccessibilityChecks()
    }
}
