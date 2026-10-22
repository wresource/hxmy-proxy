package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.rules.RuleApplyPhase
import com.mzstd.hxmyproxy.core.rules.RuleLoadFailure

/** Shared by runtime and rule editors: saved switches are not an application acknowledgement. */
@Composable
fun RuleApplicationNotice(
    share: ShareState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onRetryStopped: (() -> Unit)? = null,
    retryStartsSharing: Boolean = false,
    onLogs: (() -> Unit)? = null,
    showSettled: Boolean = false,
    compact: Boolean = false,
) {
    val state = share.ruleApplication
    if (!showSettled && state.phase in listOf(RuleApplyPhase.SAVED, RuleApplyPhase.APPLIED)) return
    val failed = state.phase == RuleApplyPhase.FAILED
    val title = when (state.phase) {
        RuleApplyPhase.SAVED -> R.string.rule_apply_saved
        RuleApplyPhase.APPLYING -> R.string.rule_apply_loading
        RuleApplyPhase.APPLIED -> R.string.rule_apply_ready
        RuleApplyPhase.FAILED -> if (state.stoppedForFailure) R.string.rule_apply_stopped else R.string.rule_apply_failed
    }
    @Composable fun actions() {
        if (onLogs != null) TextButton(onClick = onLogs) { Text(stringResource(R.string.rule_apply_logs)) }
        if (share.running && onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.testTag("retry_rules")) {
            Text(stringResource(R.string.rule_apply_retry))
        } else if (!share.running && !share.starting && onRetryStopped != null) {
            TextButton(onClick = onRetryStopped, modifier = Modifier.testTag("retry_rule_start")) {
                Text(stringResource(if (retryStartsSharing) R.string.rule_apply_retry_start else R.string.rule_apply_open_run))
            }
        }
    }
    Surface(modifier.fillMaxWidth().testTag("rule_application_notice"), shape = MaterialTheme.shapes.medium,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
        if (compact && failed) {
            // Runtime controls and traffic keep their viewport, including landscape/large text.
            // Rule pages retain the full explanation; both recovery actions remain visible here.
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(if (share.running) R.string.rule_apply_previous_short else R.string.rule_apply_saved_short),
                        style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) { actions() }
            }
        } else {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            when (state.phase) {
                RuleApplyPhase.SAVED -> Text(stringResource(R.string.rule_apply_saved_detail), style = MaterialTheme.typography.bodySmall)
                RuleApplyPhase.APPLYING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (share.running) R.string.rule_apply_pending_detail else R.string.rule_apply_starting_detail), style = MaterialTheme.typography.bodySmall)
                }
                RuleApplyPhase.FAILED -> {
                    Text(stringResource(if (state.stoppedForFailure) R.string.rule_apply_stopped_detail else R.string.rule_apply_failed_detail), style = MaterialTheme.typography.bodyMedium)
                    state.failure?.let { reason -> Text(stringResource(when (reason) {
                        RuleLoadFailure.ASSET_READ -> R.string.rule_apply_asset_error
                        RuleLoadFailure.VALIDATION -> R.string.rule_apply_validation_error
                        RuleLoadFailure.STORAGE -> R.string.rule_apply_storage_error
                    }), style = MaterialTheme.typography.bodySmall) }
                    if (state.retryScheduled) Text(stringResource(R.string.rule_apply_retry_scheduled), style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        actions()
                    }
                }
                RuleApplyPhase.APPLIED -> Unit
            }
        }
        }
    }
}
