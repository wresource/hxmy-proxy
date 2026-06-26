package com.mzstd.hxmyproxy.core.proxy

/**
 * 非用户可调的内部时序常量。用户可调的连接/缓冲上限见 [com.mzstd.hxmyproxy.core.model.ConnectionLimits]。
 */
object ProxyTuning {
    /** 上游 TCP 连接建立超时（**单个地址**）。Happy Eyeballs 下各地址并行，总耗时≈最快者，不再逐个累加。 */
    const val CONNECT_TIMEOUT_MS = 5_000
    /** Happy Eyeballs（RFC 8305）连接尝试间隔：起一个地址后等这么久仍未成功，就并行起下一个。 */
    const val HE_ATTEMPT_DELAY_MS = 250
    /** 握手阶段（SOCKS 协商 / HTTP 请求行+头）读超时，防慢速攻击挂死。 */
    const val HANDSHAKE_TIMEOUT_MS = 15_000
    /** HTTP keep-alive 连接两次请求之间的空闲等待；超时则关闭连接释放 FD。 */
    const val KEEPALIVE_IDLE_MS = 15_000
    /** accept backlog。 */
    const val ACCEPT_BACKLOG = 128
}
