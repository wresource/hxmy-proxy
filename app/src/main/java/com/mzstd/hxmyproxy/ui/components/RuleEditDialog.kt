package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.rules.IpCidrSet
import com.mzstd.hxmyproxy.core.rules.RuleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val addedFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/**
 * 规则条目的编辑与详情。
 *
 * 【为什么需要】列表行里塞了六个元素（类型/值/作用域/状态/开关/删除），值被挤在 `weight(1f)` 里
 * 单行省略——**长 IP 与长域名根本看不全**；而写错一个字符只能删了重加，作用域档位更是加完就定死。
 * 这里把「看全 + 改值 + 改档位」放在同一个地方。
 *
 * 【作用域即前缀】三档在数据上就是值的前缀（`=` 精确 / `*.` 单级 / 无前缀 全层级），所以输入框里
 * 只放**裸域名**、档位单独用 chip 选，保存时再合成——与添加时的输入方式完全一致，用户不必记语法。
 *
 * 【IP/CIDR 无档位】作用域是域名的概念，IP 上没有意义（与 [RuleScopeBadge] 对 IP 返回 null 同源），
 * 故检测到 IP/CIDR 时整组 chip 隐藏，避免给出一个改了也没用的开关。
 *
 * 【全层级仍要确认】与添加路径同一道防线：全层级会连同任意深度子域一并处理，误选代价大。
 * 从「单级」改成「全层级」时二次确认，反向不拦。
 */
@Composable
fun RuleEditDialog(
    entry: RuleEntry,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (origScope, origBare) = remember(entry.value) { RuleScope.parse(entry.value) }
    val isIp = remember(entry.value) { IpCidrSet.looksLikeIpOrCidr(entry.value) }
    var bare by remember(entry.value) { mutableStateOf(origBare) }
    var scope by remember(entry.value) { mutableStateOf(origScope) }
    // 待确认的「升级为全层级」；非空时先弹确认。
    var confirmSuffix by remember { mutableStateOf(false) }

    val result = if (isIp) bare.trim() else RuleScope.format(scope, bare.trim())
    val changed = result != entry.value && bare.isNotBlank()

    if (confirmSuffix) {
        val b = bare.trim()
        // 复用添加路径那套文案与选项（同一道防线、同一种措辞，用户不必学两遍）:
        // 确认=保持全层级并保存；另一个按钮=退回单级,回到编辑继续调,不直接保存。
        AlertDialog(
            onDismissRequest = { confirmSuffix = false },
            title = { Text(stringResource(R.string.rules_scope_confirm_title)) },
            text = { Text(stringResource(R.string.rules_scope_confirm_body, b, b, b)) },
            confirmButton = {
                TextButton(onClick = { confirmSuffix = false; onSave(RuleScope.format(RuleScope.SUFFIX, b)) }) {
                    Text(stringResource(R.string.rules_scope_confirm_keep_suffix))
                }
            },
            dismissButton = {
                TextButton(onClick = { scope = RuleScope.SINGLE; confirmSuffix = false }) {
                    Text(stringResource(R.string.rules_scope_confirm_use_single, b))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 完整原值：等宽 + 不限行数,长 IPv6 / 长域名在这里一定看得全（列表里看不全正是痛点）。
                Text(
                    stringResource(R.string.rules_edit_current),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = bare,
                    onValueChange = { bare = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    // placeholder 不用 label：label 上浮后会与上一行挤在一起（见 Compose UI 设计规范）。
                    placeholder = { Text(stringResource(R.string.rules_add_domain)) },
                )
                if (!isIp) {
                    Text(
                        stringResource(R.string.rules_edit_scope),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuleScope.entries.forEach { s ->
                            FilterChip(
                                selected = s == scope,
                                onClick = { scope = s },
                                label = {
                                    Text(
                                        stringResource(
                                            when (s) {
                                                RuleScope.EXACT -> R.string.rules_scope_exact
                                                RuleScope.SINGLE -> R.string.rules_scope_single
                                                RuleScope.SUFFIX -> R.string.rules_scope_suffix
                                            },
                                        ),
                                    )
                                },
                                colors = stdFilterChipColors(),
                            )
                        }
                    }
                    // 改完档位后，用最终值预览「这条规则将变成什么」——作用域是前缀，光看 chip 不直观。
                    Text(
                        stringResource(R.string.rules_edit_result, result),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (entry.addedAt > 0L) {
                    Text(
                        stringResource(R.string.rules_edit_added, addedFmt.format(Date(entry.addedAt))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = changed,
                onClick = {
                    // 只有「升级到全层级」才拦一道；已经是全层级或往更窄改，直接保存。
                    if (!isIp && scope == RuleScope.SUFFIX && origScope != RuleScope.SUFFIX) confirmSuffix = true
                    else onSave(result)
                },
            ) { Text(stringResource(R.string.ruleset_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
