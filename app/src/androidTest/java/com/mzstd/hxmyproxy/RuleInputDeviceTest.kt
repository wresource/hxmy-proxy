package com.mzstd.hxmyproxy

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.data.repository.SettingsRepository
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.components.RuleEditDialog
import com.mzstd.hxmyproxy.ui.rules.RulePillInputRow
import com.mzstd.hxmyproxy.ui.rules.allowAccent
import com.mzstd.hxmyproxy.ui.run.WarnBanners
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android ICU and numeric matching; android.jar stubs cannot validate either. */
@RunWith(AndroidJUnit4::class)
class RuleInputDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unicodeAndAceUseTheSameNonTransitionalDomain() {
        val examples = mapOf("中国.com" to "xn--fiqs8s.com", "faß.de" to "xn--fa-hia.de",
            "ＡＲＸＩＶ。ＯＲＧ" to "arxiv.org", "www。arxiv.org" to "www.arxiv.org")
        examples.forEach { (input, ascii) ->
            assertEquals(input, ascii, RuleInput.normalize(input))
            val matcher = RuleMatcher().apply { add(RuleInput.normalize(input)!!) }
            assertTrue(input, matcher.matches(ascii)); assertTrue(input, matcher.matches(input))
        }
        val normalized = RuleInput.normalize("\u0430rxiv.org")!!
        assertNotEquals("arxiv.org", normalized)
        assertFalse(RuleMatcher().apply { add(normalized) }.matches("arxiv.org"))
        assertNull(RuleInput.normalize("xn--.com"))
        assertNull(RuleInput.normalize("ar\u200bxiv.org"))
        assertFalse(RuleMatcher().apply { add("xn--fa-hia.de") }.matches("fass.de"))
        assertEquals("*.xn--fiqs8s.com", RuleInput.forScope("https://中国.com/path?q=1", RuleScope.SINGLE)!!.value)
    }

    @Test fun ipv4Ipv6AndCidrUseActualAndroidMatching() {
        val matcher = RuleMatcher().apply {
            listOf("1.2.3.4:443", "2001:db8::/32", "[::1]", "[::ffff:192.0.2.1]:443").forEach {
                add(RuleInput.normalize(it)!!)
            }
        }
        assertTrue(matcher.matches("1.2.3.4")); assertFalse(matcher.matches("1.2.3.5"))
        assertTrue(matcher.matches("2001:db8::23")); assertFalse(matcher.matches("2001:db9::23"))
        assertTrue(matcher.matches("::1")); assertTrue(matcher.matches("192.0.2.1"))
        listOf("10.0.0.1/33", "2001:db8::/129", "2001::db8::1", "[1.2.3.4]", "256.2.3.4").forEach {
            assertNull(it, RuleInput.normalize(it))
        }
    }

    @Test fun backupValidationIsAtomicAndNormalizesRules() = runBlocking {
        val repo = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = repo.settings.first()
        try {
            val before = ProxySettings(userDirectRules = listOf(RuleEntry("safe.example")))
            suspend fun backupOf(values: List<RuleEntry>): String {
                repo.update { before.copy(userDirectRules = values) }
                val backup = repo.exportJson(); repo.update { before }; return backup
            }
            repo.importJson(backupOf(listOf(RuleEntry("https://中国.com/path", addedAt = 123))))
            assertEquals(RuleEntry("xn--fiqs8s.com", addedAt = 123), repo.settings.first().userDirectRules.single())
            for (bad in listOf(listOf(RuleEntry("bad..example")),
                listOf(RuleEntry("https://safe.example"), RuleEntry("safe.example")))) {
                val backup = backupOf(bad)
                assertTrue(runCatching { repo.importJson(backup) }.isFailure)
                assertEquals(before, repo.settings.first())
            }
        } finally { repo.update { original } }
    }

    @Test fun addFormKeepsBadTextAndPreservesSelectedScopeForUrls() {
        val saved = mutableListOf<String>(); lateinit var add: String
        compose.setContent {
            add = LocalContext.current.getString(R.string.rules_add)
            MaterialTheme { RulePillInputRow(allowAccent()) { saved += it } }
        }
        compose.onNode(hasSetTextAction()).performTextInput("https://1password.com/login?q=1")
        compose.onNodeWithText(add).performClick()
        compose.runOnIdle { assertEquals(listOf("*.1password.com"), saved) }
        compose.onNode(hasSetTextAction()).performTextReplacement("bad..example")
        compose.onNodeWithText(add).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertTextContains("bad..example")
        compose.runOnIdle { assertEquals(1, saved.size) }
    }

    @Test fun editorRejectsInvalidSaveAndShowsNormalizedResult() {
        val saved = mutableListOf<String>(); lateinit var save: String
        compose.setContent {
            save = LocalContext.current.getString(R.string.ruleset_save)
            MaterialTheme { RuleEditDialog(RuleEntry("*.apple.com"), { saved += it }, {}, {}) }
        }
        compose.onNodeWithTag("rule_edit_input").performTextReplacement("bad..example")
        compose.onNodeWithText(save).assertIsNotEnabled()
        compose.onNodeWithTag("rule_edit_input").performTextReplacement("https://developer.apple.com/store")
        compose.onNodeWithText(save).performClick()
        compose.runOnIdle { assertEquals(listOf("*.developer.apple.com"), saved) }
    }

    @Test fun actualMissingDirectEgressHasVisibleLocalizedExplanation() {
        val failed = mutableStateOf(true); lateinit var message: String
        compose.setContent {
            val context = LocalContext.current
            message = context.getString(R.string.direct_egress_missing, context.getString(R.string.egress_wifi))
            MaterialTheme { Surface { Column(Modifier.safeDrawingPadding()) {
                WarnBanners(MainUiState(settings = ProxySettings(directEgressChoice = DirectEgressChoice.WIFI),
                    share = ShareState(running = true, admissionEmpty = false,
                        diagnostics = ShareState().diagnostics.copy(httpPortUp = true),
                        directEgressUnavailable = failed.value)))
            } } }
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "direct-egress-warning.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { failed.value = false }
        compose.onAllNodesWithText(message).assertCountEquals(0)
    }
}
