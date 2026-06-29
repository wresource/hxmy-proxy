package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ClientSession
import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.model.ProtocolTraffic
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * 流量记账：按客户端 IP 与目标域名聚合上下行字节，喂给监控页的「会话列表 / 域名 Top-N」。
 *
 * 设计要点：
 * - **热路径零锁**：[ConnTracker.add] 只对 [LongAdder] 累加（无锁、cell 分片）；`ConcurrentHashMap`
 *   的 computeIfAbsent 仅在开连接 / 首次绑 host 各走一次，不在搬字节循环里。
 * - **隐私**：只记 host + 字节，绝不碰 path/query/URL/请求内容；纯内存、不落盘，随 [reset] 清空。
 * - **内存有界**：域名 Top-N 封顶（[maxDomains]）+ "(其他)" 兜底桶 + 空闲老化（[ageOut]），三重堵死无界增长。
 * - **不破坏全局速率仪表**：每次 add 同时把增量喂 [globalSink]（→ totalUp/totalDown）。
 * - 读（[snapshot]）仅在单线程 ticker，[LongAdder.sum] 弱一致足够（统计语义可容忍 ±1s）。
 */
class TrafficAccounting(
    @Volatile var maxDomains: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
    private val globalSink: (Long, Long) -> Unit = { _, _ -> },
) {
    private val perClient = ConcurrentHashMap<InetAddress, Acc>()
    private val perDomain = ConcurrentHashMap<String, Acc>()
    // 按协议（HTTP/SOCKS5/PAC）聚合：键就 3 个、常驻不老化，监控页「按协议」区块用。
    private val perProtocol = ConcurrentHashMap<ProxyProtocol, Acc>()
    // 上次快照各 IP 累计，用于在单线程 ticker 内做 1s 速率差分（仅 ticker 访问，无需同步）。
    private val lastClientBytes = HashMap<InetAddress, LongArray>()

    /** 每条连接开始时建一次，绑定 [clientIp] 与 [protocol]；host 解析出来后再 [ConnTracker.bindHost]。 */
    fun openConnection(clientIp: InetAddress, protocol: ProxyProtocol): ConnTracker = ConnTracker(clientIp, protocol)

    inner class ConnTracker(clientIp: InetAddress, protocol: ProxyProtocol) {
        private val clientAcc = perClient.computeIfAbsent(clientIp) { Acc(clientIp.hostAddress ?: "?") }
            .also { it.conns.increment() }
        // 协议固定（不像 host 会随 keep-alive 切换），开连接时一次性绑定。
        private val protocolAcc = perProtocol.computeIfAbsent(protocol) { Acc(protocol.name) }
            .also { it.conns.increment() }
        @Volatile private var domainAcc: Acc? = null

        /** 目标解析出来后调用；按当前 host 归属（keep-alive 多 host 时覆盖到最新请求的 host）。幂等。 */
        fun bindHost(host: String) {
            val key = host.lowercase()
            val cur = domainAcc
            if (cur != null && cur.key == key) return
            // keep-alive 多 host 切换：先释放旧域名的连接计数，否则旧域名 conns 虚高 → 永不满足 ageOut → 内存泄漏。
            cur?.conns?.decrement()
            // 容量封顶：满了且是新 host → 计入 "(其他)" 兜底桶，字节不丢、内存不爆。
            val acc = perDomain[key]
                ?: if (perDomain.size >= maxDomains) perDomain.computeIfAbsent(OTHERS) { Acc(OTHERS) }
                else perDomain.computeIfAbsent(key) { Acc(key) }
            acc.conns.increment()
            acc.lastSeen = clock()
            domainAcc = acc
        }

        /** 热路径：每搬一块字节后调用，纯原子加；同时喂全局速率仪表。 */
        fun add(up: Long, down: Long) {
            if (up > 0) { clientAcc.up.add(up); protocolAcc.up.add(up); domainAcc?.up?.add(up) }
            if (down > 0) { clientAcc.down.add(down); protocolAcc.down.add(down); domainAcc?.down?.add(down) }
            val now = clock()
            clientAcc.lastSeen = now
            protocolAcc.lastSeen = now
            domainAcc?.let { it.lastSeen = now }
            globalSink(up, down)
        }

        /** 连接结束：活跃连接数 -1（累计字节保留到老化淘汰）。 */
        fun close() {
            clientAcc.conns.decrement()
            protocolAcc.conns.decrement()
            domainAcc?.conns?.decrement()
        }
    }

    /** 单线程 ticker 调用：产出客户端会话与域名 Top-N 快照（客户端含 1s 窗口速率差分）。 */
    fun snapshot(topN: Int): Snapshot {
        val clients = perClient.map { (ip, a) ->
            val up = a.up.sum(); val down = a.down.sum()
            val last = lastClientBytes[ip]
            val upRate = if (last != null) (up - last[0]).coerceAtLeast(0) else 0
            val downRate = if (last != null) (down - last[1]).coerceAtLeast(0) else 0
            lastClientBytes[ip] = longArrayOf(up, down)
            ClientSession(
                clientIp = ip,
                interfaceId = "",
                activeConnections = a.conns.sum().toInt().coerceAtLeast(0),
                uploadBytes = up,
                downloadBytes = down,
                uploadRateBps = upRate,
                downloadRateBps = downRate,
                lastSeenAtEpochMs = a.lastSeen,
            )
        }.sortedByDescending { it.uploadBytes + it.downloadBytes }
        lastClientBytes.keys.retainAll(perClient.keys)   // 清理已不在的 IP 的差分缓存

        val domains = perDomain.values.map { a ->
            DomainTraffic(a.key, a.up.sum(), a.down.sum(), a.conns.sum().coerceAtLeast(0), a.lastSeen)
        }.sortedByDescending { it.uploadBytes + it.downloadBytes }.take(topN)

        // 按协议：固定按枚举顺序（HTTP/SOCKS5/PAC），只列出现过的协议；UI 顺序稳定不跳动。
        val protocols = ProxyProtocol.values().mapNotNull { p ->
            val a = perProtocol[p] ?: return@mapNotNull null
            ProtocolTraffic(p, a.up.sum(), a.down.sum(), a.conns.sum().toInt().coerceAtLeast(0))
        }
        return Snapshot(clients, domains, protocols)
    }

    /** 移除空闲超 [idleMs] 且无活跃连接的条目（"(其他)" 桶保留）。在 ticker 顺带调用。 */
    fun ageOut(idleMs: Long) {
        val now = clock()
        perClient.entries.removeIf { (_, a) -> a.conns.sum() <= 0 && now - a.lastSeen > idleMs }
        perDomain.entries.removeIf { (k, a) -> k != OTHERS && a.conns.sum() <= 0 && now - a.lastSeen > idleMs }
    }

    /** 会话边界清零（start/stop 调用）。 */
    fun reset() {
        perClient.clear(); perDomain.clear(); perProtocol.clear()
        lastClientBytes.clear()
    }

    class Snapshot(
        val clients: List<ClientSession>,
        val topDomains: List<DomainTraffic>,
        val protocols: List<ProtocolTraffic>,
    )

    private class Acc(val key: String) {
        val up = LongAdder()
        val down = LongAdder()
        val conns = LongAdder()
        @Volatile var lastSeen = 0L
    }

    private companion object {
        const val OTHERS = "(其他)"
    }
}
