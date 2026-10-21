package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleScope

/** Complex details/forms follow the approved bottom-panel prototype. Only the body scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractionSheet(
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    dismissEnabled: Boolean = true,
    bodyScrollState: ScrollState? = null,
    scrollBody: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { canDismiss || it != SheetValue.Hidden })
    val defaultScroll = rememberScrollState()
    val scroll = bodyScrollState ?: defaultScroll
    var truncated by remember(title, LocalConfiguration.current.screenWidthDp, LocalDensity.current.fontScale) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // A modal owns another Android window, whose platform locals would otherwise replace
    // the app's explicit locale and font scale. Keep the existing stable locale-provider tree.
    val appContext = LocalContext.current
    val appConfiguration = LocalConfiguration.current
    val appDensity = LocalDensity.current
    ModalBottomSheet(
        onDismissRequest = { if (canDismiss) onDismiss() },
        sheetState = sheetState, sheetMaxWidth = 480.dp,
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        dragHandle = null,
    ) {
      CompositionLocalProvider(LocalContext provides appContext, LocalConfiguration provides appConfiguration, LocalDensity provides appDensity) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.9f)
            .testTag("interaction_sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.testTag("sheet_back")) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
                SelectionContainer(Modifier.weight(1f)) {
                    Text(if (truncated) stringResource(R.string.ui_details) else title, Modifier.testTag("sheet_title"), style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!truncated) truncated = it.didOverflowHeight })
                }
                IconButton(onClick = onDismiss, enabled = dismissEnabled, modifier = Modifier.testTag("sheet_close")) {
                    Icon(painterResource(R.drawable.ic_b_close), stringResource(R.string.ui_close))
                }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().then(if (scrollBody) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp).testTag("sheet_body"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Full long values remain readable/copyable. Ordinary titles are never repeated.
                if (truncated) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    SelectionContainer(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyMedium) }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(title)) }) {
                        Icon(painterResource(R.drawable.ic_b_copy), stringResource(R.string.copy))
                    }
                }
                content()
            }
            if (footer != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("sheet_actions")) { footer() }
            }
        }
      }
    }
}

@Composable
fun SheetActions(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit,
    enabled: Boolean = true, primaryTag: String = "", primaryEnabled: Boolean = true) {
    AdaptiveActionPair(secondary, primary, extraWidth = 24.dp) { button ->
        OutlinedButton(onClick = onSecondary, enabled = enabled, modifier = button,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text(secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        }
        Button(onClick = onPrimary, enabled = enabled && primaryEnabled, modifier = button.testTag(primaryTag),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
            Text(primary, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun DetailValueRow(label: String, value: String, onClick: (() -> Unit)? = null, mono: Boolean = false) {
    val click = if (onClick == null) Modifier else Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick)
    Row(Modifier.fillMaxWidth().then(click).heightIn(min = if (onClick == null) 32.dp else 48.dp)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, fontFamily = if (mono) FontFamily.Monospace else null, textAlign = TextAlign.End)
        if (onClick != null) Icon(painterResource(R.drawable.ic_b_edit), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        else Spacer(Modifier.size(18.dp))
    }
}

@Composable
fun SheetChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, description: String? = null, enabled: Boolean = true) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleScopePicker(scope: RuleScope, onChange: (RuleScope) -> Unit) = RuleScopePicker(scope, true, onChange)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleScopePicker(scope: RuleScope, enabled: Boolean, onChange: (RuleScope) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val environment = rememberAppWindowEnvironment()
    fun label(value: RuleScope) = when (value) {
        RuleScope.EXACT -> R.string.rules_scope_exact; RuleScope.SINGLE -> R.string.rules_scope_single; RuleScope.SUFFIX -> R.string.rules_scope_suffix
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ui_rule_scope), style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(expanded && enabled, { if (enabled) expanded = !expanded }) {
            OutlinedTextField(stringResource(label(scope)), {}, readOnly = true, enabled = enabled,
                modifier = Modifier.menuAnchor().fillMaxWidth().testTag("rule_scope"),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, textStyle = MaterialTheme.typography.bodyMedium, shape = MaterialTheme.shapes.medium)
            ExposedDropdownMenu(expanded && enabled, { expanded = false }, shape = MaterialTheme.shapes.medium) {
              environment.Provide {
                listOf(RuleScope.EXACT, RuleScope.SINGLE, RuleScope.SUFFIX).forEach { value ->
                    DropdownMenuItem(modifier = Modifier.clip(MaterialTheme.shapes.small), text = { Text(stringResource(label(value))) }, onClick = { onChange(value); expanded = false })
                }
              }
            }
        }
    }
}
