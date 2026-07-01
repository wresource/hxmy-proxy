package com.mzstd.hxmyproxy.core.network

import com.mzstd.hxmyproxy.core.model.InterfaceType

/**
 * 接口类型推断（纯函数，便于单测）。名称仅作弱提示，结合「是否持网关式 .1 地址」判断。
 */
object InterfaceClassifier {

    fun classify(name: String, gatewayLike: Boolean): InterfaceType {
        val n = name.lowercase()
        return when {
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") -> InterfaceType.HOTSPOT
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> InterfaceType.USB
            n.startsWith("bt-pan") || n.startsWith("bt") -> InterfaceType.BLUETOOTH
            n.startsWith("eth") -> InterfaceType.ETHERNET
            n.startsWith("wlan") -> if (gatewayLike) InterfaceType.HOTSPOT else InterfaceType.WIFI
            else -> InterfaceType.UNKNOWN
        }
    }

    /**
     * 纯上行 / 不可共享接口:蜂窝(移动数据)与 VPN 隧道。
     *
     * 这类接口是「本机上网的上行链路」,不是局域网——别的设备无法连上来,把它们列进「可共享入口」只会误导用户
     * (选了也没用)。故直接从接口列表排除。依据接口名前缀,均为 Linux 内核 / 厂商稳定约定:
     * - 蜂窝:`rmnet*`(高通)、`ccmni*`(联发科)、`pdp*`、`radio*`;
     * - VPN 隧道:`ipsec*`(IPsec,含 Google One VPN)、`tun*`(OpenVPN/WireGuard)、`ppp*`、`tap*`。
     */
    fun isUplinkOnly(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("rev_rmnet") || n.startsWith("v4-rmnet") || n.startsWith("radio") ||
            n.startsWith("ipsec") || n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("tap")
    }

    /** IPv4 是否网关式（末位 .1）。 */
    fun isGatewayLike(rawAddr: ByteArray): Boolean =
        rawAddr.size == 4 && (rawAddr[3].toInt() and 0xFF) == 1
}
