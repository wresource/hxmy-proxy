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
)
