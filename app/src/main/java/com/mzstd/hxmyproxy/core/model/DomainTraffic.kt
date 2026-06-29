package com.mzstd.hxmyproxy.core.model

/**
 * 按「目标域名 × 协议」聚合的流量快照（隐私：只含 host + 协议 + 字节，绝不含 path/query/URL/请求内容）。
 * host 来源：HTTP CONNECT 的目标、明文 HTTP 的 Host、SOCKS5 的目标地址；[protocol] 为该域名经由的代理协议。
 * 同一域名若经不同协议访问则分别成行。纯内存、不落盘。
 */
data class DomainTraffic(
    val host: String,
    val protocol: ProxyProtocol,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val connections: Long = 0,
    val lastSeenAtEpochMs: Long = 0,
)
