package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.model.EgressNetworkChoice
import com.mzstd.hxmyproxy.core.model.EgressStatus

/**
 * 多网络出口提供者：同时持有 WiFi / 蜂窝 / 以太网(含 USB 网卡) / VPN 各自的 [Network] 句柄，
 * 供 [com.mzstd.hxmyproxy.core.proxy.OutboundConnector] 按用户选择的出口 **per-socket 绑定**
 * （官方推荐 per-socket，不用 bindProcessToNetwork——那是进程级，会破坏「其它流量各走各路」）。
 *
 * - 各 transport 各注册一个 `registerNetworkCallback`「只监听不保网」，维护句柄 + 在线状态供 UI。
 * - 用户选了某**物理**出口时额外 `requestNetwork` 把它拉起并保持（如 WiFi 在时点亮蜂窝，
 *   官方 javadoc 背书；需 CHANGE_NETWORK_STATE，已在 Manifest）。VPN 出口只能监听（VpnService 建立，
 *   app 无法 requestNetwork 拉起 VPN），拿不到就回退默认。
 * - [current] = DIRECT 分流的底层物理网络（绕过共享 VPN），优先 WiFi 的任一非 VPN 物理网络（语义不变）。
 * - [egressNetwork] = PROXY 出站按用户选择；AUTO 返回 null（走系统默认，含 VPN）。
 * - ⚠️ always-on VPN + lockdown（「阻止无 VPN 连接」）下内核丢弃非 tun 流量，绑物理网络的 socket
 *   仍连不通——这是硬边界，句柄非空也可能无连通性；UI 侧据 VPN 在线态给警示。
 */
class UnderlyingNetworkProvider(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var wifiNet: Network? = null
    @Volatile private var cellularNet: Network? = null
    @Volatile private var ethernetNet: Network? = null
    @Volatile private var vpnNet: Network? = null

    /** WiFi 句柄是否通过系统连通性校验（NET_CAPABILITY_VALIDATED）。仅诊断——纯内网 WiFi 合法地无 VALIDATED。 */
    @Volatile
    var validated: Boolean = false
        private set

    @Volatile private var choice: EgressNetworkChoice = EgressNetworkChoice.AUTO

    /** DIRECT 分流用的底层物理网络（绕过共享 VPN）：优先 WiFi，其次以太网/蜂窝。 */
    fun current(): Network? = wifiNet ?: ethernetNet ?: cellularNet

    /** PROXY 出站按用户选择的出口网络；AUTO 返回 null（走系统默认，含 VPN）。 */
    fun egressNetwork(): Network? = when (choice) {
        EgressNetworkChoice.AUTO -> null
        EgressNetworkChoice.VPN -> vpnNet
        EgressNetworkChoice.WIFI -> wifiNet
        EgressNetworkChoice.CELLULAR -> cellularNet
        EgressNetworkChoice.ETHERNET -> ethernetNet
    }

    /** 各出口在线状态快照（UI 置灰不可用项）。 */
    fun status(): EgressStatus = EgressStatus(
        wifi = wifiNet != null,
        cellular = cellularNet != null,
        ethernet = ethernetNet != null,
        vpn = vpnNet != null,
    )

    private fun physReq(transport: Int) = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(transport)
        .build()

    private val wifiReq = physReq(NetworkCapabilities.TRANSPORT_WIFI)
    private val cellularReq = physReq(NetworkCapabilities.TRANSPORT_CELLULAR)
    private val ethernetReq = physReq(NetworkCapabilities.TRANSPORT_ETHERNET)
    // VPN：默认自带 NOT_VPN，须移除并显式加 TRANSPORT_VPN 才能匹配到 VPN 网络（官方 NET_CAPABILITY_NOT_VPN javadoc）。
    private val vpnReq = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .build()

    private val wifiCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            val old = wifiNet; wifiNet = n
            if (old != n) FileLog.w(TAG, "egress[wifi] -> $n (was $old)")
        }
        override fun onLost(n: Network) {
            if (wifiNet == n) { wifiNet = null; validated = false; FileLog.w(TAG, "egress[wifi] lost: $n") }
        }
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            if (n == wifiNet) validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
    private val cellularCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { cellularNet = n }
        override fun onLost(n: Network) { if (cellularNet == n) cellularNet = null }
    }
    private val ethernetCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            val old = ethernetNet; ethernetNet = n
            if (old != n) FileLog.w(TAG, "egress[ethernet] -> $n (was $old)")
        }
        override fun onLost(n: Network) {
            if (ethernetNet == n) { ethernetNet = null; FileLog.w(TAG, "egress[ethernet] lost: $n") }
        }
    }
    private val vpnCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { vpnNet = n }
        override fun onLost(n: Network) { if (vpnNet == n) vpnNet = null }
    }

    /** 选了物理出口时的「拉起并保持」request 回调（保活网络，不做别的——句柄由上面监听更新）。 */
    @Volatile private var pendingReqCb: ConnectivityManager.NetworkCallback? = null

    @Volatile private var registered = false

    /** 当前 requestNetwork 保活的 transport（null=未请求，对应 AUTO/VPN）。 */
    @Volatile private var pendingChoice: EgressNetworkChoice? = null

    /** 由 applyTunables 推入用户的出口选择；物理出口按需 requestNetwork 拉起并保持（幂等）。 */
    fun setEgressChoice(c: EgressNetworkChoice) {
        choice = c
        reconcileRequest()
    }

    /** 让 requestNetwork 状态与 [choice] 一致：物理出口保活对应网络，AUTO/VPN 撤销请求。幂等。 */
    private fun reconcileRequest() {
        val want = when (choice) {
            EgressNetworkChoice.WIFI, EgressNetworkChoice.CELLULAR, EgressNetworkChoice.ETHERNET -> choice
            else -> null
        }
        if (want == pendingChoice) return
        pendingReqCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        pendingReqCb = null
        pendingChoice = null
        val req = when (want) {
            EgressNetworkChoice.WIFI -> wifiReq
            EgressNetworkChoice.CELLULAR -> cellularReq
            EgressNetworkChoice.ETHERNET -> ethernetReq
            else -> return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {}
        try {
            cm.requestNetwork(req, cb)   // 保持该网络在线（如 WiFi 时点亮蜂窝）
            pendingReqCb = cb
            pendingChoice = want
        } catch (e: Exception) {
            FileLog.w(TAG, "egress requestNetwork($want) failed: ${e.message}")
        }
    }

    fun start() {
        if (registered) return
        try {
            cm.registerNetworkCallback(wifiReq, wifiCb)
            cm.registerNetworkCallback(cellularReq, cellularCb)
            cm.registerNetworkCallback(ethernetReq, ethernetCb)
            cm.registerNetworkCallback(vpnReq, vpnCb)
            registered = true
            // 注意:只注册监听,不在此 requestNetwork——监听可在停止态常驻(出口卡显示在线态),
            // 而 requestNetwork「拉起蜂窝」只在共享运行时由 setEgressChoice 触发(省电)。
        } catch (e: Exception) {
            Log.w(TAG, "egress network callbacks register failed: ${e.message}")
        }
    }

    /** 共享停止时暂停出口保活：撤销 requestNetwork 省电，但**保留监听**——停止态出口卡仍显示各网络在线状态。 */
    fun pause() {
        pendingReqCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        pendingReqCb = null
        pendingChoice = null
    }

    fun stop() {
        if (!registered) return
        listOf(wifiCb, cellularCb, ethernetCb, vpnCb, pendingReqCb).forEach {
            it ?: return@forEach
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        pendingReqCb = null
        pendingChoice = null
        registered = false
        wifiNet = null; cellularNet = null; ethernetNet = null; vpnNet = null
        validated = false
    }

    private companion object {
        const val TAG = "hxmyproxy"
    }
}
