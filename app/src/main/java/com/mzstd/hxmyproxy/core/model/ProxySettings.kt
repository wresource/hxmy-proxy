package com.mzstd.hxmyproxy.core.model

/**
 * 用户设置（DataStore 持久化的单一来源）。
 *
 * 安全默认（D5）：认证默认关闭（附未认证警告）；反 SSRF 出口护栏默认开启，
 * 但私网（RFC1918）出口默认放行（[blockPrivateLanEgress] = false）以保证广适用性。
 */
data class ProxySettings(
    val httpEnabled: Boolean = true,
    val socksEnabled: Boolean = true,
    val pacEnabled: Boolean = true,
    val httpPort: Int = 8080,
    val socksPort: Int = 1080,
    val pacPort: Int = 8899,
    val selectedInterfaceIds: Set<String> = emptySet(),
    val vpnDownStrategy: VpnDownStrategy = VpnDownStrategy.BLOCK,
    val mdnsEnabled: Boolean = true,
    val authEnabled: Boolean = false,
    /** 反 SSRF：默认放行私网出口；置 true 则连私网也禁（loopback/链路本地始终禁）。 */
    val blockPrivateLanEgress: Boolean = false,
    val preset: PerformancePreset = PerformancePreset.BALANCED,
    val limits: ConnectionLimits = PerformancePreset.BALANCED.toLimits(),
    val language: AppLanguage = AppLanguage.SYSTEM,
    // —— 规则分流（Phase 2）——
    /** 规则分流总开关（默认关：保持「全部走代理」的现有行为，用户在规则页主动开启）。 */
    val ruleEngineEnabled: Boolean = false,
    /** 已启用的内置规则组 ID（见 core/rules）；广告组默认不在内（OISD small 默认关）。 */
    val enabledRuleGroups: Set<String> = emptySet(),
    /** 用户自定义直连白名单（域名后缀；优先级最高，防误杀）。规则页第一模块的快速白名单。 */
    val userDirectRules: Set<String> = emptySet(),
    /** 用户自建命名规则集（规则集管理界面创建/编辑）。 */
    val userRuleSets: List<com.mzstd.hxmyproxy.core.rules.UserRuleSet> = emptyList(),
    /** 自定义规则订阅 URL。 */
    val ruleSubscriptionUrls: Set<String> = emptySet(),
)
