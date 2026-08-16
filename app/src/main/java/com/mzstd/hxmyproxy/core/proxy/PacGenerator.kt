package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol

/**
 * 由可用入口生成 PAC（`FindProxyForURL`）。
 *
 * **iOS 友好排序**（grounded：iOS CFNetwork 的 PAC 解析器只认 `PROXY`/`DIRECT`/`SOCKS`，
 * 忽略 `HTTPS`/`HTTP`、不保证字面 `SOCKS5`）：
 * - `PROXY`(HTTP，iOS+桌面都最稳) 优先于 SOCKS；
 * - SOCKS 同时给 `SOCKS5`(桌面)与裸 `SOCKS`(iOS 把裸 SOCKS 当 SOCKS5)；
 * - 末尾恒 `DIRECT`。
 *
 * 曾经还在末尾附一条 `.local` 便利名兜底，随 mDNS 一并删除——
 * 那个名字从未被通告过 A 记录，客户端根本解析不到。纯函数，便于单测。
 */
object PacGenerator {
    fun generate(entries: List<ProxyEntry>): String {
        val socks = entries.filter { it.protocol == ProxyProtocol.SOCKS5 }
        val http = entries.filter { it.protocol == ProxyProtocol.HTTP }
        val tokens = ArrayList<String>()
        // ① HTTP 代理(具体 IP)——iOS 与桌面都稳认 PROXY，放最前。
        http.forEach { tokens.add("PROXY ${it.ipEndpoint}") }
        // ② SOCKS(具体 IP)——桌面用 SOCKS5；iOS 的 CFNetwork 把裸 SOCKS 当 SOCKS5，两条都给。
        socks.forEach { tokens.add("SOCKS5 ${it.ipEndpoint}") }
        socks.forEach { tokens.add("SOCKS ${it.ipEndpoint}") }
        // ③ 兜底直连。
        tokens.add("DIRECT")
        return "function FindProxyForURL(url, host) {\n  return \"${tokens.joinToString("; ")}\";\n}\n"
    }
}
