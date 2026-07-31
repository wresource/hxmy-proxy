package com.mzstd.hxmyproxy.core.network

import java.net.InetAddress

/**
 * IPv4 子网几何：地址↔整数、子网定向广播地址、地址是否落在某组子网内。
 *
 * 从 ProxyServerRepository 抽出：这三个函数是**纯计算**——不读任何 @Volatile 字段、
 * 不碰会话时序、无 IO，因此可以独立单测（原先它们是 private，那些位运算的边界一行都没测过）。
 * 抽出时把 `inEntrySubnet` 隐式读取的 `entrySubnets` 字段改成了显式入参，这是它变纯的关键。
 *
 * **以下两个边界是刻意保留的当前行为，不要"顺手修掉"**：
 * - [subnetBroadcast] 用 `prefix !in 1..31` 排除 /32 与 /0：/32 没有广播地址，
 *   /0 会算出 255.255.255.255（受限广播），两者都不是"子网定向广播"的有效目标。
 * - [inSubnets] 里 `prefix <= 0` 时掩码为 0，**任何地址都算在网内**。改掉会改变
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
     * [addr] 是否落在 [subnets] 中任一子网内。[subnets] 为 (网络号Int, 前缀长度) 对。
     * 非 IPv4 一律返回 false（当前只按 v4 判定入口子网）。
     */
    fun inSubnets(addr: InetAddress, subnets: List<Pair<Int, Int>>): Boolean {
        val ip = ipv4Int(addr) ?: return false
        return subnets.any { (net, prefix) ->
            val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
            (ip and mask) == (net and mask)
        }
    }
}
