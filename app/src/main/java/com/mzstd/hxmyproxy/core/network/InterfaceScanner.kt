package com.mzstd.hxmyproxy.core.network

import com.mzstd.hxmyproxy.core.model.InterfaceStatus
import com.mzstd.hxmyproxy.core.model.InterfaceType
import com.mzstd.hxmyproxy.core.model.ShareInterface
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * 枚举本机网络接口，构造 [ShareInterface] 列表。
 *
 * **不信任接口名**：名称仅作弱提示，类型由「名称前缀 + 结构特征（是否持网关式 .1 地址）」推断，
 * 未知归 [InterfaceType.UNKNOWN]。V1 入口聚焦 IPv4。
 */
class InterfaceScanner {

    fun scan(selectedIds: Set<String>): List<ShareInterface> {
        val result = ArrayList<ShareInterface>()
        val nifs = try {
            NetworkInterface.getNetworkInterfaces() ?: return result
        } catch (e: Exception) {
            return result
        }
        for (nif in Collections.list(nifs)) {
            val up = try { nif.isUp && !nif.isLoopback } catch (e: Exception) { false }
            if (!up) continue
            for (ia in nif.interfaceAddresses) {
                val addr = ia.address ?: continue
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                val prefix = ia.networkPrefixLength.toInt()
                val id = "${nif.name}/${addr.hostAddress}"
                result.add(
                    ShareInterface(
                        id = id,
                        name = nif.name,
                        type = classify(nif.name, addr),
                        address = addr,
                        prefixLength = prefix,
                        gatewayLike = isGatewayLike(addr),
                        isSelected = id in selectedIds,
                        status = InterfaceStatus.UP,
                    )
                )
            }
        }
        return result
    }

    private fun classify(name: String, addr: InetAddress): InterfaceType {
        val n = name.lowercase()
        return when {
            n.startsWith("ap") || n.startsWith("softap") || n.startsWith("swlan") -> InterfaceType.HOTSPOT
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> InterfaceType.USB
            n.startsWith("bt-pan") || n.startsWith("bt") -> InterfaceType.BLUETOOTH
            n.startsWith("eth") -> InterfaceType.ETHERNET
            n.startsWith("wlan") -> if (isGatewayLike(addr)) InterfaceType.HOTSPOT else InterfaceType.WIFI
            else -> InterfaceType.UNKNOWN
        }
    }

    /** 手机是否持网关式地址（末位 .1）——热点/USB/蓝牙主端的典型特征。 */
    private fun isGatewayLike(addr: InetAddress): Boolean {
        val b = addr.address
        return b.size == 4 && (b[3].toInt() and 0xFF) == 1
    }
}
