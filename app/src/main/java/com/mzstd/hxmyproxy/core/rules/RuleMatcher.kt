package com.mzstd.hxmyproxy.core.rules

/**
 * 规则匹配器：封装域名字典树 [DomainSuffixSet] 与 IP/CIDR 集 [IpCidrSet]，
 * [add]/[matches] 按「是否 IP/CIDR 字面量」自动分派。域名支持三档作用域（见 [RuleScope]）：
 *   `example.com`（全层级）、`*.example.com`（单级）、`=example.com`（精确）；
 * IP/网段走 CIDR 匹配：`1.2.3.4`、`192.168.0.0/16`、`2001:db8::/32`。
 *
 * 非线程安全：构建（[add]）阶段单线程完成后作只读快照交给 [RuleEngine] 匹配。
 */
class RuleMatcher {
    private val domains = DomainSuffixSet()
    private val ips = IpCidrSet()

    /** 总条数（域名 + IP/CIDR），仅供日志/预览。 */
    val size: Int get() = domains.size + ips.size

    /** 添加一条规则，自动识别 IP/CIDR 与域名作用域前缀。 */
    fun add(rule: String) {
        val r = rule.trim()
        if (r.isEmpty()) return
        if (IpCidrSet.looksLikeIpOrCidr(r)) { ips.add(r); return }
        val (scope, bare) = RuleScope.parse(r)
        if (bare.isEmpty()) return
        when (scope) {
            RuleScope.SUFFIX -> domains.addSuffix(bare)
            RuleScope.SINGLE -> domains.addSingle(bare)
            RuleScope.EXACT -> domains.addExact(bare)
        }
    }

    /** [host]（域名或 IP 字面量）是否命中：IP 走 CIDR 匹配、域名走作用域匹配。 */
    fun matches(host: String): Boolean =
        if (IpCidrSet.isIpLiteral(host)) ips.matches(host) else domains.matches(host)

    /**
     * 命中的**具体度**（越大越具体），未命中返回 -1。用于用户规则内部的 most-specific-wins 裁决。
     * 域名取锚定标签数；IP/CIDR 命中给一个高于任何域名的常量——IP 规则本就点名到主机/网段，
     * 且与域名规则不会同时命中同一个 host（host 要么是 IP 字面量要么是域名）。
     */
    fun matchSpecificity(host: String): Int =
        if (IpCidrSet.isIpLiteral(host)) {
            if (ips.matches(host)) IP_SPECIFICITY else -1
        } else {
            domains.matchDepth(host)
        }

    private companion object {
        /** IP/CIDR 命中的具体度常量（域名标签数远小于它）。 */
        const val IP_SPECIFICITY = 1000
    }
}
