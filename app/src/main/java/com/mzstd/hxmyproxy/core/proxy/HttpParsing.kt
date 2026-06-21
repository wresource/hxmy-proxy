package com.mzstd.hxmyproxy.core.proxy

/** HTTP 代理解析助手（纯函数，便于单测）。 */
object HttpParsing {

    /** 转发普通 HTTP 时需剥离的 hop-by-hop / 代理专用头（保留 Content-Length / Transfer-Encoding 以保 body 框架）。 */
    val HOP_BY_HOP = setOf(
        "connection", "proxy-connection", "proxy-authorization",
        "proxy-authenticate", "keep-alive", "te", "trailer", "upgrade",
    )

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
