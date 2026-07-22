package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.ui.theme.AvatarBgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarBgLight
import com.mzstd.hxmyproxy.ui.theme.AvatarFgDark
import com.mzstd.hxmyproxy.ui.theme.AvatarFgLight
import com.mzstd.hxmyproxy.ui.theme.LocalDarkTheme
import com.mzstd.hxmyproxy.ui.theme.StatusColors

/**
 * Bento 设计语言的公共组件库（2026-07 UI 重设计，规格=images/html/ 下的 HTML 稿）。
 * 核心语汇：不等宽栅格「尺寸即层级」、卡片底色三档分深浅、超大 tabular 数字、
 * sparkline/占比条把数据画出来、计数徽章、图标圆盘定卡片身份。
 * 各页面只允许从这里取卡壳/图形件，页面私有形态（盾环等）留在各自文件。
 */

/** 卡片层级：Primary=视觉主角(最亮/最白)、Default=常规、Sunken=沉底配角(嵌在卡内的次级区)。 */
enum class CardTier { Primary, Default, Sunken }

@Composable
private fun tierColor(tier: CardTier): Color {
    val dark = LocalDarkTheme.current
    return when (tier) {
        CardTier.Primary -> if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest
        CardTier.Default -> if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow
        CardTier.Sunken -> if (dark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer
    }
}

/**
 * Bento 卡：圆角 shapes.large(24dp)，层级底色 [tier]，可整卡点击。
 * [container] 显式传入时覆盖 tier（状态渐变底等特殊卡用）。
 */
@Composable
fun BentoCard(
    modifier: Modifier = Modifier,
    tier: CardTier = CardTier.Default,
    container: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.elevatedCardColors(containerColor = container ?: tierColor(tier))
    val inner: @Composable () -> Unit = {
        Column(
            Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
    if (onClick != null) {
        ElevatedCard(onClick = onClick, modifier = modifier, shape = shape, colors = colors) { inner() }
    } else {
        ElevatedCard(modifier = modifier, shape = shape, colors = colors) { inner() }
    }
}

/** 图标圆盘：卡片/区块的身份锚点。默认 32dp 圆、primaryContainer 底、primary 图标。 */
@Composable
fun IconDisc(
    icon: Painter,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    bg: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 18.dp,
) {
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 卡头行：图标圆盘 + titleMedium 标题 + 右侧 trailing 槽（徽章/按钮/小注）。 */
@Composable
fun CardHeader(
    title: String,
    icon: Painter? = null,
    iconBg: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) IconDisc(icon, size = 28.dp, bg = iconBg, tint = iconTint, iconSize = 16.dp)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
    }
}

/** 页头：每个 tab 顶部的标题行（图标圆盘 + 大标题 + 右侧灰色小注/状态胶囊）。 */
@Composable
fun PageHeader(
    title: String,
    icon: Painter? = null,
    iconBg: Color = MaterialTheme.colorScheme.primary,
    iconTint: Color = MaterialTheme.colorScheme.onPrimary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) IconDisc(icon, size = 30.dp, bg = iconBg, tint = iconTint, iconSize = 17.dp)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (trailing != null) trailing()
    }
}

/** 大数字 + 单位的基线组合（速率/计数主角数字）。数字 tabular 由 display 阶自带。 */
@Composable
fun BigStat(
    value: String,
    unit: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueSize: Int = 34,
) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = valueSize.sp, lineHeight = (valueSize + 6).sp),
            color = valueColor,
        )
        if (unit != null) {
            Text(
                unit,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** 全大写风格小标签（bento 卡内 label，10sp 加字距灰色）。窄卡里给 weight 让位时超长省略。 */
@Composable
fun StatLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 迷你趋势图（最近 N 样本）：折线 + 底部渐变面积。[values] 原始值（字节/秒等），内部归一化。
 * 不足 2 点画底线占位（布局稳定，不跳动）。
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val w = size.width
        val h = size.height
        val pts = if (values.size >= 2) values else listOf(0f, 0f)
        val max = (pts.max()).coerceAtLeast(1f)
        val stepX = w / (pts.size - 1)
        val line = Path()
        pts.forEachIndexed { i, v ->
            val x = i * stepX
            // 顶部留 10% 呼吸,底部贴 4% 让静止时线不压边
            val y = h * (1f - 0.86f * (v / max)) - h * 0.04f
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 占比条：满宽轨道 + [fraction] 比例填充（0..1）。Top 域名/客户端流量/拦截排行的量级可视化。 */
@Composable
fun RatioBar(
    fraction: Float,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.005f) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(f)
                    .clip(RoundedCornerShape(50)).background(color),
            )
        }
    }
}

/** 多段占比条（规则集启用占比等）：按 [segments] (fraction, color) 顺序铺满轨道。 */
@Composable
fun SegmentedBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        segments.filter { it.first > 0.004f }.forEach { (f, c) ->
            Box(Modifier.fillMaxHeight().weight(f).background(c))
        }
        val rest = 1f - segments.sumOf { it.first.toDouble() }.toFloat()
        if (rest > 0.004f) Box(Modifier.fillMaxHeight().weight(rest))
    }
}

/** 计数徽章（小胶囊）：「12 条」「4 组」「已选 1/3」。 */
@Composable
fun CountBadge(
    text: String,
    fg: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    bg: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** 协议徽章配色（HTTP=天蓝 / SOCKS5=青 / PAC=薰衣草，取粉彩头像板，明暗自适应）。 */
@Composable
fun protoBadgeColors(protocol: ProxyProtocol): Pair<Color, Color> {
    val dark = LocalDarkTheme.current
    val bg = if (dark) AvatarBgDark else AvatarBgLight
    val fg = if (dark) AvatarFgDark else AvatarFgLight
    val i = when (protocol) {
        ProxyProtocol.HTTP -> 0
        ProxyProtocol.SOCKS5 -> 1
        ProxyProtocol.PAC -> 4
    }
    return bg[i] to fg[i]
}

/** 协议名小徽章（等宽字，入口地址/设置协议行共用）。 */
@Composable
fun ProtoBadge(protocol: ProxyProtocol) {
    val (bg, fg) = protoBadgeColors(protocol)
    Text(
        protocol.name,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** 警示横幅等级。 */
enum class BannerLevel { Warn, Error }

/** 警示横幅：圆角浅底 + 正文，可选 trailing 动作（「去开启 ›」）。风险信息的统一形态。 */
@Composable
fun WarnBanner(
    text: String,
    level: BannerLevel = BannerLevel.Warn,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val bg = when (level) {
        BannerLevel.Warn -> StatusColors.warnContainer()
        BannerLevel.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val fg = when (level) {
        BannerLevel.Warn -> StatusColors.warn()
        BannerLevel.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = fg)
        if (trailing != null) trailing()
    }
}

/** 状态圆点（8-12dp 实心圆）。 */
@Composable
fun StatusDot(color: Color, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** stat 细条里的一项：label 灰小字 + 值加粗，横排。 */
@Composable
fun StatStripItem(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = valueColor,
        )
    }
}
