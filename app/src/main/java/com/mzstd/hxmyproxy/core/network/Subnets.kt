package com.mzstd.hxmyproxy.core.network

import java.net.InetAddress

/**
 * 子网几何：地址↔整数、子网定向广播地址、地址是否落在某组子网内。**双栈**。
 *
 * 从 ProxyServerRepository 抽出：这几个函数是**纯计算**——不读任何 @Volatile 字段、
 * 不碰会话时序、无 IO，因此可以独立单测（原先它们是 private，那些位运算的边界一行都没测过）。
 * 抽出时把 `inEntrySubnet` 隐式读取的 `entrySubnets` 字段改成了显式入参，这是它变纯的关键。
 *
 * **IPv6 支持的关键在 [inSubnets] 换了表示法**：原先网段是 `Pair<Int, Int>`（32 位网络号 +
 * 前缀），而 IPv6 是 128 位，**Int 根本装不下**——这才是入站 v6 一直进不来的真正卡点，
 * 不是"监听没开"。现在统一用 `ByteArray`（v4 是 4 字节、v6 是 16 字节）按位前缀比较，
 * 两族靠字节长度天然隔离，不会互相误匹配。
 *
 * **以下边界是刻意保留的当前行为，不要"顺手修掉"**：
 * - [subnetBroadcast] 仍然只对 v4 有效：IPv6 没有广播，只有多播，返回 null 是正确语义。
 *   用 `prefix !in 1..31` 排除 /32 与 /0：/32 没有广播地址，/0 会算出 255.255.255.255
 *   （受限广播），两者都不是"子网定向广播"的有效目标。
 * - [inSubnets] 里 `prefix <= 0` 时**同族任何地址都算在网内**。改掉会改变
 *   entrySubnets 含异常前缀时的失联判定与探测范围。
 */
object Subnets {

    /** IPv4 地址转 Int（大端）；非 v4 返回 null。 */
    fun ipv4Int(addr: InetAddress): Int? {
        val b = addr.address
        if (b.size != 4) return null
        return ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
    }

    /** IPv4 子网定向广播地址（ip | ~mask）；非 v4 或异常前缀（含 /0 与 /32）返回 null。 */
    fun subnetBroadcast(addr: InetAddress, prefix: Int): InetAddress? {
        val b = addr.address
        if (b.size != 4 || prefix !in 1..31) return null
        val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
        val bc = ip or ((1 shl (32 - prefix)) - 1)
        return runCatching {
            InetAddress.getByAddress(
                byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()),
            )
        }.getOrNull()
    }

    /**
     * [addr] 是否落在 [subnets] 中任一子网内。[subnets] 为 (网络号字节, 前缀长度) 对。
     *
     * 字节长度即地址族：v4 是 4、v6 是 16。**长度不同直接不匹配**，
     * 所以一台双栈客户端的 v4 地址不会因为某条 v6 网段而被误放行，反之亦然。
     */
    fun inSubnets(addr: InetAddress, subnets: List<Pair<ByteArray, Int>>): Boolean {
        val ip = addr.address ?: return false
        return subnets.any { (net, prefix) -> matchesPrefix(ip, net, prefix) }
    }

    /**
     * 按位前缀比较，同时适用于 v4 与 v6。
     *
     * 先整字节比，再比余下的高位。`prefix <= 0` 时同族全匹配（沿用原 v4 语义，见类注释）。
     */
    fun matchesPrefix(ip: ByteArray, net: ByteArray, prefix: Int): Boolean {
        if (ip.size != net.size) return false
        if (prefix <= 0) return true
        val bits = prefix.coerceAtMost(ip.size * 8)
        val fullBytes = bits / 8
        for (i in 0 until fullBytes) if (ip[i] != net[i]) return false
        val restBits = bits % 8
        if (restBits == 0) return true
        val mask = (0xFF shl (8 - restBits)) and 0xFF
        return (ip[fullBytes].toInt() and mask) == (net[fullBytes].toInt() and mask)
    }
}
