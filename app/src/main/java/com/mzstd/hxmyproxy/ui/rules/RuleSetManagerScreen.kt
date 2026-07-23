package com.mzstd.hxmyproxy.ui.rules

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.core.rules.RuleCategory
import com.mzstd.hxmyproxy.core.rules.RuleGroup
import com.mzstd.hxmyproxy.core.rules.RuleGroupKind
import com.mzstd.hxmyproxy.core.rules.UserRuleSet
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.AvatarCircle
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.BigStat
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.CountBadge
import com.mzstd.hxmyproxy.ui.components.DetailScaffold
import com.mzstd.hxmyproxy.ui.components.IconDisc
import com.mzstd.hxmyproxy.ui.components.SegmentedBar
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.StatusDot
import com.mzstd.hxmyproxy.ui.components.stdFilterChipColors
import com.mzstd.hxmyproxy.ui.components.stdSwitchColors
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 规则集管理（Bento 稿 06）：hero 汇总卡（N/64 组已启用 + 拦截/放行/未启用 SegmentedBar + 域名总数）+
 * 搜索（本地按名称过滤）+ 我的规则集紧凑行卡（自建、可增删）+ 内置规则集按分类折叠
 * （分类行 → 展开组行 → 域名预览内联展开）。
 */
@Composable
fun RuleSetManagerScreen(ui: MainUiState, viewModel: MainViewModel, onBack: () -> Unit, onEdit: (String, String) -> Unit) {
    var showNew by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 分类展开态（key=RuleCategory.name，默认全部折叠；搜索时强制展开命中分类）
    val expandedCats = remember { mutableStateMapOf<String, Boolean>() }
    val context = LocalContext.current

    val settings = ui.settings
    val byCategory = remember { RuleCatalog.all.groupBy { it.category } }
    val totalGroups = RuleCatalog.all.size
    val enabledIds = settings.enabledRuleGroups
    val enabledGroups = remember(enabledIds) { RuleCatalog.all.filter { it.id in enabledIds } }
    val rejOn = enabledGroups.count { it.kind == RuleGroupKind.REJECT }
    val dirOn = enabledGroups.size - rejOn

    // 内置组域名总数（覆盖版优先；IO 线程统计一次，进页后异步补上）
    val domainsTotal by produceState<Int?>(initialValue = null, settings.ruleSetOverrides) {
        value = countBuiltinDomains(context, settings.ruleSetOverrides)
    }

    // 搜索：纯本地过滤（自建集名 + 内置组名/id）
    val q = query.trim()
    val matchedGroupIds: Set<String>? = if (q.isEmpty()) null else remember(q) {
        RuleCatalog.all
            .filter { context.getString(it.titleRes).contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true) }
            .map { it.id }.toSet()
    }
    val shownUserSets = if (q.isEmpty()) settings.userRuleSets
    else settings.userRuleSets.filter { it.name.contains(q, ignoreCase = true) }

    DetailScaffold(
        title = stringResource(R.string.ruleset_manager_title),
        onBack = onBack,
    ) { padding ->
        // 沉浸式:DetailScaffold 的 padding(TopAppBar+手势条)进 contentPadding,内容可滚入系统栏后方。
        val ld = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(ld),
                end = 16.dp + padding.calculateEndPadding(ld),
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // —— 汇总 hero ——
            item(key = "hero") {
                BuiltinHeroCard(
                    enabled = enabledGroups.size, total = totalGroups,
                    rejOn = rejOn, dirOn = dirOn, domainsTotal = domainsTotal,
                )
            }

            // —— 搜索 ——
            item(key = "search") { RuleSetSearchField(query) { query = it } }

            // —— 我的规则集 ——（搜索无命中时整节隐藏）
            if (q.isEmpty() || shownUserSets.isNotEmpty()) {
                item(key = "my-head") {
                    SectionHeaderRow(
                        label = stringResource(R.string.ruleset_my),
                        badge = "${settings.userRuleSets.size}",
                    ) {
                        NewSetChip { showNew = true }
                    }
                }
                item(key = "my-card") {
                    BentoCard(tier = CardTier.Primary, contentPadding = 12.dp, spacing = 0.dp) {
                        if (shownUserSets.isEmpty()) {
                            Text(
                                stringResource(R.string.ruleset_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        } else {
                            shownUserSets.forEachIndexed { i, set ->
                                if (i > 0) HorizontalDivider()
                                UserRuleSetRow(set, viewModel, onEdit)
                            }
                        }
                    }
                }
            }

            // —— 内置规则集（分类折叠）——
            item(key = "builtin-head") {
                SectionHeaderRow(
                    label = stringResource(R.string.ruleset_builtin),
                    badge = stringResource(R.string.ruleset_groups_cats, totalGroups, byCategory.size),
                ) {
                    // 搜索态强制展开，「全部收起」无意义则隐藏
                    if (q.isEmpty()) {
                        CollapseAllChip { expandedCats.clear() }
                    }
                }
            }
            RuleCategory.entries.forEach { cat ->
                val groups = byCategory[cat] ?: return@forEach
                val visible = matchedGroupIds?.let { m -> groups.filter { it.id in m } } ?: groups
                if (visible.isEmpty()) return@forEach
                val expanded = matchedGroupIds != null || expandedCats[cat.name] == true
                item(key = "cat-${cat.name}") {
                    if (expanded) {
                        ExpandedCategoryCard(
                            cat = cat, groups = visible,
                            enabledIds = enabledIds, overrides = settings.ruleSetOverrides,
                            viewModel = viewModel, onEdit = onEdit,
                            onCollapse = { expandedCats[cat.name] = false },
                        )
                    } else {
                        CollapsedCategoryRow(
                            cat = cat, groups = visible,
                            onCount = visible.count { it.id in enabledIds },
                            onExpand = { expandedCats[cat.name] = true },
                        )
                    }
                }
            }
        }
    }

    if (showNew) {
        NewRuleSetDialog(
            onCreate = { name, action -> viewModel.addRuleSet(name, action); showNew = false },
            onDismiss = { showNew = false },
        )
    }
}

/** hero 汇总卡：大数字「N / 64 组已启用」+ 拦截红/放行蓝/未启用灰 SegmentedBar + 图例 + 域名总数。 */
@Composable
private fun BuiltinHeroCard(enabled: Int, total: Int, rejOn: Int, dirOn: Int, domainsTotal: Int?) {
    BentoCard(tier = CardTier.Primary, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatLabel(stringResource(R.string.ruleset_builtin))
            Spacer(Modifier.weight(1f))
            Icon(
                painterResource(R.drawable.ic_b_layers),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 「12 / 64 组已启用」：%1$d 在中英文都居首，剥出大数字、其余做 dim 单位
        val line = stringResource(R.string.ruleset_enabled_of_total, enabled, total)
        val big = enabled.toString()
        BigStat(value = big, unit = line.removePrefix(big).trim(), valueSize = 32)
        val t = total.coerceAtLeast(1)
        val segments = buildList {
            // 极小占比给 2% 下限，1/64 的拦截段也保持可见（对齐 HTML 固定 7px 段）
            if (rejOn > 0) add((rejOn.toFloat() / t).coerceAtLeast(0.02f) to MaterialTheme.colorScheme.error)
            if (dirOn > 0) add((dirOn.toFloat() / t).coerceAtLeast(0.02f) to MaterialTheme.colorScheme.primary)
        }
        SegmentedBar(segments)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendItem(MaterialTheme.colorScheme.error, "${stringResource(R.string.ruleset_action_reject)} $rejOn")
            LegendItem(MaterialTheme.colorScheme.primary, "${stringResource(R.string.rules_group_bypass)} $dirOn")
            LegendItem(MaterialTheme.colorScheme.surfaceContainerHighest, "${stringResource(R.string.diag_disabled)} ${(total - rejOn - dirOn).coerceAtLeast(0)}")
            Spacer(Modifier.weight(1f))
            Text(
                domainsTotal?.let { stringResource(R.string.ruleset_domains_total, it) } ?: "…",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 图例项：色点 + 「标签 N」。 */
@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusDot(color, size = 6.dp)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 搜索框：本地过滤规则集名，纯 UI 状态。 */
@Composable
private fun RuleSetSearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        placeholder = { Text(stringResource(R.string.ruleset_search_hint)) },
        leadingIcon = {
            Icon(
                painterResource(R.drawable.ic_b_search),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = if (query.isEmpty()) null else {
            {
                IconButton(onClick = { onChange("") }) {
                    Icon(
                        painterResource(R.drawable.ic_b_close),
                        contentDescription = stringResource(R.string.clear_logs),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** 分区头：全大写小标签 + 计数徽章 + 右侧动作槽。 */
@Composable
private fun SectionHeaderRow(label: String, badge: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatLabel(label)
        CountBadge(badge)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 「+ 新建规则集」胶囊。 */
@Composable
private fun NewSetChip(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_b_plus),
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            stringResource(R.string.ruleset_new),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/** 「全部收起」文字动作。 */
@Composable
private fun CollapseAllChip(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_b_chevrons_up),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.ruleset_collapse_all),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** 我的规则集行：图标圆盘 + 名称/动作副行 + 编辑/删除/开关（行为与旧卡一致，仅换紧凑行形态）。 */
@Composable
private fun UserRuleSetRow(set: UserRuleSet, viewModel: MainViewModel, onEdit: (String, String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconDisc(
            painterResource(R.drawable.ic_b_bookmark),
            bg = if (set.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            tint = if (set.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            iconSize = 16.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(set.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${actionLabel(set.action)} · ${stringResource(R.string.ruleset_domains_count, set.domains.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onEdit("user", set.id) }, modifier = Modifier.size(34.dp)) {
            Icon(
                painterResource(R.drawable.ic_b_edit),
                contentDescription = stringResource(R.string.ruleset_edit),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { viewModel.deleteRuleSet(set.id) }, modifier = Modifier.size(34.dp)) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = set.enabled, onCheckedChange = { viewModel.toggleRuleSet(set.id, it) }, colors = stdSwitchColors())
    }
}

/** 分类头行（折叠行与展开卡头共用）：图标圆盘 + 类名 + [拦截]「N 组」「N 开」徽章 + chevron。 */
@Composable
private fun CategoryHeaderRow(
    cat: RuleCategory,
    groups: List<RuleGroup>,
    onCount: Int,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconDisc(
            painterResource(catIconRes(cat)),
            size = 28.dp,
            bg = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            iconSize = 15.dp,
        )
        Text(
            stringResource(cat.titleRes),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (groups.any { it.kind == RuleGroupKind.REJECT }) {
            // 拦截类徽章：危险语义一律 error 红
            CountBadge(
                stringResource(R.string.ruleset_action_reject),
                fg = MaterialTheme.colorScheme.onErrorContainer,
                bg = MaterialTheme.colorScheme.errorContainer,
            )
        }
        CountBadge(stringResource(R.string.rules_groups_count, groups.size))
        if (onCount > 0) {
            CountBadge(
                stringResource(R.string.ruleset_on_count, onCount),
                fg = MaterialTheme.colorScheme.onPrimaryContainer,
                bg = MaterialTheme.colorScheme.primaryContainer,
            )
        }
        Icon(
            painterResource(if (expanded) R.drawable.ic_b_chevron_up else R.drawable.ic_b_chevron_down),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 折叠态分类行：整卡可点展开。 */
@Composable
private fun CollapsedCategoryRow(cat: RuleCategory, groups: List<RuleGroup>, onCount: Int, onExpand: () -> Unit) {
    BentoCard(tier = CardTier.Default, onClick = onExpand, contentPadding = 12.dp, spacing = 0.dp) {
        CategoryHeaderRow(cat, groups, onCount, expanded = false)
    }
}

/** 展开态分类卡：可点收起的类头 + 分隔线组行。 */
@Composable
private fun ExpandedCategoryCard(
    cat: RuleCategory,
    groups: List<RuleGroup>,
    enabledIds: Set<String>,
    overrides: Map<String, List<String>>,
    viewModel: MainViewModel,
    onEdit: (String, String) -> Unit,
    onCollapse: () -> Unit,
) {
    BentoCard(tier = CardTier.Primary, contentPadding = 12.dp, spacing = 0.dp) {
        CategoryHeaderRow(
            cat, groups, onCount = groups.count { it.id in enabledIds }, expanded = true,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onCollapse)
                .padding(vertical = 2.dp),
        )
        groups.forEachIndexed { i, group ->
            if (i > 0) HorizontalDivider()
            BuiltinGroupRow(group, group.id in enabledIds, overrides[group.id], viewModel, onEdit)
        }
    }
}

/** 内置组行：AvatarCircle + 名称/来源副行 + 条数徽章 + 编辑铅笔（可编辑组）+ 开关；点行内联展开域名预览。 */
@Composable
private fun BuiltinGroupRow(
    group: RuleGroup,
    enabled: Boolean,
    overrideDomains: List<String>?,
    viewModel: MainViewModel,
    onEdit: (String, String) -> Unit,
) {
    var previewShown by remember(group.id) { mutableStateOf(false) }
    val context = LocalContext.current
    // 条数徽章与预览共用一次异步加载：覆盖版直接取，否则 IO 线程读 assets
    val preview by produceState<Pair<Int, List<String>>?>(initialValue = null, group.id, overrideDomains) {
        value = overrideDomains?.let { it.size to it.take(30) } ?: loadPreview(context, group.assetPath)
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { previewShown = !previewShown }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarCircle(
                size = 32.dp,
                bg = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Icon(
                    painterResource(catIconRes(group.category)),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(group.sourceRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CountBadge(preview?.let { pluralStringResource(R.plurals.count_entries, it.first, it.first) } ?: "…")
            if (group.editable) {
                IconButton(onClick = { onEdit("builtin", group.id) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_b_edit),
                        contentDescription = stringResource(R.string.ruleset_edit),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = { viewModel.toggleRuleGroup(group.id, it) }, colors = stdSwitchColors())
        }
        if (previewShown) DomainPreviewPanel(preview) { previewShown = false }
    }
}

/** 域名预览面板（内联沉底区）：「域名预览 · 共 N 条」+ 收起 + 等宽域名串。 */
@Composable
private fun DomainPreviewPanel(preview: Pair<Int, List<String>>?, onCollapse: () -> Unit) {
    val dark = LocalDarkTheme.current
    val sunkenBg = if (dark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 42.dp, bottom = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(sunkenBg)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatLabel(preview?.let { stringResource(R.string.ruleset_domain_preview, it.first) } ?: "…")
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onCollapse)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    stringResource(R.string.monitor_collapse),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    painterResource(R.drawable.ic_b_chevron_up),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (preview != null) {
            val joined = preview.second.joinToString(" · ") + if (preview.first > preview.second.size) " …" else ""
            Text(
                joined,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewRuleSetDialog(onCreate: (String, RuleAction) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.DIRECT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, action) }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.ruleset_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.ruleset_name)) }, shape = MaterialTheme.shapes.large)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chipColors = stdFilterChipColors()
                    FilterChip(selected = action == RuleAction.DIRECT, onClick = { action = RuleAction.DIRECT }, colors = chipColors, label = { Text(stringResource(R.string.ruleset_action_direct)) })
                    FilterChip(selected = action == RuleAction.REJECT, onClick = { action = RuleAction.REJECT }, colors = chipColors, label = { Text(stringResource(R.string.ruleset_action_reject)) })
                }
            }
        },
    )
}

@Composable
private fun actionLabel(action: RuleAction): String = stringResource(
    when (action) {
        RuleAction.REJECT -> R.string.ruleset_action_reject
        else -> R.string.ruleset_action_direct
    },
)

/** 分类 → 图标（Bento 稿 06 分类语义；社交/视频/音乐沿用旧 ic_rule_*）。 */
private fun catIconRes(cat: RuleCategory): Int = when (cat) {
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
    RuleCategory.ADS -> R.drawable.ic_b_block
}

/** 读 assets 清单预览（纯 .txt；返回总条数 + 前 [limit] 条）。 */
private suspend fun loadPreview(context: Context, assetPath: String, limit: Int = 30): Pair<Int, List<String>> =
    withContext(Dispatchers.IO) {
        var total = 0
        val sample = ArrayList<String>(limit)
        runCatching {
            context.assets.open(assetPath).bufferedReader().forEachLine { line ->
                val d = line.trim()
                if (d.isNotEmpty() && d[0] != '#') {
                    total++
                    if (sample.size < limit) sample.add(d)
                }
            }
        }
        total to sample
    }

/** 统计全部内置组域名总数（覆盖版优先；hero 卡「共 N 个域名」）。 */
private suspend fun countBuiltinDomains(context: Context, overrides: Map<String, List<String>>): Int =
    withContext(Dispatchers.IO) {
        RuleCatalog.all.sumOf { g ->
            overrides[g.id]?.size ?: runCatching {
                var n = 0
                context.assets.open(g.assetPath).bufferedReader().forEachLine { line ->
                    val d = line.trim()
                    if (d.isNotEmpty() && d[0] != '#') n++
                }
                n
            }.getOrDefault(0)
        }
    }
