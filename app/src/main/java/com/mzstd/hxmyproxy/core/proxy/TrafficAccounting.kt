package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.BlockedDomain
import com.mzstd.hxmyproxy.core.model.ClientSession
import com.mzstd.hxmyproxy.core.model.DomainTraffic
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * 流量记账：按客户端 IP 与「目标域名 × 协议」聚合上下行字节，喂给监控页的「会话列表 / 域名 Top-N」。
 *
 * 域名按 **(host, protocol)** 聚合 —— 同一域名若经不同协议访问则分别成行，监控里据此看出
 * 「哪个域名走的是 HTTP / SOCKS5 / PAC」。
 *
 * 设计要点：
 * - **热路径零锁**：[ConnTracker.add] 只对 [LongAdder] 累加（无锁、cell 分片）；`ConcurrentHashMap`
 *   的 computeIfAbsent 仅在开连接 / 首次绑 host 各走一次，不在搬字节循环里。
 * - **隐私**：只记 host + 协议 + 字节，绝不碰 path/query/URL/请求内容；纯内存、不落盘，随 [reset] 清空。
 * - **内存有界**：域名 Top-N 封顶（[maxDomains]）+ "(其他)" 兜底桶 + 空闲老化（[ageOut]），三重堵死无界增长。
 * - **不破坏全局速率仪表**：每次 add 同时把增量喂 [globalSink]（→ totalUp/totalDown）。
 * - 读（[snapshot]）仅在单线程 ticker，[LongAdder.sum] 弱一致足够（统计语义可容忍 ±1s）。
 */
class TrafficAccounting(
    @Volatile var maxDomains: Int = 256,
    private val clock: () -> Long = System::currentTimeMillis,
    private val globalSink: (Long, Long) -> Unit = { _, _ -> },
    /**
     * 历史流量统计入口（跨会话、按出口分类累加）。与 [globalSink] 的区别：那个是「本次共享」的
     * 会话计量，[reset] 会清零；这个是落盘的历史累计，**不随会话边界清零**。
     */
    private val historySink: (com.mzstd.hxmyproxy.core.stats.EgressKind, Long, Long) -> Unit = { _, _, _ -> },
) {
    private val perClient = ConcurrentHashMap<InetAddress, Acc>()
    // 按「域名 × 协议」聚合：键含协议，故同域名不同协议分桶 → 监控能体现"哪个域名走哪个协议"。
    private val perDomain = ConcurrentHashMap<DomainKey, Acc>()
    // 拦截计数：命中 REJECT（广告/用户拒绝）的总次数与被拦域名 Top-N；会话内累计，reset 清零。
    private val blocked = LongAdder()
    private val perBlocked = ConcurrentHashMap<String, LongAdder>()
    // 上次快照各 IP 累计，用于在单线程 ticker 内做 1s 速率差分（仅 ticker 访问，无需同步）。
    private val lastClientBytes = HashMap<InetAddress, LongArray>()

    /** 每条连接开始时建一次，绑定 [clientIp] 与 [protocol]；host 解析出来后再 [ConnTracker.bindHost]。 */
    fun openConnection(clientIp: InetAddress, protocol: ProxyProtocol): ConnTracker = ConnTracker(clientIp, protocol)

    inner class ConnTracker(clientIp: InetAddress, private val protocol: ProxyProtocol) {
        // lastSeen 必须在开连接时就打上：Acc 初始 lastSeen=0，而它原本只在 add()（有字节）时更新——
        // 零计账字节的连接（如 204 无 body 的明文请求）关闭后 conns=0 且 lastSeen=0，
        // 下一秒 ticker 的 ageOut 判 now-0>5min 恒真 → 条目秒删 → 监控页漏显瞬时客户端、
        // 手动刷新的 lastSeenClients 也抓不住（模拟器验证时踩到）。
        private val clientAcc = perClient.computeIfAbsent(clientIp) { Acc(clientIp.hostAddress ?: "?") }
            .also { it.conns.increment(); it.lastSeen = clock() }
        @Volatile private var domainAcc: Acc? = null

        /**
         * 本连接的上游出口，由 [OutboundConnector] 建连成功后回填（见 `onEgress`）。
         *
         * 默认 **OTHER 而非 null**：将来若有哪条新的建连路径忘了回填，字节会显式堆进「其他」这一档
         * ——统计页上一眼就能看出「有路径没接上」。若默认成「不计」，漏的字节会静默消失，
         * 而「统计里没有」和「根本没发生」在结果上长得一模一样（见记忆 absence-is-not-evidence）。
         */
        @Volatile private var egress = com.mzstd.hxmyproxy.core.stats.EgressKind.OTHER

        /** 建连成功后回填实际出口；同连接可能因降级重连而改变，覆盖即可。 */
        fun bindEgress(kind: com.mzstd.hxmyproxy.core.stats.EgressKind) {
            egress = kind
        }

        /** 目标解析出来后调用；按 (当前 host, 本连接协议) 归属。[direct]=规则决策为直连出口（绕过 VPN）。
         *  同连接协议固定、只 host 随 keep-alive 变。幂等。 */
        fun bindHost(host: String, direct: Boolean = false) {
            val hostKey = host.lowercase()
            val cur = domainAcc
            if (cur != null && cur.key == hostKey) return   // 同连接协议不变，host 相同即同桶
            // keep-alive 多 host 切换：先释放旧域名的连接计数，否则旧域名 conns 虚高 → 永不满足 ageOut → 内存泄漏。
            cur?.conns?.decrement()
            val key = DomainKey(hostKey, protocol)
            // 容量封顶：满了且是新 (host,协议) → 计入该协议的 "(其他)" 兜底桶，字节不丢、内存不爆。
            val acc = perDomain[key]
                ?: if (perDomain.size >= maxDomains) perDomain.computeIfAbsent(DomainKey(OTHERS, protocol)) { Acc(OTHERS, protocol) }
                else perDomain.computeIfAbsent(key) { Acc(hostKey, protocol) }
            acc.conns.increment()
            acc.lastSeen = clock()
            acc.direct = direct   // 同域名决策由规则决定,稳定;监控据此显示「直连」徽标
            domainAcc = acc
        }

        /** 热路径：每搬一块字节后调用，纯原子加；同时喂全局速率仪表。 */
        fun add(up: Long, down: Long) {
            if (up > 0) { clientAcc.up.add(up); domainAcc?.up?.add(up) }
            if (down > 0) { clientAcc.down.add(down); domainAcc?.down?.add(down) }
            val now = clock()
            clientAcc.lastSeen = now
            domainAcc?.let { it.lastSeen = now }
            globalSink(up, down)
            historySink(egress, up, down)
        }

        /** 连接结束：活跃连接数 -1（累计字节保留到老化淘汰）。 */
        fun close() {
            clientAcc.conns.decrement()
            domainAcc?.conns?.decrement()
        }

        /** 命中 REJECT（广告/拒绝规则）时调用一次：计入拦截总数与被拦域名 Top-N（域名表封顶 [maxDomains]）。 */
        fun recordBlocked(host: String) {
            blocked.increment()
            val key = host.lowercase()
            (perBlocked[key] ?: if (perBlocked.size >= maxDomains) null
                else perBlocked.computeIfAbsent(key) { LongAdder() })?.increment()
        }
    }

    /** 单线程 ticker 调用：产出客户端会话与域名 Top-N 快照（客户端含 1s 窗口速率差分）。 */
    fun snapshot(): Snapshot {
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
            DomainTraffic(a.key, a.protocol ?: ProxyProtocol.HTTP, a.up.sum(), a.down.sum(), a.conns.sum().coerceAtLeast(0), a.lastSeen, a.direct)
        }.sortedByDescending { it.uploadBytes + it.downloadBytes }
        // **不按 topN 截断**（与下面的 topBlocked 同口径）：完整返回（≤ maxTrackedDomains），
        // 由 UI 层各自 take。此前后端按【流量】截断、前端却按【最近访问】重排，两次口径不一致——
        // 结果是零散但活跃的小流量域名（浏览器的多数请求）在后端就被丢掉，用户永远看不到，
        // 而列表看着又像「最近访问」。明细页要「显示全部域名」也必须靠这个完整集合。
        // 被拦域名不按 topN 截断：完整返回（≤ maxDomains），供拦截明细页排查误封；首页/监控 UI 层各自 take。
        val topBlocked = perBlocked.map { (h, c) -> BlockedDomain(h, c.sum()) }
            .sortedByDescending { it.count }
        return Snapshot(clients, domains, blocked.sum(), topBlocked)
    }

    /** 移除空闲超 [idleMs] 且无活跃连接的条目（"(其他)" 桶保留）。在 ticker 顺带调用。 */
    fun ageOut(idleMs: Long) {
        val now = clock()
        perClient.entries.removeIf { (_, a) -> a.conns.sum() <= 0 && now - a.lastSeen > idleMs }
        perDomain.entries.removeIf { (k, a) -> k.host != OTHERS && a.conns.sum() <= 0 && now - a.lastSeen > idleMs }
    }

    /** 会话边界清零（start/stop 调用）。 */
    fun reset() {
        perClient.clear(); perDomain.clear()
        blocked.reset(); perBlocked.clear()
        lastClientBytes.clear()
    }

    class Snapshot(
        val clients: List<ClientSession>,
        val topDomains: List<DomainTraffic>,
        val blockedTotal: Long = 0,
        val topBlocked: List<BlockedDomain> = emptyList(),
    )

    /** 域名聚合键：host + 协议，同域名不同协议分别成桶。 */
    private data class DomainKey(val host: String, val protocol: ProxyProtocol)

    private class Acc(val key: String, val protocol: ProxyProtocol? = null) {
        val up = LongAdder()
        val down = LongAdder()
        val conns = LongAdder()
        @Volatile var lastSeen = 0L
        @Volatile var direct = false
    }

    companion object {
        /** 域名统计满员后的聚合兜底桶名（运行时常量键）；UI 显示时按语言本地化，勿直接展示。 */
        const val OTHERS = "(其他)"
    }
}
