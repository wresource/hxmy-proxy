package com.mzstd.hxmyproxy.core.model

/**
 * 按目标域名聚合的流量快照（隐私：只含 host + 字节，绝不含 path/query/URL/请求内容）。
 * host 来源：HTTP CONNECT 的目标、明文 HTTP 的 Host、SOCKS5 的目标地址；纯内存、不落盘。
 */
data class DomainTraffic(
    val host: String,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val connections: Long = 0,
    val lastSeenAtEpochMs: Long = 0,
)
