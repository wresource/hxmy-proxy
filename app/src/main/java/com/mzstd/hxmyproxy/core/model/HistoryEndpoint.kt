package com.mzstd.hxmyproxy.core.model

/** 用过的代理入口（持久化历史）。 */
data class HistoryEndpoint(
    val protocol: ProxyProtocol,
    val ip: String,
    val port: Int,
    val lastUsedMillis: Long,
) {
    val endpoint: String get() = "$ip:$port"
}

/** UI 视图：历史入口 + 当前是否仍可用（IP 仍是某个接口地址、且端口与当前配置一致）。 */
data class HistoryEndpointView(
    val entry: HistoryEndpoint,
    val available: Boolean,
)
