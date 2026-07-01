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
        // 选中匹配容忍 IP 变化：id 形如 "wlan0/192.168.1.34" 含 IP，换 WiFi/DHCP 续约后 IP 变、id 变，
        // 若只按完整 id 比对，选中会丢失 → 入口列表空、准入退化。故同时按「接口名」（'/' 前）比对：
        // 只要该接口名曾被选中，新 IP 下仍算选中 → refresh 后自动纳入新地址、无缝跟随换网。
        val selectedNames = selectedIds.mapNotNull { it.substringBefore('/').ifEmpty { null } }.toSet()
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
                        isSelected = id in selectedIds || nif.name in selectedNames,
                        status = InterfaceStatus.UP,
                    )
                )
            }
        }
        return result
    }

    private fun classify(name: String, addr: InetAddress): InterfaceType =
        InterfaceClassifier.classify(name, isGatewayLike(addr))

    private fun isGatewayLike(addr: InetAddress): Boolean =
        InterfaceClassifier.isGatewayLike(addr.address)
}
