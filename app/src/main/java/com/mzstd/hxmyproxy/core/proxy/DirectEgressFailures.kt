package com.mzstd.hxmyproxy.core.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 按 host 记录 **DIRECT(绕过 VPN)出站** 的连续失败。
 *
 * 为什么需要它：DIRECT 是 fail-closed 的——建连失败宁可断也不降级到 VPN。这个设计本身是对的
 * （降级会把本该绕开 VPN 的流量泄漏进去），但它有一个对用户不友好的后果：
 * **失败是静默的**。用户在防护页把某个域名设成「直连」，如果该域名在物理网络上恰好不通，
 * 每次访问都要等一次连接超时，而 UI 上没有任何迹象——他只会觉得"这个 app 有时候卡"。
 *
 * 真机日志实证（2026-08-01，5 天）：237 次 DIRECT 超时，Top 是 tpstelemetry.tencent.com 121 次、
 * otheve.beacon.qq.com 19 次——全是用户在防护页点过「直连」的遥测域名。它们在 VPN 在线时
 * 走物理网连不通，于是每次都白等一个超时；而保持拦截反而是立即拒绝、体验更好。
 *
 * 计数语义是**连续**失败：一次成功即清零。所以 [snapshot] 里的条目表示"现在仍然不通"，
 * 而不是"历史上失败过"——后者会让早已恢复的域名一直挂在 UI 上。
 */
object DirectEgressFailures {

    /** 连续失败达到这个次数才认为"这个域名的直连确实不通"，避免偶发抖动就报给用户。 */
    const val MIN_FAILS_TO_REPORT = 3

    /** 最多跟踪多少个 host：防止被大量一次性域名撑爆内存。超限时丢弃最久未更新的。 */
    private const val MAX_HOSTS = 64

    data class Entry(val host: String, val fails: Int, val lastError: String, val lastAtMs: Long)

    private class Counter {
        val fails = AtomicInteger(0)

        @Volatile
        var lastError: String = ""

        @Volatile
        var lastAtMs: Long = 0
    }

    private val map = ConcurrentHashMap<String, Counter>()

    /** DIRECT 建连失败。[error] 用 ProxyError 的简短名（RemoteTimeout / DnsFailure…）。 */
    fun recordFailure(host: String, error: String, nowMs: Long = System.currentTimeMillis()) {
        if (host.isEmpty()) return
        if (map.size >= MAX_HOSTS && !map.containsKey(host)) evictOldest()
        val c = map.computeIfAbsent(host) { Counter() }
        c.fails.incrementAndGet()
        c.lastError = error
        c.lastAtMs = nowMs
    }

    /** DIRECT 建连成功：**清零而非递减**——连续失败被打断就说明当前是通的。 */
    fun recordSuccess(host: String) {
        if (host.isNotEmpty()) map.remove(host)
    }

    /** 用户改回「走代理」或移除覆盖后调用，让该条目立刻从 UI 消失，不必等下一次成功。 */
    fun forget(host: String) {
        map.remove(host)
    }

    /** 会话边界清空（与其它会话级计量一致，见 ProxyServerRepository.resetSessionCounters）。 */
    fun reset() = map.clear()

    /** 当前仍不通的 host，按失败次数降序。少于 [MIN_FAILS_TO_REPORT] 次的不报。 */
    fun snapshot(minFails: Int = MIN_FAILS_TO_REPORT): List<Entry> =
        map.entries
            .mapNotNull { (h, c) ->
                val n = c.fails.get()
                if (n >= minFails) Entry(h, n, c.lastError, c.lastAtMs) else null
            }
            .sortedByDescending { it.fails }

    private fun evictOldest() {
        map.entries.minByOrNull { it.value.lastAtMs }?.let { map.remove(it.key) }
    }
}
