package com.mzstd.hxmyproxy.core.proxy

import java.util.concurrent.ConcurrentHashMap

/**
 * 自探连接的**源端口**标记表：探针 connect 前先 bind 拿到本地端口并登记，accept 侧据此识别
 * 「这条连接是 30s 配对自探本身」——而不是用「remote 是本机地址」猜。
 *
 * 为什么不能按地址猜：本机进程连自己的 LAN IP 走 local 路由（src=该 LAN IP），accept 侧
 * remote==local——与**真实的本机自用代理**（手机上第三方 app / 设备内 nc 验证法 / WiFi 代理指向
 * 自身 LAN IP 的双模式网关用法）完全同形。按地址过滤会把自用流量的记账/拦截计数/accept 落盘
 * 一并抹掉（review 证实的静默回归），按源端口标记则只豁免探针本身。
 *
 * 条目 10s 过期自动清；探针 30s 两条，表恒小。[consume] 一次性（remove），同一连接只匹配一次。
 */
object SelfProbeMarks {
    private val ports = ConcurrentHashMap<Int, Long>()

    private const val TTL_NANOS = 10_000_000_000L // 10s

    /** 探针 connect 前登记本地源端口。不需要显式清除——accept 侧 consume 或 TTL 过期兜底。 */
    fun mark(port: Int) {
        prune()
        ports[port] = System.nanoTime()
    }

    /** accept 侧查询并消费：true=这是自探连接。 */
    fun consume(port: Int): Boolean {
        val t = ports.remove(port) ?: return false
        return System.nanoTime() - t <= TTL_NANOS
    }

    private fun prune() {
        val cutoff = System.nanoTime() - TTL_NANOS
        ports.entries.removeIf { it.value < cutoff }
    }
}
