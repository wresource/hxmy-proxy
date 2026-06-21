package com.mzstd.hxmyproxy.core.model

/**
 * 默认网络的 VPN 检测结果。
 *
 * - [detected]：默认网络具备 `NetworkCapabilities.TRANSPORT_VPN`。
 * - [validated]：默认网络具备 `NET_CAPABILITY_VALIDATED`（已探测到真实公网，非仅建立）。
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
