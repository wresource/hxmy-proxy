package com.mzstd.hxmyproxy.core.rules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 规则判定结果。
 * DIRECT=代理出站绑到底层物理网络、绕开共享 VPN（egress=VPN 时拿不到物理网则 fail-closed 断开，不泄漏进 VPN）；
 *   注意 HTTP/SOCKS 客户端流量**仍经本代理转发**，DIRECT 只改变代理自己的出站网络，并非「客户端不走代理」。
 * PROXY=走 hxmy 代理的默认/用户选定出口；REJECT=拦截。
 */
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
        /** 放行表里**最近添加**的那条比拦截表的更新 —— 仅用于具体度完全相同时的平手裁决。 */
        val userDirectNewer: Boolean = true,
        /** 广告表误杀救济表（assets/rules/ads-allowlist.txt）：命中则**跳过** [reject] 判定，
         *  继续按内置直连组/兜底走。见该文件头部说明。 */
        val adsAllow: RuleMatcher = RuleMatcher(),
        val reject: RuleMatcher = RuleMatcher(),
        val direct: RuleMatcher = RuleMatcher(),
        val proxy: RuleMatcher = RuleMatcher(),
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val _version = MutableStateFlow(0)
    /** 每次 [update] +1，供 UI 观察「规则已变、需重判」的刷新信号（decide 仍无锁读 snapshot）。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun update(newSnapshot: Snapshot) { snapshot = newSnapshot; _version.value += 1 }

    /**
     * 判定 [host]（域名或 IP/CIDR 字面量）。未命中任何表 → 兜底 [RuleAction.PROXY]（决策②：其余走代理）。
     * 域名走后缀匹配、IP 走 CIDR 网段匹配（见 [RuleMatcher]）。
     */
    fun decide(host: String): RuleAction = decideDetailed(host).action

    /**
     * 同 [decide]，但**附带命中来源** —— 这是「某个域名为什么被拦/为什么走直连」的唯一答案来源。
     * 排障时只知道结果（如 DIRECT）远远不够：用户移除了自己的规则后仍是 DIRECT，究竟是内置组还在管、
     * 还是父域规则盖住了子域，只有来源能回答。
     */
    fun decideDetailed(host: String): RuleDecision {
        val s = snapshot
        // per-host 三态覆盖：最高优先级（误拦救济/手动指定），三态各一张表
        if (s.ovrDirect.matches(host)) return RuleDecision(RuleAction.DIRECT, RuleSrc.OVERRIDE)
        if (s.ovrReject.matches(host)) return RuleDecision(RuleAction.REJECT, RuleSrc.OVERRIDE)
        if (s.ovrProxy.matches(host)) return RuleDecision(RuleAction.PROXY, RuleSrc.OVERRIDE)
        // 用户的放行 / 拦截两表**同级**，命中多条时按 most-specific-wins 裁决，而不是让某张表整体压过另一张：
        // 先加 `*.apple.com` 放行、后加 `xxx.apple.com` 拦截时，对 xxx.apple.com 应当拦截（后者锚定更深）；
        // 反过来先加具体的 `secret.apple.com` 拦截、后加宽泛的 `*.apple.com` 放行时，具体那条不会被无声抹掉。
        // 具体度相同（同一锚定域名、同一档位）才看 [Snapshot.userDirectNewer] 决定谁更近添加。
        val allow = s.userDirect.matchSpecificity(host)
        val block = s.userReject.matchSpecificity(host)
        if (allow >= 0 || block >= 0) {
            val allowWins = when {
                block < 0 -> true
                allow < 0 -> false
                allow != block -> allow > block
                else -> s.userDirectNewer   // 平手：最近添加的那张表赢
            }
            return if (allowWins) RuleDecision(RuleAction.DIRECT, RuleSrc.USER_ALLOW)
            else RuleDecision(RuleAction.REJECT, RuleSrc.USER_BLOCK)
        }
        // 广告表判定前先过救济表：公共黑名单（oisd）会误收厂商的内容分发域名，而 ADS 又优先于
        // 内置 App 直连组 —— 结果是我们自己精选要直连的域族，子域反被拦掉（微信小程序/图片转圈的根因）。
        // 命中救济表则跳过广告表，继续往下走（通常落到 BUILTIN_APP 直连）。
        if (!s.adsAllow.matches(host) && s.reject.matches(host)) {
            return RuleDecision(RuleAction.REJECT, RuleSrc.BUILTIN_ADS)
        }
        if (s.direct.matches(host)) return RuleDecision(RuleAction.DIRECT, RuleSrc.BUILTIN_APP)
        if (s.proxy.matches(host)) return RuleDecision(RuleAction.PROXY, RuleSrc.BUILTIN_PROXY)
        return RuleDecision(RuleAction.PROXY, RuleSrc.DEFAULT)
    }
}

/** 判定命中的来源表（优先级由高到低，与 [RuleEngine.decideDetailed] 的短路顺序一致）。 */
enum class RuleSrc {
    /** per-host 三态覆盖（防护页误拦救济/手动指定） */ OVERRIDE,
    /** 用户白名单（规则页「放行」） */ USER_ALLOW,
    /** 用户快速拦截（规则页「拦截」） */ USER_BLOCK,
    /** 内置广告/追踪表 */ BUILTIN_ADS,
    /** 内置 App/服务直连组（如 Apple / App Store） */ BUILTIN_APP,
    /** 内置代理组 */ BUILTIN_PROXY,
    /** 未命中任何表，兜底走代理 */ DEFAULT,
}

/** [RuleEngine.decideDetailed] 的结果：动作 + 命中来源。 */
data class RuleDecision(val action: RuleAction, val src: RuleSrc)
