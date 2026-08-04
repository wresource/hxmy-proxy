package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.model.DirectEgressChoice
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
    private val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
    private val pm: android.content.pm.PackageManager? = context.packageManager

    @Volatile private var wifiNet: Network? = null
    @Volatile private var cellularNet: Network? = null
    @Volatile private var ethernetNet: Network? = null
    @Volatile private var vpnNet: Network? = null

    /** WiFi 句柄是否通过系统连通性校验（NET_CAPABILITY_VALIDATED）。仅诊断——纯内网 WiFi 合法地无 VALIDATED。 */
    @Volatile
    var validated: Boolean = false
        private set

    @Volatile private var choice: EgressNetworkChoice = EgressNetworkChoice.AUTO
    @Volatile private var directChoice: DirectEgressChoice = DirectEgressChoice.AUTO
    @Volatile private var dohChoice: com.mzstd.hxmyproxy.core.model.DohEgressChoice =
        com.mzstd.hxmyproxy.core.model.DohEgressChoice.FOLLOW_DIRECT

    /**
     * DIRECT 分流用的底层物理网络（绕过共享 VPN），按用户的 [directChoice]：
     * AUTO=以太网/USB → WiFi → 蜂窝（**有线优先**，用户要求）；手动指定则取对应句柄。
     * 拿不到返回 null，交由 OutboundConnector fail-closed 断开（绝不回落默认路由=VPN）。
     */
    fun current(): Network? = when (directChoice) {
        DirectEgressChoice.AUTO -> ethernetNet ?: wifiNet ?: cellularNet
        DirectEgressChoice.ETHERNET -> ethernetNet
        DirectEgressChoice.WIFI -> wifiNet
        DirectEgressChoice.CELLULAR -> cellularNet
    }

    /** PROXY 出站按用户选择的出口网络；AUTO 返回 null（走系统默认，含 VPN）。 */
    /**
     * 备用 DNS(DoH)请求走哪张网。返回 null = 跟随进程默认路由(旧行为)。
     *
     * 为什么 DoH 需要单独选一张网:它是「系统 DNS 已经失败之后」的救济手段,而默认路由在
     * egress=VPN 时正是刚出问题的那条 VPN——DoH 于是和它要救的业务走同一条正在死的路、一起失败
     * (0803 实测救援成功率仅 6.5%)。绑到物理网才能在 VPN 半死时仍问得到答案。
     * 注意这与端点选择是配套的:绑物理网后是国内直连,必须有国内可达的端点(见 DOH_ENDPOINTS)。
     */
    fun dohNetwork(): Network? = when (dohChoice) {
        com.mzstd.hxmyproxy.core.model.DohEgressChoice.FOLLOW_DIRECT -> current()
        com.mzstd.hxmyproxy.core.model.DohEgressChoice.DEFAULT -> null
        com.mzstd.hxmyproxy.core.model.DohEgressChoice.ETHERNET -> ethernetNet
        com.mzstd.hxmyproxy.core.model.DohEgressChoice.WIFI -> wifiNet
        com.mzstd.hxmyproxy.core.model.DohEgressChoice.CELLULAR -> cellularNet
    }

    /**
     * 由 applyTunables 推入 DoH 出口选择。**不做 requestNetwork 保活**——DoH 只在解析失败时
     * 偶发使用,复用已有的被动句柄即可;为它常驻拉起一张网不值得(耗电)。
     */
    fun setDohEgressChoice(c: com.mzstd.hxmyproxy.core.model.DohEgressChoice) { dohChoice = c }

    fun egressNetwork(): Network? = when (choice) {
        EgressNetworkChoice.AUTO -> null
        EgressNetworkChoice.VPN -> vpnNet
        EgressNetworkChoice.WIFI -> wifiNet
        EgressNetworkChoice.CELLULAR -> cellularNet
        EgressNetworkChoice.ETHERNET -> ethernetNet
    }

    /** 各出口在线状态快照（UI 置灰不可用项）。*Capable=「有能力」位（见 EgressStatus）。 */
    fun status(): EgressStatus = EgressStatus(
        wifi = wifiNet != null,
        cellular = cellularNet != null,
        ethernet = ethernetNet != null,
        vpn = vpnNet != null,
        cellularCapable = cellularCapable(),
        ethernetCapable = ethernetNet != null,   // 以太网不像蜂窝会休眠，被动句柄即可靠（没插=正确置灰）
    )

    /**
     * 蜂窝「有能力」判据（**免权限**）：设备有蜂窝无线电 + SIM 就绪。用于让「WiFi 在线时也能选蜂窝出口」——
     * 蜂窝在 WiFi 为默认网时被系统休眠、被动回调不 fire（cellularNet 恒 null），但只要有 SIM 就该可选，
     * 选中后由 reconcileDirectRequest/reconcileRequest 的 requestNetwork 主动拉起。必须**先 hasSystemFeature
     * 再调 getSimState**：API33 telephony feature-split 下非蜂窝设备裸调 getSimState 会抛
     * UnsupportedOperationException。getSimState 源码仅 @RequiresFeature，无 @RequiresPermission → 免权限。
     */
    private fun cellularCapable(): Boolean {
        val p = pm ?: return false
        if (!p.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)) return false
        val t = tm ?: return false
        if (t.simState == android.telephony.TelephonyManager.SIM_STATE_READY) return true
        // 双卡/eSIM 兜底：遍历各槽（activeModemCount API30 + getSimState(slot) API26，SDK 守卫）。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            for (i in 0 until t.activeModemCount) {
                if (t.getSimState(i) == android.telephony.TelephonyManager.SIM_STATE_READY) return true
            }
        }
        return false
    }

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

    /** DIRECT 出口的**独立**保活槽（手动指定物理网时用；AUTO 不拉起）。与 PROXY 槽分开，互不干扰。 */
    @Volatile private var directPendingReqCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var directPendingChoice: DirectEgressChoice? = null

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

    /** 由 applyTunables 推入用户的直连出口选择；手动指定物理网时 requestNetwork 拉起保活（幂等）。 */
    fun setDirectEgressChoice(c: DirectEgressChoice) {
        directChoice = c
        reconcileDirectRequest()
    }

    /** DIRECT 手动指定物理出口时拉起并保活对应网络；AUTO 只用被动句柄不主动拉起（省电，见耗电边界）。幂等。 */
    private fun reconcileDirectRequest() {
        val want = when (directChoice) {
            DirectEgressChoice.WIFI, DirectEgressChoice.CELLULAR, DirectEgressChoice.ETHERNET -> directChoice
            else -> null   // AUTO 不主动拉起，只用被动句柄 + fail-closed
        }
        if (want == directPendingChoice) return
        directPendingReqCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        directPendingReqCb = null
        directPendingChoice = null
        val req = when (want) {
            DirectEgressChoice.WIFI -> wifiReq
            DirectEgressChoice.CELLULAR -> cellularReq
            DirectEgressChoice.ETHERNET -> ethernetReq
            else -> return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {}
        try {
            cm.requestNetwork(req, cb)   // 拉起并保持该物理网（如 WiFi 在时点亮蜂窝）
            directPendingReqCb = cb
            directPendingChoice = want
        } catch (e: Exception) {
            FileLog.w(TAG, "direct egress requestNetwork($want) failed: ${e.message}")
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
            // 注册失败 = 四个出口句柄全部拿不到 → 出口选择器整体失效、DIRECT 分流 fail-closed 断开。
            // 此前只有 logcat（release 下被剥离），用户看到的是「选了 Wi-Fi 出口却连不上」而日志无痕。
            Ev.kw(LogCat.EGRESS, "egress.callbackRegisterFailed", "err" to e.toString())
        }
    }

    /** 共享停止时暂停出口保活：撤销 requestNetwork 省电，但**保留监听**——停止态出口卡仍显示各网络在线状态。 */
    fun pause() {
        pendingReqCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        pendingReqCb = null
        pendingChoice = null
        directPendingReqCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        directPendingReqCb = null
        directPendingChoice = null
    }

    fun stop() {
        if (!registered) return
        listOf(wifiCb, cellularCb, ethernetCb, vpnCb, pendingReqCb, directPendingReqCb).forEach {
            it ?: return@forEach
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        pendingReqCb = null
        pendingChoice = null
        directPendingReqCb = null
        directPendingChoice = null
        registered = false
        wifiNet = null; cellularNet = null; ethernetNet = null; vpnNet = null
        validated = false
    }

    private companion object {
        const val TAG = "hxmyproxy"
    }
}
