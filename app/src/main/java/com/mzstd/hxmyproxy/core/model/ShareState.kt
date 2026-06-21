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
    val diagnostics: DiagnosticsSummary = DiagnosticsSummary(),
)
