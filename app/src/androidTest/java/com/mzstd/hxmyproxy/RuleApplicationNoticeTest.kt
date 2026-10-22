package com.mzstd.hxmyproxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.ui.components.RuleApplicationNotice
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleApplicationNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun Host(language: AppLanguage, dark: Boolean, body: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
            ProvideAppLocale(language) { HxmyProxyTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { body() }
                }
            } }
        }
    }
    @Test fun englishFailureKeepsOldConfigurationExplicitAndRetryIsReachable() {
        var retries = 0
        compose.setContent { Host(AppLanguage.ENGLISH, true) {
            RuleApplicationNotice(ShareState(running = true, ruleApplication = RuleApplicationState(
                phase = RuleApplyPhase.FAILED, appliedVersion = 4, counts = RuleCounts(userDirect = 2),
                failure = RuleLoadFailure.ASSET_READ, retryScheduled = true)), onRetry = { retries++ }, onLogs = {})
        } }
        compose.onNodeWithText("Rule changes are not active").assertExists()
        compose.onNodeWithText("Changes are saved, but could not be applied. Sharing still uses the last successful configuration.").assertExists()
        compose.onNodeWithTag("retry_rules").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun stoppedFailureUsesExplicitStartActionAndRecoveryRemovesFailure() {
        val share = mutableStateOf(ShareState(ruleApplication = RuleApplicationState(phase = RuleApplyPhase.FAILED,
            failure = RuleLoadFailure.ASSET_READ, stoppedForFailure = true)))
        var starts = 0
        compose.setContent { Host(AppLanguage.CHINESE, false) {
            RuleApplicationNotice(share.value, onRetry = { error("must not use running-only retry") },
                onRetryStopped = { starts++ }, retryStartsSharing = true, showSettled = true)
        } }
        compose.onNodeWithText("规则加载失败，共享已停止").assertExists()
        compose.onNodeWithTag("retry_rule_start").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, starts)
            share.value = ShareState(running = true, ruleApplication = RuleApplicationState(phase = RuleApplyPhase.APPLIED))
        }
        compose.onNodeWithText("规则已生效").assertExists()
        compose.onNodeWithTag("retry_rule_start").assertDoesNotExist()
        compose.onNodeWithText("规则加载失败，共享已停止").assertDoesNotExist()
    }
    @Test fun pendingAndStoppedSettingsNeverClaimApplied() {
        val phase = mutableStateOf(RuleApplyPhase.APPLYING)
        compose.setContent { Host(AppLanguage.ENGLISH, false) {
            RuleApplicationNotice(ShareState(ruleApplication = RuleApplicationState(phase = phase.value)), showSettled = true)
        } }
        compose.onNodeWithText("Applying rules…").assertExists()
        compose.onNodeWithText("Rules applied").assertDoesNotExist()
        compose.runOnIdle { phase.value = RuleApplyPhase.SAVED }
        compose.onNodeWithText("Rules will be applied when sharing starts.").assertExists()
        compose.onNodeWithText("Rules applied").assertDoesNotExist()
    }

    @Test fun compactRuntimeNoticeLeavesRoomForProxyControlsAtLargeFont() {
        var starts = 0
        var density = 1f
        compose.setContent {
            val physical = LocalDensity.current.density
            density = physical
            CompositionLocalProvider(LocalDensity provides Density(physical, 1.5f)) {
                ProvideAppLocale(AppLanguage.ENGLISH) { HxmyProxyTheme(darkTheme = false) {
                    Surface {
                        Column(Modifier.widthIn(max = 400.dp).height(320.dp)) {
                            Spacer(Modifier.height(100.dp))
                            RuleApplicationNotice(ShareState(ruleApplication = RuleApplicationState(
                                phase = RuleApplyPhase.FAILED, failure = RuleLoadFailure.ASSET_READ, stoppedForFailure = true)),
                                onRetryStopped = { starts++ }, retryStartsSharing = true, onLogs = {}, compact = true)
                            Box(Modifier.weight(1f).fillMaxWidth().testTag("remaining_proxy_controls"))
                        }
                    }
                } }
            }
        }
        val space = compose.onNodeWithTag("remaining_proxy_controls").fetchSemanticsNode().boundsInRoot.height
        assertTrue("runtime controls collapsed to $space px", space >= 48 * density)
        compose.onNodeWithTag("retry_rule_start").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
