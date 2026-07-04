package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.AvatarCircle
import com.mzstd.hxmyproxy.ui.components.CardGrid
import com.mzstd.hxmyproxy.ui.components.LabeledSwitchRow
import com.mzstd.hxmyproxy.ui.components.cardContainerColor

/**
 * 规则页:三模块（澄清 C 的分级开关）。
 * 1. IP / 域名白名单：用户增删，整组走直连（出口分流绕过共享 VPN）。
 * 2. App / 服务规则集：每服务一个开关（即将上线）。
 * 3. 广告拦截：每个表一个开关 + 用户白名单覆盖（OISD small 默认关）。
 */
@Composable
fun RulesScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onManage: () -> Unit,
    onOpenRejectDetail: () -> Unit = {},
    onOpenDirectDetail: () -> Unit = {},
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val s = ui.settings
    Column(
        // 沉浸式:inset padding 放 verticalScroll **之后**(属于被滚动内容,可随滚动穿入系统栏后方)。
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 页内大标题已删：底栏已标「规则」,页内再写「规则分流」冗余(用户反馈)。
        if (ui.share.lockdownSuspected) {
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    stringResource(R.string.lockdown_warning),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // ========== 🛡️ 拦截 Reject（命中即拒绝连接，有没有 VPN 都生效）==========
        SectionGroupHeader(stringResource(R.string.rules_group_reject), com.mzstd.hxmyproxy.ui.theme.StatusColors.bad())

        // 快速拦截单域名/IP/CIDR（对称白名单）→ userReject 表。与放行卡同一模板（用户反馈:两卡必须一致）。
        QuickRuleCard(
            title = stringResource(R.string.rules_reject_quick),
            hint = stringResource(R.string.rules_reject_quick_hint),
            rules = s.userRejectRules.sorted(),
            onAdd = { viewModel.addUserRejectRule(it) },
            onRemove = { viewModel.removeUserRejectRule(it) },
            onOpenDetail = onOpenRejectDetail,
        )

        // ========== 🌐 放行 Bypass（直连出口，绕过共享 VPN；仅 PAC 客户端生效）==========
        SectionGroupHeader(stringResource(R.string.rules_group_bypass), MaterialTheme.colorScheme.primary)

        // —— ① IP / 域名白名单（直连，出口分流绕过共享 VPN）——与拦截卡同模板，整体开关在标题行。
        QuickRuleCard(
            title = stringResource(R.string.rules_module_list),
            hint = stringResource(R.string.rules_user_direct_hint),
            rules = s.userDirectRules.sorted(),
            trailing = {
                Switch(checked = s.userDirectEnabled, onCheckedChange = { viewModel.toggleUserDirectEnabled(it) }, colors = com.mzstd.hxmyproxy.ui.components.stdSwitchColors())
            },
            onAdd = { viewModel.addUserDirectRule(it) },
            onRemove = { viewModel.removeUserDirectRule(it) },
            onOpenDetail = onOpenDirectDetail,
        )

        // —— ② App / 服务规则集 + 自建集（每集一个开关；管理入口可增删集/集内域名）——
        SectionCard(stringResource(R.string.rules_module_apps)) {
            Text(
                // 专属提示：原来错误复用了白名单的 rules_user_direct_hint（审计发现的文案 bug）。
                stringResource(R.string.rules_app_sets_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 两行式（仿导航栏编辑器，用户设计）：上=🌐放行、下=🛡️拦截。chip 主体点=停用（从行消失），
            // 右上 ⇄ 徽标=移到另一行（内置组切 rejectedGroups / 自建集切 action）。
            // 64 个内置组平铺会爆——只显示**已启用**的组,全部组去「管理」按分类启用。
            val ra = com.mzstd.hxmyproxy.core.rules.RuleAction.REJECT
            val bypassDescs = RuleCatalog.appGroups.filter { it.id in s.enabledRuleGroups && it.id !in s.rejectedGroups }.map { g ->
                RuleCellDesc(
                    g.titleRes, null, groupIcon(g.id), true,
                    onToggle = { viewModel.toggleRuleGroup(g.id, false) },
                    onSwap = { viewModel.setGroupRejected(g.id, true) },
                )
            } + s.userRuleSets.filter { it.action != ra }.map { set ->
                RuleCellDesc(
                    null, set.name, R.drawable.ic_rule_label, set.enabled,
                    onToggle = { viewModel.toggleRuleSet(set.id, !set.enabled) },
                    onSwap = { viewModel.setRuleSetAction(set.id, ra) },
                )
            }
            val rejectDescs = RuleCatalog.appGroups.filter { it.id in s.enabledRuleGroups && it.id in s.rejectedGroups }.map { g ->
                RuleCellDesc(
                    g.titleRes, null, groupIcon(g.id), true,
                    onToggle = { viewModel.toggleRuleGroup(g.id, false) },
                    onSwap = { viewModel.setGroupRejected(g.id, false) },
                )
            } + s.userRuleSets.filter { it.action == ra }.map { set ->
                RuleCellDesc(
                    null, set.name, R.drawable.ic_rule_label, set.enabled,
                    onToggle = { viewModel.toggleRuleSet(set.id, !set.enabled) },
                    onSwap = { viewModel.setRuleSetAction(set.id, com.mzstd.hxmyproxy.core.rules.RuleAction.DIRECT) },
                )
            }
            SectionGroupHeader(stringResource(R.string.rules_group_bypass), MaterialTheme.colorScheme.primary)
            if (bypassDescs.isEmpty()) {
                Text(
                    stringResource(R.string.rules_row_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CardGrid(items = bypassDescs, collapsedRows = 2) { m, d -> RuleSetGridCell(m, d) }
            }
            SectionGroupHeader(stringResource(R.string.rules_group_reject), com.mzstd.hxmyproxy.ui.theme.StatusColors.bad())
            if (rejectDescs.isEmpty()) {
                Text(
                    stringResource(R.string.rules_row_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CardGrid(items = rejectDescs, collapsedRows = 2) { m, d -> RuleSetGridCell(m, d) }
            }
            OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.rules_manage))
            }
        }

    }
}

/**
 * 快速名单卡统一模板（拦截/放行同构，用户反馈:两卡必须一致、按钮与输入框对齐）：
 * 说明 → 输入行（无 supportingText，按钮与框真正居中对齐）→ 格式提示 → 前 2 条预览 → 「管理全部」入口。
 * 全量增删/从历史添加在 [QuickRulesDetailScreen]。
 */
@Composable
private fun QuickRuleCard(
    title: String,
    hint: String,
    rules: List<String>,
    trailing: (@Composable () -> Unit)? = null,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenDetail: () -> Unit,
) {
    SectionCard(title, trailing = trailing ?: {}) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var input by remember { mutableStateOf("") }
        // IntrinsicSize.Min + fillMaxHeight：按钮与输入框(56dp)等高，尺寸匹配（用户反馈:按钮明显比框小）。
        Row(
            Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                // placeholder 而非 label：label 上浮要在组件顶部预留 ~8dp，导致按钮 fillMaxHeight 后顶部突出;
                // placeholder 版组件=可见框=56dp，按钮与框像素级同高对齐。
                placeholder = { Text(stringResource(R.string.rules_add_domain)) },
                // 输入框默认 extraSmall(8dp) 圆角与按钮 large(16dp) 打架——统一 16dp（全 app 弧度语言）。
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { if (input.isNotBlank()) { onAdd(input); input = "" } },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxHeight(),
            ) { Text(stringResource(R.string.rules_add)) }
        }
        Text(
            stringResource(R.string.rules_format_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rules.take(2).forEach { rule ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 删除用图标按钮：Remove/移除 文字宽度随语言变化导致一列参差（双语检查原则）。
                androidx.compose.material3.IconButton(onClick = { onRemove(rule) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.rules_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        com.mzstd.hxmyproxy.ui.components.NavRow(stringResource(R.string.rules_manage_all, rules.size), onOpenDetail)
    }
}

/** 规则分区大标题（🛡️ 拦截 / 🌐 放行），带语义色，把 reject/bypass 两大类在视觉上分开。 */
@Composable
private fun SectionGroupHeader(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

// GroupSwitchRow 已收敛到 components.LabeledSwitchRow（与设置项/接口开关同款）。

/** 规则集网格单元描述（非 composable，避免在 map 里调 composable）。onSwap≠null 显示 ⇄ 徽标（放行↔拦截移行）。 */
private class RuleCellDesc(
    val titleRes: Int?, val titleStr: String?, val iconRes: Int,
    val enabled: Boolean, val onToggle: () -> Unit, val onSwap: (() -> Unit)? = null,
)

/** 内置 App 集的语义图标（Material 开源图标）。避免商标：用音乐/视频/聊天等通用图标。 */
private fun groupIcon(id: String): Int = when (id) {
    "app-neteasemusic" -> R.drawable.ic_rule_music
    "app-bilibili" -> R.drawable.ic_rule_video
    "app-wechat" -> R.drawable.ic_rule_chat
    else -> R.drawable.ic_rule_label
}

/** 规则集圆形图标网格单元：圆形(开=主题 primary / 关=灰) + 名称；点击切换开关。
 *  开关状态只用主题色表达——原先的第三方品牌色(网易红/B站粉/微信绿)不随主题、与蓝粉打架,已收敛。 */
@Composable
private fun RuleSetGridCell(modifier: Modifier, d: RuleCellDesc) {
    val name = d.titleRes?.let { stringResource(it) } ?: d.titleStr ?: ""
    // 外层 Box 不裁剪、留出顶部空间——⇄ 徽标挂外层右上角，不被点击区 clip 切掉（同 NavTabCell 方案）。
    Box(modifier.padding(top = 6.dp)) {
        Column(
            Modifier.fillMaxWidth().clickable { d.onToggle() }.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarCircle(
                40.dp,
                if (d.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    painterResource(d.iconRes),
                    contentDescription = name,
                    tint = if (d.enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
        // ⇄ 徽标：把该集移到另一行（放行↔拦截）。悬于右上角（上移 6dp 进预留区），独立可点。
        if (d.onSwap != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-6).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClickLabel = stringResource(R.string.rules_swap_a11y, name)) { d.onSwap.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Text("⇄", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    // 与监控/设置/首页统一：cardContainerColor（浅色 Low 不发灰 / 深色 High 靠明度对比浮出，不描边）。
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = cardContainerColor()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                trailing()
            }
            content()
        }
    }
}

/** 「从历史添加」对话框：列出访问过的域名(已在白名单的排除)，点一条加入白名单，可连点多条。 */
@Composable
internal fun HistoryAddDialog(history: List<String>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.setup_close)) } },
        title = { Text(stringResource(R.string.rules_add_from_history)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.rules_history_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(history) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onAdd(h) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(h, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
    )
}
