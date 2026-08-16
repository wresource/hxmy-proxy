package com.mzstd.hxmyproxy.core.model

/**
 * 一个可分享入口（host:port + 协议），供 UI 展示、复制、二维码与 PAC 生成。
 *
 * 入口只给具体 IP。曾经还有一个 `hxmyproxy.local` 便利名字段，随 mDNS 一并删除——
 * NsdManager 无法注册自定义主机名，那个名字从未被通告过 A 记录、客户端解析不到。
 *
 * @param host             具体接口 IP 字面量（如 "192.168.1.34"）。
 * @param sourceInterfaceId 来源 [ShareInterface.id]。
 */
data class ProxyEntry(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val sourceInterfaceId: String,
    val priority: Int = 0,
    val reachable: Boolean = true,
) {
    /**
     * 形如 "192.168.1.34:1080" 的 IP 端点（始终可用）。
     *
     * **IPv6 必须加方括号**：`fd00::1:1080` 无法解析——冒号既是地址的一部分又是端口分隔符，
     * 这是 RFC 3986 §3.2.2 规定的写法。PAC 正文、二维码、设置页、复制按钮全部走这里，
     * 所以在这一处修好，下游都不用各自处理。
     * 判据用「含冒号」而不是类型判断：这里的 host 已经是字符串，且只有 v6 字面量会含冒号。
     */
    val ipEndpoint: String get() = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    /** 是否 IPv6 入口（判据同 [ipEndpoint]：host 已是字符串，只有 v6 字面量含冒号）。 */
    val isIpv6: Boolean get() = host.contains(':')

    /**
     * 入口卡**行内展示**文本。PAC 必须给**完整 URL**（`http://ip:port/proxy.pac`）——系统/浏览器的
     * 「自动代理配置（PAC）」字段要的就是这个；只给裸 `ip:port` 用户粘进去必然失效（这正是 PAC「拉得到却用不起来」的根因）。
     * HTTP/SOCKS 则给 `host:port`（直接填代理服务器/端口）。
     */
    val displayEndpoint: String get() =
        if (protocol == ProxyProtocol.PAC) "http://$ipEndpoint/proxy.pac" else ipEndpoint

    /** 「复制」写入剪贴板的文本：PAC 给完整 PAC URL；HTTP/SOCKS 给 IP 端点。 */
    val copyValue: String get() =
        if (protocol == ProxyProtocol.PAC) "http://$ipEndpoint/proxy.pac" else ipEndpoint
}
