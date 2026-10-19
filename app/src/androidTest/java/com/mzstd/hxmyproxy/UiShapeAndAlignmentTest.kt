package com.mzstd.hxmyproxy

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.AppLanguage
import com.mzstd.hxmyproxy.ui.components.*
import com.mzstd.hxmyproxy.ui.locale.ProvideAppLocale
import com.mzstd.hxmyproxy.ui.rules.CollapseAllChip
import com.mzstd.hxmyproxy.ui.rules.SectionHeaderRow
import com.mzstd.hxmyproxy.ui.run.SummaryCell
import com.mzstd.hxmyproxy.ui.theme.HxmyProxyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class UiShapeAndAlignmentTest {
    @get:Rule val compose = createComposeRule()

    private fun fullText(label: String): Pair<TextLayoutResult, Float> {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // Compose 1.11 can keep the paragraph's measurement width while the Text node
        // wraps its actual glyphs (e.g. "37": paragraph 1080px, node 102px). Its width
        // overflow flag then reports a false positive. Check real glyph and parent bounds.
        assertFalse("Text exceeds its height: $label", layout.didOverflowHeight)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        repeat(layout.lineCount) { assertFalse("Ellipsized text: $label", layout.isLineEllipsized(it)) }
        label.indices.forEach { index ->
            val glyph = layout.getBoundingBox(index)
            assertTrue("Clipped glyph $index in $label: $glyph vs ${layout.size}",
                glyph.left >= -0.5f && glyph.right <= layout.size.width + 0.5f &&
                    glyph.top >= -0.5f && glyph.bottom <= layout.size.height + 0.5f)
        }
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertEquals("Parent clips $label", layout.size.width.toFloat(), visible.width, 1f)
        assertEquals("Parent clips $label", layout.size.height.toFloat(), visible.height, 1f)
        return layout to node.fetchSemanticsNode().boundsInRoot.top
    }

    @Test fun narrowLargeTextHeaderKeepsCountAndEveryActionVisible() {
        val language = mutableStateOf(AppLanguage.ENGLISH)
        var collapsed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                ProvideAppLocale(language.value) { HxmyProxyTheme {
                    Surface(Modifier.width(320.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            SectionHeaderRow(if (language.value == AppLanguage.ENGLISH) "Built-in rule sets" else "内置规则集",
                                if (language.value == AppLanguage.ENGLISH) "66 sets · 11 categories" else "66 组 · 11 类") {
                                Switch(false, {})
                                CollapseAllChip { collapsed++ }
                            }
                        }
                    }
                } }
            }
        }
        listOf("Built-in rule sets", "66 sets · 11 categories", "Collapse all").forEach(::fullText)
        compose.onNodeWithText("Collapse all").performClick()
        compose.runOnIdle { assertEquals(1, collapsed); language.value = AppLanguage.CHINESE }
        listOf("内置规则集", "66 组 · 11 类", "全部收起").forEach(::fullText)
        compose.onNodeWithText("全部收起").performClick()
        compose.runOnIdle { assertEquals(2, collapsed) }
    }

    @Test fun numbersAndUnitsShareActualTextBaseline() {
        compose.setContent { HxmyProxyTheme { Surface { BigStat("37", "MB") } } }
        val (value, valueTop) = fullText("37")
        val (unit, unitTop) = fullText("MB")
        assertEquals(valueTop + value.firstBaseline, unitTop + unit.firstBaseline, 1f)
    }

    @Test fun threeLargeTextActionsWrapWithEqualWidthAndFullLabels() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HxmyProxyTheme { Surface(Modifier.width(320.dp)) { Column(Modifier.padding(16.dp)) {
                    AdaptiveActions(listOf("Save", "Import", "Export"), extraWidth = 32.dp) { button ->
                        listOf("Save", "Import", "Export").forEach { label -> Button({}, button.testTag(label)) { Text(label) } }
                    }
                } } }
            }
        }
        listOf("Save", "Import", "Export").forEach(::fullText)
        val bounds = listOf("Save", "Import", "Export").map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.zipWithNext().all { (a, b) -> a.bottom <= b.top })
        assertTrue(bounds.all { abs(it.width - bounds.first().width) < 1f && abs(it.left - bounds.first().left) < 1f })
    }

    @Test fun pressedFeedbackIsRoundedInLightAndDarkThemesAndStillVisible() {
        val sample = mutableStateOf(0)
        val dark = mutableStateOf(false)
        compose.setContent { HxmyProxyTheme(darkTheme = dark.value) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp)) {
                    Box(Modifier.fillMaxWidth().testTag("feedback")) {
                        when (sample.value) {
                            // Measurement control: the old background-only pattern leaks ripple into corners.
                            0 -> Box(Modifier.fillMaxWidth().height(64.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                                .clickable {})
                            1 -> SummaryCell("Outbound", "Wi-Fi", Modifier.fillMaxWidth(), onClick = {})
                            2 -> WorkbenchFold("Runtime settings", "feedback") {}
                            3 -> DetailValueRow("Rule", "Proxy", onClick = {})
                            4 -> SegTabs(listOf("Overview", "Activity"), 0, {})
                        }
                    }
                }
            }
        } }
        for (isDark in listOf(false, true)) for (kind in 0..4) {
            compose.runOnIdle { dark.value = isDark; sample.value = kind }
            val target = compose.onNodeWithTag(if (kind == 4) "section_tab_0" else "feedback")
            val before = target.captureToImage().asAndroidBitmap()
            target.performTouchInput { down(center) }
            // Android ripple draws on the render thread; let a held press reach the bounds.
            Thread.sleep(400)
            val pressed = target.captureToImage().asAndroidBitmap()
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shape-pressed-$isDark-$kind.png")
            file.outputStream().use { pressed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            target.performTouchInput { up() }
            Thread.sleep(250)
            fun different(x: Int, y: Int) = before.getPixel(x, y) != pressed.getPixel(x, y)
            val corners = listOf(2 to 2, (before.width - 3) to 2, 2 to (before.height - 3), (before.width - 3) to (before.height - 3))
            if (kind == 0) assertTrue("Control must expose square feedback", corners.any { (x, y) -> different(x, y) })
            else assertTrue("Rounded feedback leaked at kind=$kind dark=$isDark", corners.none { (x, y) -> different(x, y) })
            var changed = 0
            for (y in 4 until before.height - 4 step 3) for (x in 4 until before.width - 4 step 3) if (different(x, y)) changed++
            assertTrue("Feedback must remain visible: kind=$kind dark=$isDark", changed > 10)
        }
    }
}
