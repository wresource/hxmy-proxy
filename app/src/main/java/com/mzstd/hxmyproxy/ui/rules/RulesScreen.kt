package com.mzstd.hxmyproxy.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.model.RuleEntry
import com.mzstd.hxmyproxy.core.model.RuleEntry.Companion.sortedForDisplay
import com.mzstd.hxmyproxy.core.rules.IpCidrSet
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.core.rules.RuleCategory
import com.mzstd.hxmyproxy.core.rules.RuleGroup
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.AvatarCircle
import com.mzstd.hxmyproxy.ui.components.BannerLevel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardGrid
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.CountBadge
import com.mzstd.hxmyproxy.ui.components.IconDisc
import com.mzstd.hxmyproxy.ui.components.PageHeader
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.StatusDot
import com.mzstd.hxmyproxy.ui.components.WarnBanner
import com.mzstd.hxmyproxy.ui.components.stdSwitchColors
import com.mzstd.hxmyproxy.ui.theme.AvatarBgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarBgLight
import com.mzstd.hxmyproxy.ui.theme.AvatarFgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarFgLight
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/**
 * 规则页（Bento 重设计，规格=images/html/04-rules.html）：
 * 页头（规则 + 分流·拦截与放行）→ 两大语义区（粉盾圈=拦截 / 蓝地球圈=放行，区头带条数徽章）
 * → 快速拦截卡与白名单卡（胶囊输入行 + 类型徽章条目 + 「管理全部」行）
 * → App 规则集卡（放行/拦截两行粉彩圆形网格 + ⇄ 移行徽标 + 管理入口）。
 * 语义配色纪律：拦截=粉 tertiary、放行=蓝 primary（本页的两极视觉语言，粉在此为语义色非点睛）。
 */
@Composable
fun RulesScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onManage: () -> Unit,
    onOpenRejectDetail: () -> Unit = {},
    onOpenDirectDetail: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val s = ui.settings
    val rejectAcc = rejectAccent()
    val allowAcc = allowAccent()
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
        // 页头：图标圆盘 + 大标题 + 右侧模式小注。
        PageHeader(
            title = stringResource(R.string.nav_rules),
            icon = painterResource(R.drawable.ic_b_filter),
            trailing = { StatLabel(stringResource(R.string.rules_mode_line)) },
        )

        // lockdown 红警示（条件显示）：VPN「无 VPN 时阻断」会掐死直连分流。
        if (ui.share.lockdownSuspected) {
            WarnBanner(
                stringResource(R.string.lockdown_warning),
                level = BannerLevel.Error,
                icon = painterResource(R.drawable.ic_b_alert),
            )
        }

        // ========== 拦截区（粉盾圈；命中即拒绝连接，有没有 VPN 都生效）==========
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ZoneHeader(
                iconRes = R.drawable.ic_b_shield_x,
                accent = rejectAcc,
                title = stringResource(R.string.rules_group_reject),
                sub = stringResource(R.string.rules_reject_zone_sub),
            ) {
                CountBadge(
                    pluralStringResource(R.plurals.count_entries, s.userRejectRules.size, s.userRejectRules.size),
                    fg = rejectAcc.onContainer,
                    bg = rejectAcc.container,
                )
            }
            // 快速拦截单域名/IP/CIDR（对称白名单）→ userReject 表。与放行卡同一模板（用户反馈:两卡必须一致）。
            QuickRuleCard(
                title = stringResource(R.string.rules_reject_quick),
                hint = stringResource(R.string.rules_reject_quick_hint),
                rules = s.userRejectRules,
                accent = rejectAcc,
                trailing = {
                    Switch(
                        checked = s.userRejectEnabled,
                        onCheckedChange = { viewModel.toggleUserRejectEnabled(it) },
                        colors = stdSwitchColors(),
                    )
                },
                onAdd = { viewModel.addUserRejectRule(it) },
                onToggle = { viewModel.toggleUserRejectRule(it) },
                onRemove = { viewModel.removeUserRejectRule(it) },
                onOpenDetail = onOpenRejectDetail,
            )
        }

        // ========== 放行区（蓝地球圈；直连出口，绕过共享 VPN）==========
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ZoneHeader(
                iconRes = R.drawable.ic_b_globe,
                accent = allowAcc,
                title = stringResource(R.string.rules_group_bypass),
                sub = stringResource(R.string.rules_bypass_zone_sub),
            ) {
                CountBadge(
                    pluralStringResource(R.plurals.count_entries, s.userDirectRules.size, s.userDirectRules.size),
                    fg = allowAcc.onContainer,
                    bg = allowAcc.container,
                )
            }
            // IP / 域名白名单（直连）——与拦截卡同模板，整体开关在标题行。
            QuickRuleCard(
                title = stringResource(R.string.rules_module_list),
                hint = stringResource(R.string.rules_user_direct_hint),
                rules = s.userDirectRules,
                accent = allowAcc,
                trailing = {
                    Switch(
                        checked = s.userDirectEnabled,
                        onCheckedChange = { viewModel.toggleUserDirectEnabled(it) },
                        colors = stdSwitchColors(),
                    )
                },
                onAdd = { viewModel.addUserDirectRule(it) },
                onToggle = { viewModel.toggleUserDirectRule(it) },
                onRemove = { viewModel.removeUserDirectRule(it) },
                onOpenDetail = onOpenDirectDetail,
            )
        }

        // ========== App 与服务规则集（灰格圈；每集一个开关，⇄ 在放行/拦截两行间移动）==========
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // 已启用 = 内置组开着的 + 自建集开着的（两行网格里实际生效的数量）。
            val enabledCount = RuleCatalog.appGroups.count { it.id in s.enabledRuleGroups } +
                s.userRuleSets.count { it.enabled }
            ZoneHeader(
                iconRes = R.drawable.ic_b_grid,
                accent = null,
                title = stringResource(R.string.rules_module_apps),
                sub = null,
            ) {
                CountBadge(
                    stringResource(R.string.rules_enabled_count, enabledCount),
                    fg = MaterialTheme.colorScheme.onSurfaceVariant,
                    bg = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            BentoCard(tier = CardTier.Default, contentPadding = 14.dp, spacing = 8.dp) {
                Text(
                    stringResource(R.string.rules_app_sets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 两行式（仿导航栏编辑器，用户设计）：上=放行、下=拦截。圆主体点=停用（从行消失），
                // 右上 ⇄ 徽标=移到另一行（内置组切 rejectedGroups / 自建集切 action）。
                // 64 个内置组平铺会爆——只显示**已启用**的组,全部组去「管理」按分类启用。
                val ra = RuleAction.REJECT
                val bypassDescs = RuleCatalog.appGroups.filter { it.id in s.enabledRuleGroups && it.id !in s.rejectedGroups }.map { g ->
                    RuleCellDesc(
                        g.titleRes, null, groupIcon(g), true,
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
                        g.titleRes, null, groupIcon(g), true,
                        onToggle = { viewModel.toggleRuleGroup(g.id, false) },
                        onSwap = { viewModel.setGroupRejected(g.id, false) },
                    )
                } + s.userRuleSets.filter { it.action == ra }.map { set ->
                    RuleCellDesc(
                        null, set.name, R.drawable.ic_rule_label, set.enabled,
                        onToggle = { viewModel.toggleRuleSet(set.id, !set.enabled) },
                        onSwap = { viewModel.setRuleSetAction(set.id, RuleAction.DIRECT) },
                    )
                }

                // —— 放行行：蓝点行头 + 蓝 ring 网格（末尾「+」添加占位格）——
                GridRowLabel(allowAcc, stringResource(R.string.rules_group_bypass), bypassDescs.size)
                RuleSetRowGrid(bypassDescs, ring = allowAcc.main, onAdd = onManage)

                // —— 拦截行：粉点行头 + 粉 ring 网格（末尾「+」添加占位格）——
                GridRowLabel(rejectAcc, stringResource(R.string.rules_group_reject), rejectDescs.size)
                RuleSetRowGrid(rejectDescs, ring = rejectAcc.main, onAdd = onManage)

                // 管理入口 + 「自建 N」「内置 64 组」徽章。
                OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                    Text(
                        stringResource(R.string.rules_manage),
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(8.dp))
                    CountBadge(
                        stringResource(R.string.ruleset_custom_count, s.userRuleSets.size),
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                        bg = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Spacer(Modifier.size(6.dp))
                    CountBadge(
                        stringResource(R.string.ruleset_builtin_count, RuleCatalog.appGroups.size),
                        fg = MaterialTheme.colorScheme.onSurfaceVariant,
                        bg = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
    }
}

// ---------- 语义配色（拦截=粉 / 放行=蓝，明暗跟主题 tertiary/primary 家族走） ----------

/** 拦截/放行的语义配色组：main=描边/文字强调、onMain=填充按钮字色、container/onContainer=徽章浅底。 */
internal class RuleAccent(val main: Color, val onMain: Color, val container: Color, val onContainer: Color)

/** 拦截粉（tertiary 家族）。本页粉为「拦截」语义色，与 HTML 稿 --rej 系对应。 */
@Composable
internal fun rejectAccent() = RuleAccent(
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.onTertiary,
    MaterialTheme.colorScheme.tertiaryContainer,
    MaterialTheme.colorScheme.onTertiaryContainer,
)

/** 放行蓝（primary 家族），对应 HTML 稿 --alw 系。 */
@Composable
internal fun allowAccent() = RuleAccent(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.onPrimary,
    MaterialTheme.colorScheme.primaryContainer,
    MaterialTheme.colorScheme.onPrimaryContainer,
)

/** 条目分隔发丝线（HTML .prow border-top）。 */
@Composable
internal fun ruleHairline(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

// ---------- 语义区头 / 卡模板 ----------

/**
 * 语义区区头（HTML .zone）：彩色小圆盘 + 标题 + 灰色副题 + 右端计数徽章。
 * [accent]=null 为中性灰区（App 规则集）。
 */
@Composable
private fun ZoneHeader(
    iconRes: Int,
    accent: RuleAccent?,
    title: String,
    sub: String?,
    badge: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconDisc(
            painterResource(iconRes),
            size = 26.dp,
            bg = accent?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest,
            tint = accent?.main ?: MaterialTheme.colorScheme.onSurfaceVariant,
            iconSize = 15.dp,
        )
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) {
            Text(
                sub,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        badge()
    }
}

/**
 * 快速名单卡统一模板（拦截/放行同构，用户反馈:两卡必须一致）：
 * 标题行（放行卡带总开关）→ 说明 → 胶囊输入行 → 等宽格式提示 → 前 2 条预览（类型徽章+等宽字+删除）
 * → 「管理全部」行。全量增删/从历史添加在 [QuickRulesDetailScreen]。
 */
@Composable
private fun QuickRuleCard(
    title: String,
    hint: String,
    rules: List<RuleEntry>,
    accent: RuleAccent,
    trailing: (@Composable () -> Unit)? = null,
    onAdd: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenDetail: () -> Unit,
) {
    // 视觉主角卡（HTML hero/flat 都落 Primary 档：浅色近白、深色 High 浮出）。
    BentoCard(tier = CardTier.Primary, contentPadding = 14.dp, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (trailing != null) trailing()
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RulePillInputRow(accent, onAdd)
        RuleFormatHint()
        rules.sortedForDisplay().take(2).forEach { entry ->
            HorizontalDivider(color = ruleHairline())
            RuleEntryRow(entry, accent, onToggle = { onToggle(entry.value) }, onRemove = { onRemove(entry.value) })
        }
        HorizontalDivider(color = ruleHairline())
        // 「管理全部」行：语义色文字 + 计数徽章 + 右进入尖角。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onOpenDetail)
                .padding(vertical = 7.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.rules_manage_all_plain),
                style = MaterialTheme.typography.labelLarge,
                color = accent.main,
            )
            CountBadge(rules.size.toString(), fg = accent.onContainer, bg = accent.container)
            Spacer(Modifier.weight(1f))
            Icon(
                painterResource(R.drawable.ic_b_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ---------- 胶囊输入行 / 条目行（规则页卡片与二级管理页共用） ----------

/**
 * 胶囊输入行（HTML .inrow）：全圆角 OutlinedTextField + 前置加号 + 右侧语义色填充「添加」按钮。
 * 拦截卡粉钮/放行卡蓝钮；提交后清空输入。
 */
@Composable
internal fun RulePillInputRow(accent: RuleAccent, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val dark = LocalDarkTheme.current
    // 浅灰填充底（HTML --field）：比卡面沉一档，胶囊轮廓在卡上立得住。
    val field = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        singleLine = true,
        // 输入/提示文字用 bodyMedium(14sp)：TextField 默认 16sp 在紧凑卡里偏大、与 bento 不协调。
        textStyle = MaterialTheme.typography.bodyMedium,
        // placeholder 而非 label：label 上浮的顶部预留会顶歪整行（既有结论，见 Compose UI 设计规范）。
        placeholder = {
            Text(
                stringResource(R.string.rules_add_domain),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                painterResource(R.drawable.ic_b_plus),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            Button(
                onClick = { if (input.isNotBlank()) { onAdd(input); input = "" } },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent.main, contentColor = accent.onMain),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.padding(end = 6.dp).height(36.dp),
            ) { Text(stringResource(R.string.rules_add), style = MaterialTheme.typography.labelLarge) }
        },
        // 圆角对齐 app 体系(卡 24 / medium 16 / small 12)——原全圆角胶囊(50)在方正 bento 卡里突兀、圆角对不上。
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = field,
            unfocusedContainerColor = field,
            focusedBorderColor = accent.main,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 等宽格式提示（HTML .fmt）：example.com（含子域）· 1.2.3.4 · 192.168.0.0/16。 */
@Composable
internal fun RuleFormatHint() {
    Text(
        stringResource(R.string.rules_format_hint),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 条目行（HTML .prow）：类型小徽章（域名/IP 段）+ 等宽地址 + 删除图标。 */
@Composable
internal fun RuleEntryRow(entry: RuleEntry, accent: RuleAccent, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RuleTypeChip(entry.value, accent)
        Text(
            entry.value,
            modifier = Modifier.weight(1f).alpha(if (entry.enabled) 1f else 0.4f),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 状态标签（只显示，不可点）：生效=语义色实底，停用=灰底。开关才是操作。
        Text(
            stringResource(if (entry.enabled) R.string.rule_active else R.string.rule_disabled),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (entry.enabled) accent.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (entry.enabled) accent.container else MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
        // 小开关控制启用/停用（缩到小尺寸）：开关=操作，左侧标签=状态。
        Switch(
            checked = entry.enabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.scale(0.72f),
            colors = stdSwitchColors(),
        )
        // 删除用图标按钮：Remove/移除 文字宽度随语言变化导致一列参差（双语检查原则）。
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.rules_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 条目类型小徽章（HTML .tchip）：按现有匹配器分派逻辑判「IP 段」vs「域名」，语义色淡底。 */
@Composable
internal fun RuleTypeChip(rule: String, accent: RuleAccent) {
    // 复用 RuleMatcher.add 的分派判定（数字 IP / CIDR → IP 表），UI 徽章与引擎口径一致。
    val isIp = remember(rule) { IpCidrSet.looksLikeIpOrCidr(rule) }
    Text(
        stringResource(if (isIp) R.string.rules_type_ip else R.string.rules_type_domain),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
        color = accent.main,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.main.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

// ---------- 规则集网格 ----------

/** 网格行头（HTML .glabel）：语义色圆点 + 行名 + 「N 组」徽章。 */
@Composable
private fun GridRowLabel(accent: RuleAccent, name: String, count: Int) {
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(accent.main, size = 7.dp)
        Text(name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        CountBadge(
            stringResource(R.string.rules_groups_count, count),
            fg = accent.onContainer,
            bg = accent.container,
        )
    }
}

/** 规则集网格单元描述（非 composable，避免在 map 里调 composable）。onSwap≠null 显示 ⇄ 徽标（放行↔拦截移行）。 */
private class RuleCellDesc(
    val titleRes: Int?, val titleStr: String?, val iconRes: Int,
    val enabled: Boolean, val onToggle: () -> Unit, val onSwap: (() -> Unit)? = null,
)

/** 内置 App 集的语义图标（避免商标）：音乐/视频/聊天沿用旧 ic_rule_*，其余按分类取 ic_b_* 家族。 */
private fun groupIcon(g: RuleGroup): Int = when (g.category) {
    RuleCategory.SOCIAL -> R.drawable.ic_rule_chat
    RuleCategory.VIDEO -> R.drawable.ic_rule_video
    RuleCategory.MUSIC -> R.drawable.ic_rule_music
    RuleCategory.SHOPPING -> R.drawable.ic_b_shopping
    RuleCategory.PAY -> R.drawable.ic_b_card
    RuleCategory.BANK -> R.drawable.ic_b_bank
    RuleCategory.BROKER -> R.drawable.ic_b_trending
    RuleCategory.TRAVEL -> R.drawable.ic_b_send
    RuleCategory.TOOLS -> R.drawable.ic_b_wrench
    RuleCategory.GAME -> R.drawable.ic_b_gamepad
    RuleCategory.ADS -> R.drawable.ic_b_megaphone
}

/**
 * 规则集圆形图标网格单元（HTML .cell）：粉彩头像圆（板色按位次轮换）+ 语义色 ring（放行蓝/拦截粉，
 * Modifier.border 2dp）+ 名称；停用集灰圆无 ring。点主体切开关，右上 ⇄ 徽标移到另一行。
 */
@Composable
private fun RuleSetGridCell(modifier: Modifier, d: RuleCellDesc, ring: Color, idx: Int) {
    val dark = LocalDarkTheme.current
    val name = d.titleRes?.let { stringResource(it) } ?: d.titleStr ?: ""
    val avBg = if (d.enabled) (if (dark) AvatarBgDark else AvatarBgLight)[idx % 6] else MaterialTheme.colorScheme.surfaceContainerHighest
    val avFg = if (d.enabled) (if (dark) AvatarFgDark else AvatarFgLight)[idx % 6] else MaterialTheme.colorScheme.onSurfaceVariant
    // 外层 Box 不裁剪、留出顶部空间——两个徽标挂外层左右上角，不被点击区 clip 切掉（同 NavTabCell 方案）。
    // 主体不再整格可点停用（旧交互隐蔽/易误触）：停用改由左上红「−」徽标专司，点图标本身无操作。
    Box(modifier.padding(top = 6.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarCircle(
                38.dp,
                avBg,
                modifier = if (d.enabled) Modifier.border(2.dp, ring, CircleShape) else Modifier,
            ) {
                Icon(
                    painterResource(d.iconRes),
                    contentDescription = name,
                    tint = avFg,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(5.dp))
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 红「−」徽标：暂不应用（停用）此规则集。悬于左上角（上移 4dp 进预留区），独立可点——
        // 照导航栏红「−」样式（红底白符 + 描边浮出），把隐蔽的「点格子停用」显性化为明确入口。
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(y = (-4).dp)
                .size(19.dp)
                .clip(CircleShape)
                .background(StatusColors.bad())
                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(onClickLabel = stringResource(R.string.rules_disable_a11y, name)) { d.onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Text("−", color = MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.labelLarge)
        }
        // ⇄ 徽标：把该集移到另一行（放行↔拦截）。悬于右上角（上移 4dp 进预留区），独立可点。
        if (d.onSwap != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-4).dp)
                    .size(19.dp)
                    .clip(CircleShape)
                    .background(if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, ruleHairline(), CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.rules_swap_a11y, name)) { d.onSwap.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_b_swap),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** App 规则集行网格：已启用组格子 + 末尾「+」添加占位格；空行也只显 + 格（引导添加，替代旧「暂无」）。 */
@Composable
private fun RuleSetRowGrid(descs: List<RuleCellDesc>, ring: Color, onAdd: () -> Unit) {
    // null 哨兵 = 末尾「+」格。CardGrid 折叠仍生效（组多时超 2 行折叠，+ 格随最后一页出现）。
    val cells: List<RuleCellDesc?> = descs + listOf(null)
    CardGrid(items = cells.withIndex().toList(), collapsedRows = 2) { m, iv ->
        val d = iv.value
        if (d == null) AddRuleSetCell(m, onAdd)
        else RuleSetGridCell(m, d, ring = ring, idx = iv.index)
    }
}

/** 「+」添加规则集占位格：浅描边圆 + 号 + 「添加」，点击进管理页启用内置组 / 新建自建集。 */
@Composable
private fun AddRuleSetCell(modifier: Modifier, onAdd: () -> Unit) {
    Box(modifier.padding(top = 6.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = stringResource(R.string.rules_manage), onClick = onAdd)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_b_plus),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.size(5.dp))
            Text(
                stringResource(R.string.rules_add),
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
