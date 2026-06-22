package com.mzstd.hxmyproxy.core.proxy

/**
 * 非用户可调的内部时序常量。用户可调的连接/缓冲上限见 [com.mzstd.hxmyproxy.core.model.ConnectionLimits]。
 */
object ProxyTuning {
    /** 上游 TCP 连接建立超时（**单个地址**，逐个回退时累加）。降到 5s 以便不可达目标快速失败。 */
    const val CONNECT_TIMEOUT_MS = 5_000
    /** 握手阶段（SOCKS 协商 / HTTP 请求行+头）读超时，防慢速攻击挂死。 */
    const val HANDSHAKE_TIMEOUT_MS = 15_000
    /** accept backlog。 */
    const val ACCEPT_BACKLOG = 128
}
