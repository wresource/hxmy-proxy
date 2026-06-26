package com.mzstd.hxmyproxy.core.model

/**
 * 一个可分享入口（host:port + 协议），供 UI 展示、复制、二维码与 PAC 生成。
 *
 * D1 不变量：[mdnsName] 始终与具体 IP [host] 并列出现，IP 永为兜底——
 * 绝不单独发布解析不了的 `hxmyproxy.local`。
 *
 * @param host             具体接口 IP 字面量（如 "192.168.1.34"）。
 * @param mdnsName         便利名（如 "hxmyproxy.local"）；为 null 表示仅 IP。
 * @param sourceInterfaceId 来源 [ShareInterface.id]。
 */
data class ProxyEntry(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val sourceInterfaceId: String,
    val mdnsName: String? = null,
    val priority: Int = 0,
    val reachable: Boolean = true,
) {
    /** 形如 "192.168.1.34:1080" 的 IP 端点（始终可用）。 */
    val ipEndpoint: String get() = "$host:$port"

    /** 便利名端点，如 "hxmyproxy.local:1080"；无 mDNS 时为 null。 */
    val mdnsEndpoint: String? get() = mdnsName?.let { "$it:$port" }
}
