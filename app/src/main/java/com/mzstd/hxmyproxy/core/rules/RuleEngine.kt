package com.mzstd.hxmyproxy.core.rules

/** 规则判定结果。DIRECT=客户端直连绕过（仅对 PAC 客户端有效）、PROXY=走 hxmy 代理、REJECT=拦截。 */
enum class RuleAction { DIRECT, PROXY, REJECT }

/**
 * 规则引擎：按目标 host 判定 直连 / 代理 / 拦截。
 *
 * 来源分层与优先级（高→低）：
 *   per-host 三态覆盖（ovr*，最高，误拦救济/手动指定） > 用户白名单 direct（防误杀，连广告表都覆盖）
 *   > 用户 reject > 广告 REJECT > 内置 direct > 内置 proxy > 兜底 PROXY。
 *
 * 持不可变 [Snapshot]，整体热替换（@Volatile），decide 走无锁读；
 * 各组装载 / 开关变化时构造新 Snapshot 后 [update]。
 */
class RuleEngine {
    /** 一组域名集合的不可变快照。未启用的组传空集（空集 matches 恒 false）即可。 */
    data class Snapshot(
        // per-host 三态覆盖：最高优先级（误拦救济/手动指定），支持泛域名/IP/CIDR。
        val ovrDirect: RuleMatcher = RuleMatcher(),
        val ovrReject: RuleMatcher = RuleMatcher(),
        val ovrProxy: RuleMatcher = RuleMatcher(),
        val userDirect: RuleMatcher = RuleMatcher(),
        val userReject: RuleMatcher = RuleMatcher(),
        val reject: RuleMatcher = RuleMatcher(),
        val direct: RuleMatcher = RuleMatcher(),
        val proxy: RuleMatcher = RuleMatcher(),
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun update(newSnapshot: Snapshot) { snapshot = newSnapshot }

    /**
     * 判定 [host]（域名或 IP/CIDR 字面量）。未命中任何表 → 兜底 [RuleAction.PROXY]（决策②：其余走代理）。
     * 域名走后缀匹配、IP 走 CIDR 网段匹配（见 [RuleMatcher]）。
     */
    fun decide(host: String): RuleAction {
        val s = snapshot
        // per-host 三态覆盖：最高优先级（误拦救济/手动指定），三态各一张表
        if (s.ovrDirect.matches(host)) return RuleAction.DIRECT
        if (s.ovrReject.matches(host)) return RuleAction.REJECT
        if (s.ovrProxy.matches(host)) return RuleAction.PROXY
        if (s.userDirect.matches(host)) return RuleAction.DIRECT  // 用户白名单/直连集，防误杀
        if (s.userReject.matches(host)) return RuleAction.REJECT  // 用户拦截集
        if (s.reject.matches(host)) return RuleAction.REJECT      // 内置广告拦截
        if (s.direct.matches(host)) return RuleAction.DIRECT      // 内置 App/服务直连
        if (s.proxy.matches(host)) return RuleAction.PROXY        // 内置代理
        return RuleAction.PROXY                                    // 兜底：其余走代理
    }
}
