package com.mzstd.hxmyproxy.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mzstd.hxmyproxy.core.model.HistoryEndpoint
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.historyDataStore by preferencesDataStore(name = "endpoint_history")

/**
 * 历史代理入口持久化（用过的 IP:端口:协议），便于复用而不必重复操作。
 * 每条编码为 `protocol|ip|port|millis`，按最近使用排序，最多保留 [MAX] 条。
 */
@Singleton
class EndpointHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringSetPreferencesKey("endpoints")

    val history: Flow<List<HistoryEndpoint>> = context.historyDataStore.data.map { prefs ->
        (prefs[key] ?: emptySet()).mapNotNull { decode(it) }.sortedByDescending { it.lastUsedMillis }
    }

    /** 记录（upsert）一批入口；同一 protocol+ip+port 视为同一条，仅更新时间戳。 */
    suspend fun record(entries: List<HistoryEndpoint>) {
        if (entries.isEmpty()) return
        context.historyDataStore.edit { prefs ->
            val byKey = LinkedHashMap<String, HistoryEndpoint>()
            (prefs[key] ?: emptySet()).mapNotNull { decode(it) }.forEach { byKey[idOf(it)] = it }
            entries.forEach { byKey[idOf(it)] = it }
            val merged = byKey.values.sortedByDescending { it.lastUsedMillis }.take(MAX)
            prefs[key] = merged.map { encode(it) }.toSet()
        }
    }

    suspend fun remove(entry: HistoryEndpoint) {
        context.historyDataStore.edit { prefs ->
            prefs[key] = (prefs[key] ?: emptySet())
                .filterNot { decode(it)?.let(::idOf) == idOf(entry) }
                .toSet()
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { it.remove(key) }
    }

    private fun idOf(e: HistoryEndpoint) = "${e.protocol}|${e.ip}|${e.port}"
    private fun encode(e: HistoryEndpoint) = "${e.protocol.name}|${e.ip}|${e.port}|${e.lastUsedMillis}"
    private fun decode(s: String): HistoryEndpoint? {
        val p = s.split('|')
        if (p.size < 4) return null
        val proto = ProxyProtocol.entries.firstOrNull { it.name == p[0] } ?: return null
        val port = p[2].toIntOrNull() ?: return null
        return HistoryEndpoint(proto, p[1], port, p[3].toLongOrNull() ?: 0L)
    }

    private companion object { const val MAX = 20 }
}
