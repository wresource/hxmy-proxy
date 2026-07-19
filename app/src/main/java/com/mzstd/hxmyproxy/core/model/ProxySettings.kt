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
    // mDNS 默认关:hxmyproxy.local 系统 API 无法注册(解析不到)、DNS-SD 普通用户用不到,已从 UI 移除、后端不发布。
    val mdnsEnabled: Boolean = false,
    /** 备用 DNS（DoH）：系统解析双路全败后经 8.8.8.8/1.1.1.1 的 DoH 端点兜底重试（IP 直连 443）。 */
    val backupDnsEnabled: Boolean = true,
    val authEnabled: Boolean = false,
    /** 反 SSRF：默认放行私网出口；置 true 则连私网也禁（loopback/链路本地始终禁）。 */
    val blockPrivateLanEgress: Boolean = false,
    val preset: PerformancePreset = PerformancePreset.BALANCED,
    val limits: ConnectionLimits = PerformancePreset.BALANCED.toLimits(),
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** 外观：跟随系统/浅色/深色。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 被隐藏的顶层 tab（route 集合，仅监控/规则可隐藏；主页/设置在 UI 层强制保留）。 */
    val hiddenTabs: Set<String> = emptySet(),
    // —— 规则分流（Phase 2）——
    /** 规则分流总开关（默认关：保持「全部走代理」的现有行为，用户在规则页主动开启）。 */
    val ruleEngineEnabled: Boolean = false,
    /** 已启用的内置规则组 ID（见 core/rules）；广告组默认不在内（OISD small 默认关）。 */
    val enabledRuleGroups: Set<String> = emptySet(),
    /** IP/域名白名单整体开关；关掉则整组临时失效（域名走默认 PROXY），列表数据保留。 */
    val userDirectEnabled: Boolean = true,
    /** 用户自定义直连白名单（域名后缀；优先级最高，防误杀）。规则页第一模块的快速白名单。 */
    val userDirectRules: Set<String> = emptySet(),
    /** 用户自定义快速拦截名单（域名/IP/CIDR）；进 userReject 表，优先级次于白名单、高于内置。 */
    val userRejectRules: Set<String> = emptySet(),
    /** 被切成「拦截」动作的内置 app/服务组 id（规则页两行式：位于拦截行的内置组）。 */
    val rejectedGroups: Set<String> = emptySet(),
    /** 用户自建命名规则集（规则集管理界面创建/编辑）。 */
    val userRuleSets: List<com.mzstd.hxmyproxy.core.rules.UserRuleSet> = emptyList(),
    /** 内置集的用户覆盖版（groupId → 域名列表）；有覆盖则装载用它、不读 assets。可「恢复默认」删除。 */
    val ruleSetOverrides: Map<String, List<String>> = emptyMap(),
    /** per-host 三态覆盖（host → 动作）；最高优先级，误拦救济/手动指定。支持泛域名/IP/CIDR。 */
    val hostOverrides: Map<String, com.mzstd.hxmyproxy.core.rules.RuleAction> = emptyMap(),
    /** 自定义规则订阅 URL。 */
    val ruleSubscriptionUrls: Set<String> = emptySet(),
)
