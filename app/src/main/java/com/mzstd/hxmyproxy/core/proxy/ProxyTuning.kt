package com.mzstd.hxmyproxy.core.proxy

/**
 * 非用户可调的内部时序常量。用户可调的连接/缓冲上限见 [com.mzstd.hxmyproxy.core.model.ConnectionLimits]。
 */
object ProxyTuning {
    /**
     * 上游 TCP 连接建立超时（**单个地址**）。Happy Eyeballs 下各地址并行，总耗时≈最快者，不再逐个累加。
     * 2.5s：DNS 污染/被墙环境下解析出的死 IP 要快速放弃（旧 8s 会让 Claude CLI 等每个死 IP 干等 8s，
     * 换网络才恢复——性能大忌）。正常 TCP 握手 <1s、经 VPN 跨 CDN 也罕见 >2s，2.5s 足够且不误杀。
     */
    const val CONNECT_TIMEOUT_MS = 2_500
    /** Happy Eyeballs（RFC 8305）连接尝试间隔：起一个地址后等这么久仍未成功，就并行起下一个。 */
    const val HE_ATTEMPT_DELAY_MS = 250
    /** 握手阶段（SOCKS 协商 / HTTP 请求行+头）读超时，防慢速攻击挂死。 */
    const val HANDSHAKE_TIMEOUT_MS = 15_000
    /** HTTP keep-alive 连接两次请求之间的空闲等待；超时则关闭连接释放 FD。 */
    const val KEEPALIVE_IDLE_MS = 15_000
    /** accept backlog。 */
    const val ACCEPT_BACKLOG = 128

    /**
     * **建连阶段**（DNS 解析 + Happy Eyeballs 建连）的总上限，不含之后的 relay。
     *
     * 此前这一整段**没有任何 deadline**：`soTimeout` 只覆盖「读请求行 + 读头」，一进 handleConnect
     * 就再无上限。于是一次慢解析能把协程按住几十秒，而客户端早已超时放弃——**服务端却还在为
     * 一条已死的连接继续烧 DNS/connect 线程**，用户手动重试又叠一批，旧的不退场、新的不断入队，
     * 一次瞬时拥塞就此变成自我维持的拥塞（0804 实测：6 并发卡满 45~60s，之后才自行恢复）。
     *
     * 取 10s 的依据：正常路径上 DNS 单步已被 [OutboundConnector] 的 1.5s deadline 兜住，
     * Happy Eyeballs 最坏 6 个候选交错 250ms、每个 [CONNECT_TIMEOUT_MS]=2.5s，约 3.75s 收敛；
     * 10s 给互援/DoH 兜底留了余量，又远小于客户端的常见超时，能保证**代理先于客户端放弃**
     * ——这一点很重要：由代理回一个 504，客户端才知道发生了什么，而不是干等到自己超时。
     */
    const val CONNECT_PHASE_TIMEOUT_MS = 10_000L
}
