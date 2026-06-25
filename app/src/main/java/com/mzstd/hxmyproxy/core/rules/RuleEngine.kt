package com.mzstd.hxmyproxy.core.rules

/** 规则判定结果。DIRECT=客户端直连绕过（仅对 PAC 客户端有效）、PROXY=走 hxmy 代理、REJECT=拦截。 */
enum class RuleAction { DIRECT, PROXY, REJECT }

/**
 * 规则引擎：按目标 host 判定 直连 / 代理 / 拦截。
 *
 * 来源分层与优先级（高→低，见 v1.1 设计 §3.2）：
 *   用户白名单（direct，防误杀，连广告表都覆盖） > 广告 REJECT > 直连大类/App > 代理大类/App > 兜底 PROXY。
 *
 * 持不可变 [Snapshot]，整体热替换（@Volatile），decide 走无锁读；
 * 各组装载 / 开关变化时构造新 Snapshot 后 [update]。
 */
class RuleEngine {
    /** 一组域名集合的不可变快照。未启用的组传空集（空集 matches 恒 false）即可。 */
    data class Snapshot(
        val userDirect: DomainSuffixSet = DomainSuffixSet(),
        val reject: DomainSuffixSet = DomainSuffixSet(),
        val direct: DomainSuffixSet = DomainSuffixSet(),
        val proxy: DomainSuffixSet = DomainSuffixSet(),
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun update(newSnapshot: Snapshot) { snapshot = newSnapshot }

    /**
     * 判定 [host]（域名或 IP 字面量）。未命中任何组 → 兜底 [RuleAction.PROXY]（决策②：其余走代理）。
     * IP 字面量不会命中域名后缀，自然落到兜底 PROXY。
     */
    fun decide(host: String): RuleAction {
        val s = snapshot
        if (s.userDirect.matches(host)) return RuleAction.DIRECT  // 用户白名单最高优先，防误杀
        if (s.reject.matches(host)) return RuleAction.REJECT      // 广告拦截
        if (s.direct.matches(host)) return RuleAction.DIRECT      // 国内 / 局域网 / App 直连
        if (s.proxy.matches(host)) return RuleAction.PROXY        // 海外 / App 代理
        return RuleAction.PROXY                                    // 兜底：其余走代理
    }
}
