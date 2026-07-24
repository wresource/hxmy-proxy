package com.mzstd.hxmyproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mzstd.hxmyproxy.R
import com.mzstd.hxmyproxy.core.rules.RuleAction

/**
 * 已设 per-host 覆盖徽章：直连=「已设直连」，其余直接标注覆盖动作。
 * 防护卡 / 拦截明细页 / 监控域名行共用——**按设置即时显示**（与是否有运行时流量无关），
 * 让用户设完 DIRECT/PROXY/REJECT 一眼可见规则已生效。
 */
@Composable
fun OverrideBadge(action: RuleAction) {
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
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
