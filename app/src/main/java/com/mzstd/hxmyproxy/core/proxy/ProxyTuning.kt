package com.mzstd.hxmyproxy.core.proxy

/**
 * 非用户可调的内部时序常量。用户可调的连接/缓冲上限见 [com.mzstd.hxmyproxy.core.model.ConnectionLimits]。
 */
object ProxyTuning {
    /** 上游 TCP 连接建立超时。 */
    const val CONNECT_TIMEOUT_MS = 10_000
    /** 握手阶段（SOCKS 协商 / HTTP 请求行+头）读超时，防慢速攻击挂死。 */
    const val HANDSHAKE_TIMEOUT_MS = 15_000
    /** accept backlog。 */
    const val ACCEPT_BACKLOG = 128
}
