package com.mzstd.hxmyproxy.core.model

/**
 * 快速拦截/白名单的单条规则条目——带启用状态与时间戳，支持「停用而不删除」。
 *
 * @param value      域名/IP/CIDR/IPv6 字面量（已归一化：小写、去 `*.` 前缀）。
 * @param enabled    是否参与判定。停用=不进 RuleEngine 表（对该域名不做任何操作、走默认判定），
 *                   **不等于切到反面动作**（停用 block ≠ allow，停用 allow ≠ block）。
 * @param addedAt    添加时间(ms)。启用条目按此**升序**展示（添加先后）。
 * @param disabledAt 停用时间(ms)。停用条目按此**降序**展示（最近停用在前）；0=从未停用。
 */
data class RuleEntry(
    val value: String,
    val enabled: Boolean = true,
    val addedAt: Long = 0L,
    val disabledAt: Long = 0L,
) {
    companion object {
        /** 展示排序：启用(按 addedAt 升序)在前，停用(按 disabledAt 降序、最近停用在前)在后。 */
        fun List<RuleEntry>.sortedForDisplay(): List<RuleEntry> = sortedWith(
            compareByDescending<RuleEntry> { it.enabled }.thenComparator { a, b ->
                if (a.enabled) a.addedAt.compareTo(b.addedAt) else b.disabledAt.compareTo(a.disabledAt)
            },
        )
    }
}
