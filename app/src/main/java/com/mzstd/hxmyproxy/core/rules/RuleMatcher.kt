package com.mzstd.hxmyproxy.core.rules

/**
 * 规则匹配器：封装域名后缀树 [DomainSuffixSet] 与 IP/CIDR 集 [IpCidrSet]，
 * [add]/[matches] 按「是否 IP/CIDR 字面量」自动分派——泛域名走后缀匹配（自身+子域），
 * IP/网段走 CIDR 匹配。用户可在同一规则集里混写：
 *   `example.com`（含子域）、`*.example.com`、`1.2.3.4`、`192.168.0.0/16`、`2001:db8::/32`。
 *
 * 非线程安全：构建（[add]）阶段单线程完成后作只读快照交给 [RuleEngine] 匹配。
 */
class RuleMatcher {
    private val domains = DomainSuffixSet()
    private val ips = IpCidrSet()

    /** 总条数（域名 + IP/CIDR），仅供日志/预览。 */
    val size: Int get() = domains.size + ips.size

    /** 添加一条规则，自动识别 IP/CIDR 与泛域名（去 "*." 前缀，后缀语义匹配自身+子域）。 */
    fun add(rule: String) {
        val r = rule.trim()
        if (r.isEmpty()) return
        if (IpCidrSet.looksLikeIpOrCidr(r)) ips.add(r)
        else domains.addSuffix(r.removePrefix("*."))
    }

    /** [host]（域名或 IP 字面量）是否命中：IP 走 CIDR 匹配、域名走后缀匹配。 */
    fun matches(host: String): Boolean =
        if (IpCidrSet.isIpLiteral(host)) ips.matches(host) else domains.matches(host)
}
