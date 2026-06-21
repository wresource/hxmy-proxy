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
)
