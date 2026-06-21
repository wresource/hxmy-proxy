package com.mzstd.hxmyproxy.core.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 出口护栏（反 SSRF）：判断代理是否允许连到某目标地址。 */
interface EgressGuard {
    fun isAllowed(addr: InetAddress): Boolean
}

/**
 * 默认实现（D5）：无条件禁止 loopback / 链路本地 / 任意本机(通配) / 组播；
 * 私网（RFC1918 / CGNAT / ULA）默认放行，除非 [blockPrivateLan] 置 true。
 *
 * 注：阻断「本应用自身监听端口」需运行期端口信息，留待与服务层接线时增强。
 */
class DefaultEgressGuard(
    @Volatile var blockPrivateLan: Boolean = false,
) : EgressGuard {

    override fun isAllowed(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return false       // 127/8, ::1
        if (addr.isLinkLocalAddress) return false       // 169.254/16, fe80::/10
        if (addr.isAnyLocalAddress) return false         // 0.0.0.0, ::
        if (addr.isMulticastAddress) return false
        if (isPrivate(addr)) return !blockPrivateLan
        return true
    }

    private fun isPrivate(addr: InetAddress): Boolean {
        if (addr.isSiteLocalAddress) return true         // 10/8, 172.16/12, 192.168/16
        when (addr) {
            is Inet4Address -> {
                val b = addr.address
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                if (o0 == 100 && o1 in 64..127) return true   // 100.64/10 CGNAT
            }
            is Inet6Address -> {
                if ((addr.address[0].toInt() and 0xFE) == 0xFC) return true   // fc00::/7 ULA
            }
        }
        return false
    }
}
