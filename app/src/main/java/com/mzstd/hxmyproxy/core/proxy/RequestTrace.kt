package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.LogCat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次请求的**全程可追溯**。
 *
 * ## 为什么需要
 *
 * 此前的日志只在**失败那一层**留痕，成功路径几乎零记录：
 * 规则判 PROXY 直接 return 不记、建连成功不记连到了哪个 IP、relay 建立与正常拆除都不记。
 * 于是排障时永远只有碎片，得靠 `ss`、`/proc`、差集法从系统层反推——
 * 多轮排查里已经因此误判过好几次方向（把 anycast IP 当归属证据、拿「日志里没有」当「没发生」）。
 *
 * 更要命的是**同一个域名的地址有 11 条可能来源**（见 [DnsSource]），
 * 而它们会给出**不同的目标 IP**。所以「这次连的 IP 和上次不一样」这种问题，
 * 在没有溯源字段之前根本没法回答。
 *
 * ## 设计
 *
 * 一个自增 id 串起 accept→关闭的全部阶段，每阶段一行 `evt=req.<phase>`。
 * 全部走既有的 [Ev] 门面与 [LogCat]，不另起日志体系。
 *
 * 不改变转发行为、不持有 socket。新增的有界内存视图仅供界面查询，日志仍逐条落入原通道。
 * 每条记录都是即时写出的独立行，靠 id 关联而不是靠内存里的聚合——
 * 后者会在进程被杀时全部丢失，而那恰恰是最需要日志的时刻。
 */
class RequestTrace private constructor(val id: Int, val proto: String, val clientPort: Int) {

    private val activity = RequestActivity.store.open(id, proto)

    private val startNs = System.nanoTime()
    private val startRealtimeMs = android.os.SystemClock.elapsedRealtime()

    private fun elapsedMs(): Long = (System.nanoTime() - startNs) / 1_000_000L

    /**
     * 同一段时间用**挂钟不停走的时钟**再量一遍。
     *
     * 为什么要两个:所有超时预算（建连 10s、DNS 单步 1.5s、判死 90s、空闲 120s）都用
     * [System.nanoTime]，而设备 suspend 时它会停走 —— 于是「超时 10 秒」在用户那里
     * 可能是十几分钟。0814 实测 66/432 次失败的日志墙钟比 `ms=` 多出 >2 秒、最大 30.1 秒，
     * 而两个解释（设备睡了 / 写日志排队）对应的修法完全不同。
     *
     * `rt` 明显大于 `ms` ⇒ 中间睡过;两者接近 ⇒ 偏差来自日志侧。**这一个字段就能分开它们。**
     */
    private fun realtimeMs(): Long = android.os.SystemClock.elapsedRealtime() - startRealtimeMs

    /** 规则判定结果。**PROXY 也记** —— 此前只记非 PROXY，导致「绝大多数流量走了哪条路」不可见。 */
    fun rule(host: String, action: Any?, src: Any?) {
        RequestActivity.store.rule(activity, host, action?.toString(), src?.toString())
        Ev.i(
            LogCat.RULE, "req.rule",
            "id" to id, "sport" to clientPort, "host" to host, "act" to action, "src" to src,
        )
    }

    /**
     * DNS 结果的**来源溯源**。这是本次补观测的核心。
     *
     * @param src 见 [DnsSource]
     * @param netId 在哪张网上解析的；跨网缓存时这里是**答案原本属于**的那张网
     * @param detail 缓存年龄 `age=8s` / 真实耗时 `rtt=340ms` / DoH 端点 `ep=223.5.5.5`
     * @param firstIp 首个地址——用来直接对上「这次和上次是不是同一个节点」
     */
    fun dns(host: String, src: DnsSource, netId: Long?, count: Int, detail: String?, firstIp: String?) {
        RequestActivity.store.dns(activity, src.code)
        Ev.i(
            LogCat.DNS, "req.dns",
            "id" to id, "host" to host, "src" to src.code, "netId" to netId,
            "n" to count, "d" to detail, "ip" to firstIp, "ms" to elapsedMs(), "rt" to realtimeMs(),
        )
    }

    internal fun dnsRecovery(host: String, entry: Long?, source: DnsSource, outcome: String, elapsed: Long) {
        Ev.i(LogCat.DNS, "req.dnsRecovery", "id" to id, "host" to host, "entry" to entry,
            "source" to source.code, "outcome" to outcome, "recheckMs" to elapsed,
            "ms" to elapsedMs(), "rt" to realtimeMs())
    }

    internal fun dialCandidates(host: String, attempt: Int, entry: Long?, network: Long?, addresses: List<String>) {
        Ev.i(LogCat.EGRESS, "req.dial", "id" to id, "host" to host, "attempt" to attempt,
            "entry" to entry, "netId" to network, "resolved" to addresses.joinToString(","), "ms" to elapsedMs())
    }

    /** 建连成功：**记下真正连上的那个地址**，此前完全不可见。 */
    fun connected(host: String, ip: String?, port: Int, netId: Long?) {
        RequestActivity.store.connected(activity, ip)
        Ev.i(
            LogCat.EGRESS, "req.connected",
            "id" to id, "host" to host, "ip" to ip, "port" to port, "netId" to netId,
            "ms" to elapsedMs(), "rt" to realtimeMs(),
        )
    }

    /** 终局（失败或关闭）。[why] 是阶段名，便于回答「卡在哪一步」。 */
    fun failed(host: String, why: String, err: Any?) {
        RequestActivity.store.failed(activity, why, err?.toString(), (err as? ProxyError)?.code)
        Ev.w(
            LogCat.EGRESS, "req.failed",
            "id" to id, "host" to host, "at" to why, "err" to err,
            "ms" to elapsedMs(), "rt" to realtimeMs(),
        )
    }

    /**
     * 隧道结束：带上结束原因、存活时长与收发字节。
     *
     * **[host] 冗余记一遍是刻意的**——靠 id 回查 `req.rule` 才能知道是谁，
     * 意味着「这一晚是哪些域名在被回收」得先 join 两类行；而排障时最想一条命令答完的
     * 恰恰是这个问题（`grep 'why=idle' | grep -o 'host=[^ ]*' | sort | uniq -c`）。
     *
     * 曾有一个 `flag=` 字段标记「上游静默判死」的终局（silent/revived）。判据已删除，
     * 字段随之移除——它的使命就是证明那条判据在误杀，证完即止。
     */
    fun tunnelClosed(host: String?, reason: String, up: Long, down: Long) {
        RequestActivity.store.closed(activity, reason, up, down)
        Ev.i(
            LogCat.RELAY, "req.closed",
            "id" to id, "host" to host, "why" to reason, "up" to up, "down" to down,
            "ms" to elapsedMs(), "rt" to realtimeMs(),
        )
    }

    /** Numeric socket metadata only: no reverse DNS, headers or payload on the relay thread. */
    internal fun relayStarted(client: java.net.Socket, upstream: java.net.Socket) {
        fun endpoint(value: java.net.SocketAddress?): String? = (value as? java.net.InetSocketAddress)?.let {
            "${it.address?.hostAddress}:${it.port}"
        }
        Ev.i(LogCat.RELAY, "req.relay", "id" to id,
            "clientPeer" to endpoint(client.remoteSocketAddress), "clientLocal" to endpoint(client.localSocketAddress),
            "upstreamLocal" to endpoint(upstream.localSocketAddress), "upstreamPeer" to endpoint(upstream.remoteSocketAddress))
    }

    internal fun tunnelClosed(host: String?, evidence: RelayCloseEvidence, up: Long, down: Long) {
        val cause = evidence.cause()
        val stage = listOfNotNull(cause.reason, cause.operation, cause.side).joinToString(":")
        if (cause.failed) RequestActivity.store.failed(activity, stage, cause.errorType, "other")
        RequestActivity.store.closed(activity, cause.reason, up, down)
        val fields = arrayOf("id" to id, "host" to host, "why" to cause.reason,
            "op" to cause.operation, "side" to cause.side, "err" to cause.errorType, "errno" to cause.errno,
            "firstEof" to evidence.firstEof, "cleanup" to evidence.cleanup,
            "up" to up, "down" to down, "ms" to elapsedMs(), "rt" to realtimeMs())
        if (cause.failed) Ev.w(LogCat.RELAY, "req.closed", *fields)
        else Ev.i(LogCat.RELAY, "req.closed", *fields)
    }

    companion object {
        private val seq = AtomicInteger(0)

        /**
         * 每条客户端连接开一个。id 只需进程内唯一，用自增而非 UUID：日志里短、好 grep。
         *
         * @param clientPort 客户端源端口。**用于和外层中间层代理的日志对齐**——
         * 若客户端是 shim，应与它真正连接手机的 socket 本地端口关联。
         * shim 前端监听收到的 localhost 源端口属于另一条 socket，不能直接当作这个值。
         */
        fun open(proto: String, clientPort: Int): RequestTrace =
            RequestTrace(seq.incrementAndGet(), proto, clientPort)
    }
}

/**
 * 地址的来源。**一个域名当前有 11 条可能路径**，而它们会给出不同的目标 IP——
 * 对 anycast 服务（CDN 后面的站点）尤其明显。
 *
 * 曾经还有一条 `cache-cross`(跨网旧缓存):把 A 网解析出的地址拿到 B 网上去连。
 * 加它的理由是「用 TTL 内的旧地址好过直接失败」，但那假设了地址与网络无关，
 * 对 anycast 不成立。**加枚举的目的就是先量出发生率再决定去留** ——
 * 两轮实测(0814 / 0815 两台)都是 0 次命中，已于 1.31.2 移除。这条枚举也随之删掉。
 */
enum class DnsSource(val code: String) {
    /** 同一张网的进程内缓存命中（≤30s） */
    CACHE_SAME_NET("cache"),
    /** 并发单飞：等到了另一个请求的解析结果 */
    INFLIGHT("inflight"),
    /** 出口网上的系统解析（netd → 该网下发的 DNS） */
    SYS_EGRESS("sys-egress"),
    /** 进程默认路由上的系统解析 */
    SYS_DEFAULT("sys-default"),
    /** 对冲抢答中「互援那一路」先返回 */
    HEDGE_RESCUE("hedge"),
    /** 互援：进程默认网 */
    RESCUE_DEFAULT("rescue-def"),
    /** 互援：物理网 */
    RESCUE_PHYSICAL("rescue-phy"),
    /** 多个来源的备用候选；各来源数量和网络写在 detail，不伪装成同一 DNS 网络。 */
    MIXED_RESCUE("rescue-mixed"),
    /** DoH 兜底（detail 里带具体端点） */
    DOH("doh"),
    /** 目标本身就是 IP 字面量，未经任何解析器 */
    LITERAL("literal"),
}
