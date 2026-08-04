package com.mzstd.hxmyproxy.core.proxy

/** HTTP 代理解析助手（纯函数，便于单测）。 */
object HttpParsing {

    /** 转发普通 HTTP 时需剥离的 hop-by-hop / 代理专用头（保留 Content-Length / Transfer-Encoding 以保 body 框架）。 */
    val HOP_BY_HOP = setOf(
        "connection", "proxy-connection", "proxy-authorization",
        "proxy-authenticate", "keep-alive", "te", "trailer", "upgrade",
    )

    /**
     * 剥掉 IPv6 字面量的方括号，得到「规则判定 / DNS / 统计」要的裸地址。
     *
     * `java.net.URI.getHost()` 对 IPv6 字面量返回**带方括号**的 `[2001:db8::1]`，而下游一个都不剥：
     * 规则表按后缀字典树匹配域名、`InetAddresses.isNumericAddress` 也不认带括号的串 ——
     * 于是带括号的 host 被当成域名，**永远匹配不到任何规则、一律落到默认 PROXY**
     * （拦截/直连规则对这类目标全部失效）。CONNECT 走 [parseHostPort]、SOCKS5 走 ATYP 地址，
     * 二者天然无括号，只有明文 HTTP 的 absolute-form 目标会踩到。
     *
     * **反过来 `Host:` 头必须保留括号**（RFC 3986 uri-host），所以剥与不剥两种形式都要留着用，
     * 不能就地覆盖。
     */
    fun bareHost(host: String): String = host.removeSurrounding("[", "]")

    /** 解析 authority-form `host:port` 或 `[v6]:port`（端口必填，无默认）。非法返回 null。 */
    fun parseHostPort(s: String): Pair<String, Int>? {
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            val rest = s.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
            return port?.let { host to it }
        }
        val i = s.lastIndexOf(':')
        if (i < 0) return null
        val port = s.substring(i + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return s.substring(0, i) to port
    }
}
