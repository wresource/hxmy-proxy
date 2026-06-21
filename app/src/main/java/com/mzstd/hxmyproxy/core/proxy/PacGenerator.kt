package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol

/**
 * 由可用入口生成 PAC（`FindProxyForURL`）。D1：`hxmyproxy.local` 在前，随后各接口 IP 兜底，最后 `DIRECT`。
 * 纯函数，便于单测。
 */
object PacGenerator {
    fun generate(entries: List<ProxyEntry>): String {
        val socks = entries.filter { it.protocol == ProxyProtocol.SOCKS5 }
        val http = entries.filter { it.protocol == ProxyProtocol.HTTP }
        val tokens = ArrayList<String>()
        socks.firstOrNull { it.mdnsEndpoint != null }?.let { tokens.add("SOCKS5 ${it.mdnsEndpoint}") }
        socks.forEach { tokens.add("SOCKS5 ${it.ipEndpoint}") }
        http.firstOrNull { it.mdnsEndpoint != null }?.let { tokens.add("PROXY ${it.mdnsEndpoint}") }
        http.forEach { tokens.add("PROXY ${it.ipEndpoint}") }
        tokens.add("DIRECT")
        return "function FindProxyForURL(url, host) {\n  return \"${tokens.joinToString("; ")}\";\n}\n"
    }
}
