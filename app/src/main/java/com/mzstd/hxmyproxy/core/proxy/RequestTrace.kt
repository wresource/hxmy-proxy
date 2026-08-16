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
 * **刻意不做的事**：不改变任何行为、不引入锁、不持有请求对象。
 * 每条记录都是即时写出的独立行，靠 id 关联而不是靠内存里的聚合——
 * 后者会在进程被杀时全部丢失，而那恰恰是最需要日志的时刻。
 */
class RequestTrace private constructor(val id: Int, val proto: String, val clientPort: Int) {

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
    fun rule(host: String, action: Any?, src: Any?) =
        Ev.i(
            LogCat.RULE, "req.rule",
            "id" to id, "sport" to clientPort, "host" to host, "act" to action, "src" to src,
        )

    /**
     * DNS 结果的**来源溯源**。这是本次补观测的核心。
     *
     * @param src 见 [DnsSource]
     * @param netId 在哪张网上解析的；跨网缓存时这里是**答案原本属于**的那张网
     * @param detail 缓存年龄 `age=8s` / 真实耗时 `rtt=340ms` / DoH 端点 `ep=223.5.5.5`
     * @param firstIp 首个地址——用来直接对上「这次和上次是不是同一个节点」
     */
    fun dns(host: String, src: DnsSource, netId: Long?, count: Int, detail: String?, firstIp: String?) =
        Ev.i(
            LogCat.DNS, "req.dns",
            "id" to id, "host" to host, "src" to src.code, "netId" to netId,
            "n" to count, "d" to detail, "ip" to firstIp, "ms" to elapsedMs(), "rt" to realtimeMs(),
        )

    /** 建连成功：**记下真正连上的那个地址**，此前完全不可见。 */
    fun connected(host: String, ip: String?, port: Int, netId: Long?) =
        Ev.i(
            LogCat.EGRESS, "req.connected",
            "id" to id, "host" to host, "ip" to ip, "port" to port, "netId" to netId,
            "ms" to elapsedMs(), "rt" to realtimeMs(),
        )

    /** 终局（失败或关闭）。[why] 是阶段名，便于回答「卡在哪一步」。 */
    fun failed(host: String, why: String, err: Any?) =
        Ev.w(
            LogCat.EGRESS, "req.failed",
            "id" to id, "host" to host, "at" to why, "err" to err,
            "ms" to elapsedMs(), "rt" to realtimeMs(),
        )

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
    fun tunnelClosed(host: String?, reason: String, up: Long, down: Long) =
        Ev.i(
            LogCat.RELAY, "req.closed",
            "id" to id, "host" to host, "why" to reason, "up" to up, "down" to down,
            "ms" to elapsedMs(), "rt" to realtimeMs(),
        )

    companion object {
        private val seq = AtomicInteger(0)

        /**
         * 每条客户端连接开一个。id 只需进程内唯一，用自增而非 UUID：日志里短、好 grep。
         *
         * @param clientPort 客户端源端口。**用于和外层中间层代理的日志对齐**——
         * 若上游是本机 shim（proxyshim 之类），它日志里的 `(127.0.0.1:57725)` 就是这个值，
         * 有了它才能把一次失败在两层的经过拼成一条完整链路。
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
    /** DoH 兜底（detail 里带具体端点） */
    DOH("doh"),
    /** 目标本身就是 IP 字面量，未经任何解析器 */
    LITERAL("literal"),
}
