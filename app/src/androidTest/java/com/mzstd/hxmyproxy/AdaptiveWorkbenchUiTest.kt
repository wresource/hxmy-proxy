package com.mzstd.hxmyproxy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.ui.components.AdaptiveActionPair
import com.mzstd.hxmyproxy.ui.components.SegTabs
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveWorkbenchUiTest {
    @get:Rule val compose = createComposeRule()

    private fun revealTab(label: String) {
        // Scroll by actual gestures. performScrollTo can loop forever at a fractional-pixel
        // horizontal endpoint (its ScrollBy loop does not check whether the offset changed).
        // Keep a finite gesture budget and require the complete label to enter the viewport.
        repeat(6) {
            if (compose.onAllNodesWithTag("scrollable_sections").fetchSemanticsNodes().isEmpty()) return
            val viewport = compose.onNodeWithTag("scrollable_sections").fetchSemanticsNode().boundsInRoot
            val labelBounds = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if (labelBounds.left >= viewport.left && labelBounds.right <= viewport.right) return
            compose.onNodeWithTag("scrollable_sections").performTouchInput {
                if (labelBounds.left < viewport.left) swipeRight() else swipeLeft()
            }
        }
        error("The full tab label could not be reached: $label")
    }

    private fun completeSingleLine(label: String) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        text.assertIsDisplayed()
        val result = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        val layout = result.single()
        assertEquals(label, 1, layout.lineCount)
        assertEquals(label.length, layout.getLineEnd(0, visibleEnd = true))
        assertFalse(layout.isLineEllipsized(0))
        label.indices.forEach { index ->
            val box = layout.getBoundingBox(index)
            val fits = box.left.isFinite() && box.right.isFinite() && box.left >= 0 && box.right <= layout.size.width
            if (!fits) {
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                java.io.File(instrumentation.targetContext.externalCacheDir, "tab-layout-failure.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertTrue("$label character $index must fit: box=$box size=${layout.size} constraints=${layout.layoutInput.constraints} density=${layout.layoutInput.density}", fits)
        }
    }

    @Test fun allSectionsStayReadableAndSelectableAtLargeFonts() {
        val scale = mutableStateOf(1f); val selected = mutableIntStateOf(0)
        val labels = listOf("Overview", "Activity", "Traffic", "Health")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                HxmyProxyTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SegTabs(labels, selected.intValue, { selected.intValue = it })
                    }
                } }
            }
        }
        listOf(1f, 1.5f, 2f).forEach { font ->
            compose.runOnIdle { scale.value = font }
            labels.forEachIndexed { i, label ->
                revealTab(label)
                compose.onNodeWithTag("section_tab_$i").performClick().assertIsSelected()
                completeSingleLine(label)
            }
        }
    }

    @Test fun pairedActionsKeepCompleteWordsAndAccessibleTouchAreas() {
        val scale = mutableStateOf(1f); var clicked = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                HxmyProxyTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        AdaptiveActionPair("Inbound", "Edit ports") { button ->
                            OutlinedButton(onClick = { clicked = "inbound" }, modifier = button.testTag("inbound_action")) { Text("Inbound", style = MaterialTheme.typography.labelLarge) }
                            OutlinedButton(onClick = { clicked = "ports" }, modifier = button.testTag("ports_action")) { Text("Edit ports", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                } }
            }
        }
        listOf(1f, 1.5f, 2f).forEach { font ->
            compose.runOnIdle { scale.value = font }
            listOf("inbound_action" to "Inbound", "ports_action" to "Edit ports").forEach { (tag, label) ->
                compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                completeSingleLine(label)
            }
            compose.runOnIdle { assertEquals("ports", clicked) }
        }
    }
}
