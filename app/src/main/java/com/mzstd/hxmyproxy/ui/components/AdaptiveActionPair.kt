package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep paired actions side by side when their full labels fit, otherwise use full-width rows. */
@Composable
fun AdaptiveActionPair(
    first: String,
    second: String,
    modifier: Modifier = Modifier,
    extraWidth: Dp = 48.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    content: @Composable (Modifier) -> Unit,
) {
    AdaptiveActions(listOf(first, second), modifier, extraWidth, textStyle, content)
}

/** The same sizing rule for any small action group, including Save / Import / Export. */
@Composable
fun AdaptiveActions(
    labels: List<String>,
    modifier: Modifier = Modifier,
    extraWidth: Dp = 48.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    content: @Composable (Modifier) -> Unit,
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val measure = rememberTextMeasurer()
    val textWidth = labels.maxOf { measure.measure(it, textStyle).size.width }
    val minimumWidth = (with(density) { textWidth.toDp() } + extraWidth).coerceAtLeast(48.dp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= minimumWidth * labels.size + 8.dp * (labels.size - 1)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                content(Modifier.weight(1f).heightIn(min = 48.dp))
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content(Modifier.fillMaxWidth().heightIn(min = 48.dp))
        }
    }
}
