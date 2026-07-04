package com.mzstd.hxmyproxy.core.rules

import android.net.InetAddresses

/**
 * IP / CIDR 网段匹配集。用 [android.net.InetAddresses]（API 29+，minSdk 已满足）解析**数字 IP**
 * ——不做 DNS 查询——存 (网络地址字节, 前缀位数)，[matches] 按前缀位比较；支持 IPv4 与 IPv6，
 * v4/v6 互不匹配（字节长度不同直接跳过）。
 *
 * 非线程安全：构建（[add]）阶段单线程完成后作只读快照交给 [RuleMatcher] 匹配。
 */
class IpCidrSet {
    private class Entry(val addr: ByteArray, val prefix: Int)

    private val entries = ArrayList<Entry>()
    val size: Int get() = entries.size

    /** 添加 "1.2.3.4" / "1.2.3.4/24" / IPv6 字面量 / "2001:db8::/32"。非法（非数字 IP、前缀越界）忽略。 */
    fun add(cidr: String) {
        val s = cidr.trim()
        if (s.isEmpty()) return
        val slash = s.indexOf('/')
        val ipPart = if (slash >= 0) s.substring(0, slash) else s
        if (!InetAddresses.isNumericAddress(ipPart)) return
        val addr = InetAddresses.parseNumericAddress(ipPart).address ?: return
        val prefix = if (slash >= 0) (s.substring(slash + 1).toIntOrNull() ?: return) else addr.size * 8
        if (prefix < 0 || prefix > addr.size * 8) return
        entries.add(Entry(addr, prefix))
    }

    /** [host] 是 IP 字面量且落在任一 CIDR 内 → true。 */
    fun matches(host: String): Boolean {
        if (entries.isEmpty()) return false
        if (!InetAddresses.isNumericAddress(host)) return false
        val ip = InetAddresses.parseNumericAddress(host).address ?: return false
        for (e in entries) {
            if (e.addr.size == ip.size && prefixMatch(e.addr, ip, e.prefix)) return true
        }
        return false
    }

    private fun prefixMatch(net: ByteArray, ip: ByteArray, prefix: Int): Boolean {
        var bits = prefix
        var i = 0
        while (bits >= 8) {
            if (net[i] != ip[i]) return false
            i++; bits -= 8
        }
        if (bits > 0) {   // 比较不足一字节的高位掩码
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((net[i].toInt() and mask) != (ip[i].toInt() and mask)) return false
        }
        return true
    }

    companion object {
        /** 是否纯 IP 字面量（无 '/'，IPv4 或 IPv6）。 */
        fun isIpLiteral(s: String): Boolean = InetAddresses.isNumericAddress(s.trim())

        /** 是否 IP 或 CIDR（供 [RuleMatcher.add] 分派到 IP 表 vs 域名表）。 */
        fun looksLikeIpOrCidr(s: String): Boolean {
            val t = s.trim()
            val slash = t.indexOf('/')
            val ip = if (slash >= 0) t.substring(0, slash) else t
            return InetAddresses.isNumericAddress(ip)
        }
    }
}
