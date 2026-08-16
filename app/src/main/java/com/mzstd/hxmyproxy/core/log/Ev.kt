package com.mzstd.hxmyproxy.core.log

import java.util.concurrent.ConcurrentHashMap

/**
 * 日志分类。占用日志行的 tag 位（原先全仓恒为 "hxmyproxy"，等于没有维度），
 * 让导出日志可以按子系统 grep：`grep " /ADMIT: "`、`grep " /EGRESS: "`。
 */
enum class LogCat {
    /** 进程启动/退出、崩溃 */ PROC,
    /** 前台服务、监听 bind/unbind */ SVC,
    /** 设置变更、上限钳制 */ CFG,
    /** 系统网络回调 */ NET,
    /** 接口扫描、入口计算 */ IFACE,
    /** 准入集变化与准入拒绝 */ ADMIT,
    /** accept/关闭/上限拒绝 */ CONN,
    /** 规则判定与规则表重建 */ RULE,
    /** DNS 解析/互援/DoH */ DNS,
    /** 出口句柄、DIRECT 分流、上游失败 */ EGRESS,
    /** relay、evict、隧道 */ RELAY,
    /** 心跳、耗时、压力 */ PERF,
}

/**
 * 结构化事件门面：统一格式为 `evt=<事件码> k=v k=v ...`，便于 grep/awk 解析，也便于人读。
 *
 * 设计取自本项目多轮排障的教训：
 * - **只记快照不记变化**导致误判方向 ⇒ 状态变化一律用 `x=旧->新` 形式；
 * - **关键证据被高频日志冲掉** ⇒ [k]/[kw] 走 key.log 独立环；
 * - **各处自行实现节流、且都不报被抑制条数** ⇒ 统一 [throttled]，窗口结束补报 `suppressed=N`。
 */
object Ev {

    fun i(cat: LogCat, evt: String, vararg kv: Pair<String, Any?>) =
        FileLog.append("I", cat.name, line(evt, kv), null, key = false)

    fun w(cat: LogCat, evt: String, vararg kv: Pair<String, Any?>) =
        FileLog.append("W", cat.name, line(evt, kv), null, key = false)

    fun e(cat: LogCat, evt: String, t: Throwable? = null, vararg kv: Pair<String, Any?>) =
        FileLog.append("E", cat.name, line(evt, kv), t, key = true)

    /** 关键事件（I 级）：同时进 key.log，永不被高频日志冲出滚动窗口。 */
    fun k(cat: LogCat, evt: String, vararg kv: Pair<String, Any?>) =
        FileLog.append("I", cat.name, line(evt, kv), null, key = true)

    /** 关键事件（W 级）：降级类事件，同样进 key.log。 */
    fun kw(cat: LogCat, evt: String, vararg kv: Pair<String, Any?>) =
        FileLog.append("W", cat.name, line(evt, kv), null, key = true)

    /**
     * 节流写入：同一 [dedup] 键在 [windowMs] 内只落一条，窗口结束后补报期间被抑制的条数
     * （`suppressed=N`——没有这个数，日志会让人误以为「只发生了一次」）。
     * 键表有界：dedup 常含来源 IP/域名，可被扫描器或 DNS 风暴灌爆。
     */
    fun throttled(
        cat: LogCat,
        evt: String,
        dedup: String,
        windowMs: Long,
        key: Boolean = false,
        level: String = "W",
        vararg kv: Pair<String, Any?>,
    ) {
        val now = System.currentTimeMillis()
        val last = throttleAt[dedup]
        if (last != null && now - last < windowMs) {
            suppressed.merge(dedup, 1) { a, b -> a + b }
            return
        }
        if (throttleAt.size > MAX_THROTTLE_KEYS) evictOldest()
        throttleAt[dedup] = now
        val n = suppressed.remove(dedup) ?: 0
        val pairs = if (n > 0) kv.toList() + ("suppressed" to n) else kv.toList()
        FileLog.append(level, cat.name, line(evt, pairs.toTypedArray()), null, key = key)
    }

    /**
     * 键表满了：**淘汰最久未用的一批，而不是全清**。
     *
     * 原实现是 `throttleAt.clear(); suppressed.clear()`，三个后果连在一起：
     *  1. 被压制的条数**永久丢失** —— `suppressed=N` 这个补偿机制的全部意义就是
     *     「日志只有一行，但你要知道实际发生了 N 次」，清掉它等于让那 N 次凭空消失；
     *  2. 清空后所有键重新开始，下一条统统落盘 → **日志突发**，
     *     而突发恰好发生在键最多的时候（故障期，域名基数最大）；
     *  3. 512 对本项目真的紧张：一个活跃域名会占 `usilent:` / `direct:` / `egress-down:` /
     *     `dns-hedge:` / `connect:` / `dns-cache-fallback:` 五六个槽，几十个域名就到顶。
     *
     * 现在：按 LRU 淘汰四分之一，且**被淘汰键的 suppressed 累加进 [droppedSuppressed]**，
     * 由心跳报出来 —— 信息不再凭空消失，只是从「逐键精确」降级成「总量可见」。
     */
    private fun evictOldest() {
        val victims = throttleAt.entries.sortedBy { it.value }.take(MAX_THROTTLE_KEYS / 4).map { it.key }
        victims.forEach { k ->
            throttleAt.remove(k)
            suppressed.remove(k)?.let { droppedSuppressed.addAndGet(it.toLong()) }
        }
        evictions.incrementAndGet()
    }

    /** 键表现状 + 因淘汰而未能报告的抑制条数。进心跳，用来判断节流本身是否已经在骗人。 */
    fun throttleStats(): Triple<Int, Long, Long> =
        Triple(throttleAt.size, evictions.get(), droppedSuppressed.get())

    /** 状态变化的标准写法：`k=旧->新`；相等时返回 null 供调用方跳过落盘（只记变化）。 */
    fun delta(name: String, old: Any?, new: Any?): Pair<String, Any?>? =
        if (old == new) null else name to "$old->$new"

    private fun line(evt: String, kv: Array<out Pair<String, Any?>>): String = buildString {
        append("evt=").append(evt)
        kv.forEach { (k, v) ->
            if (v != null) {
                append(' ').append(k).append('=')
                val s = v.toString()
                // 含空格的值加引号，保证 k=v 切分不被破坏
                if (s.any { it == ' ' }) append('"').append(s).append('"') else append(s)
            }
        }
    }

    private const val MAX_THROTTLE_KEYS = 512
    private val throttleAt = ConcurrentHashMap<String, Long>()
    private val suppressed = ConcurrentHashMap<String, Int>()
    /** 键表淘汰次数，以及因淘汰而**没能被 `suppressed=N` 报出去**的条数累计。 */
    private val evictions = java.util.concurrent.atomic.AtomicLong()
    private val droppedSuppressed = java.util.concurrent.atomic.AtomicLong()
}
