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

/**
 * 应用对外暴露的聚合状态（单一数据源在 Core，节流后以不可变快照流向 UI）。
 */
data class ShareState(
    val running: Boolean = false,
    val vpn: VpnState = VpnState(),
    val localNetworkPermissionGranted: Boolean = false,
    val interfaces: List<ShareInterface> = emptyList(),
    val recommendedEntries: List<ProxyEntry> = emptyList(),
    val clients: List<ClientSession> = emptyList(),
    /** 目标域名流量 Top-N（按上下行总字节降序）；隐私上只含 host + 协议 + 字节。 */
    val topDomains: List<DomainTraffic> = emptyList(),
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
    /** bind 失败的协议（端口被占用/无效）。运行时改到坏端口会在此提示而非崩溃。 */
    val portBindErrors: Set<ProxyProtocol> = emptySet(),
    /** 疑似系统 VPN lockdown（「阻止无 VPN 连接」）拦了出口分流：底层网络连不通但 VPN 能连。 */
    val lockdownSuspected: Boolean = false,
    /** 走蜂窝上网且没有可共享入口（没开热点）：提示用户「开启个人热点后才能共享」。 */
    val needsHotspotHint: Boolean = false,
)
