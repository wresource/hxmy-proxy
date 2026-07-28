package com.mzstd.hxmyproxy.core.model

/**
 * 诊断快照（轻量版，V1）。诊断页据此逐项显示绿/红。
 */
data class DiagnosticsSummary(
    val localNetworkPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val httpPortUp: Boolean = false,
    val socksPortUp: Boolean = false,
    val pacPortUp: Boolean = false,
    // 各协议是否被用户启用——诊断区分「未启用(中性)」与「启用但端口没起来(异常)」,避免关掉某协议就误报红叉。
    val httpEnabled: Boolean = true,
    val socksEnabled: Boolean = true,
    val pacEnabled: Boolean = true,
    val mdnsPublished: Boolean = false,
    val vpnDetected: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
)

/** 各出口 transport 当前在线状态（Dashboard 出口选择卡据此置灰不可用项）。 */
data class EgressStatus(
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val ethernet: Boolean = false,
    val vpn: Boolean = false,
    // 「有能力」位(区别于上面「已激活/在线」位):蜂窝=有 SIM 且就绪(WiFi 在线时蜂窝虽休眠但仍可拉起当出口)、
    // 以太网=网卡已插入(≈ethernet 在线)。出口 chip 可选性依「有能力」,避免蜂窝休眠被误置灰(死锁)。
    val cellularCapable: Boolean = false,
    val ethernetCapable: Boolean = false,
)

/**
 * 段①（客户端 → 本机）链路时延统计。p50 做主数字（抗尾延迟、反映当前常态），
 * p95 反映「最坏时刻」；[samples] 为窗口内有效样本数，为 0 时 UI 应显示占位而非 0ms。
 */
data class LinkStats(
    val p50Ms: Long = 0,
    val p95Ms: Long = 0,
    val samples: Int = 0,
) {
    companion object {
        /** 绿：空载同网段的正常范围上沿（实测量级 4~15ms）。 */
        const val GOOD_MS = 20L
        /** 黄：已明显退化，网页会有顿挫感；超过 [WARN_MS] 转红（TCP+TLS 要 2~3 个 RTT，页面大概率打不开）。 */
        const val WARN_MS = 60L
    }
}

/**
 * 应用对外暴露的聚合状态（单一数据源在 Core，节流后以不可变快照流向 UI）。
 */
data class ShareState(
    val running: Boolean = false,
    val vpn: VpnState = VpnState(),
    val localNetworkPermissionGranted: Boolean = false,
    val interfaces: List<ShareInterface> = emptyList(),
    val recommendedEntries: List<ProxyEntry> = emptyList(),
    /** 准入允许集为空（未选任何网段/所选接口全消失）——即使运行中也拒绝所有新连接（fail-closed）。 */
    val admissionEmpty: Boolean = true,
    val clients: List<ClientSession> = emptyList(),
    /** 目标域名流量 Top-N（按上下行总字节降序）；隐私上只含 host + 协议 + 字节。 */
    val topDomains: List<DomainTraffic> = emptyList(),
    /** 本次共享会话累计的拦截总次数（广告/拒绝规则命中）。 */
    val blockedTotal: Long = 0,
    /** 被拦截域名 Top-N（host + 命中次数）。 */
    val topBlockedDomains: List<BlockedDomain> = emptyList(),
    val diagnostics: DiagnosticsSummary = DiagnosticsSummary(),
    /** 当前活跃连接数。 */
    val activeConnections: Int = 0,
    /** 实时上行/下行速率（字节/秒，约 1s 窗口）。 */
    val uploadRateBps: Long = 0,
    val downloadRateBps: Long = 0,
    /** 本次共享会话累计传输字节（上行+下行）；Start 时归零。 */
    val totalBytes: Long = 0,
    /** 当前上行 Wi-Fi 信号等级 0..4；-1 表示无 Wi-Fi。 */
    val signalLevel: Int = -1,
    val signalDbm: Int = 0,
    /** 段①（客户端 → 本机）链路时延；samples=0 表示尚无样本（无客户端或探测未回）。 */
    val linkStats: LinkStats = LinkStats(),
    /** 疑似失联的最近客户端（曾连过、现探测不可达且无真实入站）；非空时常驻通知切换为告警文案。 */
    val unreachableClients: List<String> = emptyList(),
    /** bind 失败的协议（端口被占用/无效）。运行时改到坏端口会在此提示而非崩溃。 */
    val portBindErrors: Set<ProxyProtocol> = emptySet(),
    /** 疑似系统 VPN lockdown（「阻止无 VPN 连接」）拦了出口分流：底层网络连不通但 VPN 能连。 */
    val lockdownSuspected: Boolean = false,
    /** 各出口网络在线状态（出口选择卡）。 */
    val egressStatus: EgressStatus = EgressStatus(),
    /** 走蜂窝上网且没有可共享入口（没开热点）：提示用户「开启个人热点后才能共享」。 */
    val needsHotspotHint: Boolean = false,
)
