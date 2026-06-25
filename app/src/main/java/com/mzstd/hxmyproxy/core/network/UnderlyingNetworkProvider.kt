package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * 持有「非 VPN 底层网络（WiFi）」的引用，供 DIRECT 出口分流用：
 * 让规则命中的 upstream socket 绕过本机共享出去的 VPN、从手机真实 WiFi 出口。
 *
 * - 用 [ConnectivityManager.registerNetworkCallback] **仅监听**满足 NOT_VPN + WIFI 的网络，
 *   不用 bindProcessToNetwork（那是进程级，会把所有流量都改走底层网络，破坏「其它域名仍走 VPN」）。
 * - per-socket 绑定由 [OutboundConnector] 用 `network.socketFactory` 完成。
 * - ⚠️ always-on VPN + lockdown（「阻止无 VPN 连接」）下，系统在内核层丢弃非 tun 流量，
 *   绑定到该网络的 socket 仍连不通——这是硬边界，[current] 即便非空也可能无连通性。
 */
class UnderlyingNetworkProvider(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var network: Network? = null

    /** 当前可用的非 VPN 底层网络；无 WiFi / 未就绪时为 null。 */
    fun current(): Network? = network

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { network = n }
        override fun onLost(n: Network) { if (network == n) network = null }
    }

    @Volatile
    private var registered = false

    fun start() {
        if (registered) return
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            Log.w("hxmyproxy", "underlying network callback register failed: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        registered = false
        network = null
    }
}
