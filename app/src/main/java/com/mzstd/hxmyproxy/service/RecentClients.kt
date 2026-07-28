package com.mzstd.hxmyproxy.service

import android.content.Context
import org.json.JSONObject

/**
 * 最近客户端 IP 的**跨会话**记录（独立 SharedPreferences，同 [ServiceState] 的理由：绝不触碰 settings flow）。
 *
 * 为什么必须跨会话：7-27 故障里,「最需要被探测/被治愈的客户端」恰恰是**这个会话还没连上来的那台**
 * ——会话内的 lastSeenClients 对它永远是空的,路径保活与失联检测都无从谈起。
 *
 * 容量 [MAX]、过期 [TTL_MS] 双重有界；写入仅在集合变化时发生（ticker 秒级调用无 IO 压力）。
 */
object RecentClients {
    private const val PREF = "recent_clients"
    private const val KEY = "clients"
    private const val MAX = 8
    private const val TTL_MS = 7L * 24 * 3600 * 1000

    /** 读出未过期的 ip -> lastSeenMs（按 lastSeen 降序）。 */
    fun load(ctx: Context): Map<String, Long> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        val cutoff = System.currentTimeMillis() - TTL_MS
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence()
                .map { it to o.getLong(it) }
                .filter { it.second > cutoff }
                .sortedByDescending { it.second }
                .toList().toMap()
        }.getOrDefault(emptyMap())
    }

    /** 合并在线客户端并落盘（截断到 [MAX]，剔除过期）。 */
    fun record(ctx: Context, onlineIps: Collection<String>) {
        if (onlineIps.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = (load(ctx) + onlineIps.associateWith { now })
            .toList().sortedByDescending { it.second }.take(MAX).toMap()
        val o = JSONObject()
        merged.forEach { (ip, t) -> o.put(ip, t) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
