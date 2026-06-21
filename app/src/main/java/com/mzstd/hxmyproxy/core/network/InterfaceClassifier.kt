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

    /** IPv4 是否网关式（末位 .1）。 */
    fun isGatewayLike(rawAddr: ByteArray): Boolean =
        rawAddr.size == 4 && (rawAddr[3].toInt() and 0xFF) == 1
}
