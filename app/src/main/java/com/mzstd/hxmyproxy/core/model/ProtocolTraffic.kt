package com.mzstd.hxmyproxy.core.model

/**
 * 按代理协议（HTTP / SOCKS5 / PAC）聚合的流量快照——监控页「按协议」区块用。
 *
 * 每条连接登记时即知道自己走的协议（server 子类的固定常量），据此累加上下行字节与活跃连接数。
 * 隐私上只含协议 + 字节 + 连接数，不涉及任何客户端/目标标识。纯内存、不落盘，随会话 reset 清零。
 */
data class ProtocolTraffic(
    val protocol: ProxyProtocol,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    /** 当前活跃连接数（该协议）。 */
    val activeConnections: Int = 0,
)
