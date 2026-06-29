package com.mzstd.hxmyproxy.core.model

/**
 * 默认网络的 VPN 检测结果。
 *
 * - [detected]：系统存在带 `NetworkCapabilities.TRANSPORT_VPN` 的网络（扫**所有**网络判定，非仅默认网络——
 *   bypassable VPN 下默认网络可能解析为底层 WiFi 而漏报）。
 * - [validated]：该 VPN 网络具备 `NET_CAPABILITY_VALIDATED`（已探测到真实公网，非仅建立）。
 *
 * 注意：`TRANSPORT_VPN` 只说明「存在 VPN」，不证明本应用某次出站确实经隧道
 * （split-tunnel/部分路由可能旁路）。因此阻断决策还需出口自检（IP-echo），见 D4。
 */
data class VpnState(
    val detected: Boolean = false,
    val validated: Boolean = false,
    val underlyingTransport: String? = null,
) {
    /** 出口是否可用（已验证有公网）。 */
    val usableEgress: Boolean get() = validated
}
