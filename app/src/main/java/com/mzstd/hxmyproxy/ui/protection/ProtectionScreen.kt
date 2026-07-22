package com.mzstd.hxmyproxy.ui.protection

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleCatalog
import com.mzstd.hxmyproxy.ui.MainUiState
import com.mzstd.hxmyproxy.ui.MainViewModel
import com.mzstd.hxmyproxy.ui.components.BentoCard
import com.mzstd.hxmyproxy.ui.components.CardTier
import com.mzstd.hxmyproxy.ui.components.HostOverrideDialog
import com.mzstd.hxmyproxy.ui.components.IconDisc
import com.mzstd.hxmyproxy.ui.components.PageHeader
import com.mzstd.hxmyproxy.ui.components.RatioBar
import com.mzstd.hxmyproxy.ui.components.StatLabel
import com.mzstd.hxmyproxy.ui.components.StatusDot
import com.mzstd.hxmyproxy.ui.components.stdSwitchColors
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import com.mzstd.hxmyproxy.ui.theme.StatusColors
import java.text.NumberFormat

/**
 * 防护 tab（Bento 重设计，规格=images/html/03-protection.html）：把"拦截"这条线做成完整体验——
 * 盾环 hero 大数字 + 拦得最多排行（比例条+三态救济入口）+ OISD 总开关 + "防护如何工作"微网格。
 * 让用户明确看到"它真的在拦、没 VPN 也能用"。粉（tertiary）在此页做点睛主角。
 */
@Composable
fun ProtectionScreen(
    ui: MainUiState,
    viewModel: MainViewModel,
    onOpenBlockedDetail: () -> Unit,
    contentPadding: PaddingValues,
) {
    val adGroup = RuleCatalog.ADS_OISD
    val adBlockOn = adGroup.id in ui.settings.enabledRuleGroups
    val blocked = ui.share.blockedTotal
    var editHost by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 页头：粉盾圆盘 + 标题 + 「本次会话 · 实时」小注。
        PageHeader(
            title = stringResource(R.string.protection_title),
            icon = painterResource(R.drawable.ic_b_shield),
            iconBg = MaterialTheme.colorScheme.tertiary,
            iconTint = MaterialTheme.colorScheme.onTertiary,
            trailing = { StatLabel(stringResource(R.string.protection_mode_line)) },
        )

        // Hero：盾环 + 56sp 粉大数字。开关态保留——关掉后圆点/数字/盾环整体退灰。
        BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Primary, contentPadding = 18.dp, spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowDot(if (adBlockOn) StatusColors.runningDot() else StatusColors.stoppedDot())
                StatLabel(stringResource(if (adBlockOn) R.string.protection_hero_label else R.string.protection_hero_label_off))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            ) {
                ShieldRing(active = adBlockOn, modifier = Modifier.size(128.dp))
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            NumberFormat.getIntegerInstance().format(blocked),
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 56.sp,
                                lineHeight = 58.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-1).sp,
                            ),
                            color = if (adBlockOn && blocked > 0) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            stringResource(R.string.protection_unit_times),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 9.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.protection_hero_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 拦得最多：排名 + 等宽域名 + 覆盖徽章 + ×次数 + tune；粉 RatioBar 按次数归一（榜首满宽）。
        // 卡头 chip=拦截明细入口（替代旧全宽按钮），空态也保留卡与入口。
        BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Default, spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatLabel(stringResource(R.string.protection_top_blocked))
                Spacer(Modifier.weight(1f))
                DetailChip(stringResource(R.string.protection_view_blocked), onOpenBlockedDetail)
            }
            val top = ui.share.topBlockedDomains.take(5)
            if (top.isEmpty()) {
                Text(
                    stringResource(R.string.monitor_no_blocked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            } else {
                val maxCount = top.maxOf { it.count }.coerceAtLeast(1L)
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    top.forEachIndexed { i, b ->
                        TopBlockedRow(
                            rank = i + 1,
                            host = b.host,
                            count = b.count,
                            fraction = b.count.toFloat() / maxCount,
                            ovr = ui.settings.hostOverrides[b.host],
                            highlight = i == 0,
                            onClick = { editHost = b.host },
                        )
                    }
                }
                // 脚注：其他来源计数 + 「点右侧调节可放行」的救济提示（HTML .tnote）。
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_b_info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.protection_top_note, (blocked - top.sumOf { it.count }).coerceAtLeast(0L)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // OISD 规则组总开关：粉图标圆盘 + 标准 Switch 配色。
        BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Default, contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconDisc(
                    painterResource(R.drawable.ic_b_shield_plus),
                    size = 34.dp,
                    bg = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.tertiary,
                    iconSize = 18.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.rule_ads_oisd), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.protection_adblock_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = adBlockOn,
                    onCheckedChange = { viewModel.toggleRuleGroup(adGroup.id, it) },
                    colors = stdSwitchColors(),
                )
            }
        }

        // 防护如何工作：沉底卡 + 2×2 微网格（图标+短句），替代旧长段落。
        BentoCard(Modifier.fillMaxWidth(), tier = CardTier.Sunken, contentPadding = 14.dp, spacing = 9.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    painterResource(R.drawable.ic_b_help),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                StatLabel(stringResource(R.string.protection_how_title))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HowItem(R.drawable.ic_b_filter, R.string.protection_how_1, Modifier.weight(1f))
                HowItem(R.drawable.ic_b_devices, R.string.protection_how_2, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HowItem(R.drawable.ic_b_wifi, R.string.protection_how_3, Modifier.weight(1f))
                HowItem(R.drawable.ic_b_undo, R.string.protection_how_4, Modifier.weight(1f))
            }
        }
    }
    // 三态救济弹窗（与拦截明细页同款）：点排行行/tune 触发。
    editHost?.let { host ->
        HostOverrideDialog(
            host = host,
            current = ui.settings.hostOverrides[host],
            onSet = { a -> viewModel.setHostOverride(host, a); editHost = null },
            onClear = { viewModel.clearHostOverride(host); editHost = null },
            onDismiss = { editHost = null },
        )
    }
}

/**
 * 盾环（页面私有形态，规格=03 稿 hero）：浅粉轨道 + 渐变粉弧（起点 132°、扫 276°，
 * 固定装饰弧长、不表示比例）+ 环心盾徽。关闭防护时整环与盾退灰。
 */
@Composable
private fun ShieldRing(active: Boolean, modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val pink = MaterialTheme.colorScheme.tertiary
    // 渐变亮端：比 tertiary 更亮的糖果粉（HTML --ring-a，明暗各一，仅此形态使用）。
    val pinkBright = if (dark) Color(0xFFFF8FAD) else Color(0xFFE8447F)
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val track = if (active) pink.copy(alpha = if (dark) 0.16f else 0.10f) else inactive.copy(alpha = 0.45f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawCircle(track, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            if (active) {
                val inset = stroke / 2f
                drawArc(
                    brush = Brush.linearGradient(listOf(pinkBright, pink)),
                    startAngle = 132f,
                    sweepAngle = 276f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_b_shield_check),
            contentDescription = null,
            tint = if (active) pink else inactive,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** 运行圆点 + 外圈光晕（HTML .dot 的 box-shadow 光环）。 */
@Composable
private fun GlowDot(color: Color) {
    Box(Modifier.size(16.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        StatusDot(color, size = 8.dp)
    }
}

/** 卡头「查看拦截明细」chip 入口（替代旧全宽按钮）：primaryContainer 小胶囊 + 右尖角。 */
@Composable
private fun DetailChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Icon(
            painterResource(R.drawable.ic_b_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * 拦得最多单行：排名 + 等宽域名（+覆盖徽章）+ ×次数 + tune 提示，下衬粉 RatioBar。
 * 行整体可点=三态救济入口；tune 图标是可见的「可调整」提示（用户反馈：不然谁知道要点）。
 */
@Composable
private fun TopBlockedRow(
    rank: Int,
    host: String,
    count: Long,
    fraction: Float,
    ovr: RuleAction?,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$rank",
                modifier = Modifier.width(16.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    host,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ovr != null) OverrideBadge(ovr)
            }
            Text(
                "×$count",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
                color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painterResource(R.drawable.ic_tune),
                contentDescription = stringResource(R.string.override_adjust),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        // 比例条与域名列左对齐（缩进=排名宽 16 + 间距 8）。
        RatioBar(
            fraction = fraction,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(start = 24.dp),
            height = 6.dp,
        )
    }
}

/** 已设覆盖徽章（防护卡/拦截明细页共用）：直连=「已设直连」，其余直接标注覆盖动作。 */
@Composable
internal fun OverrideBadge(action: RuleAction) {
    Text(
        stringResource(
            when (action) {
                RuleAction.DIRECT -> R.string.protection_set_direct
                RuleAction.PROXY -> R.string.override_proxy
                RuleAction.REJECT -> R.string.override_reject
            },
        ),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 「防护如何工作」微网格单元：小图标 + 短句（EN 较长，允许两行）。 */
@Composable
private fun HowItem(@DrawableRes icon: Int, @StringRes text: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp),
        )
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
