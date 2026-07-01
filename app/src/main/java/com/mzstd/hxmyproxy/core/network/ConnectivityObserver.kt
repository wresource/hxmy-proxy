package com.mzstd.hxmyproxy.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.mzstd.hxmyproxy.core.model.VpnState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 观察「默认网络」的变化（含 VPN 接入/断开）。
 * 暴露 [vpnState]（当前出口 VPN 状态）与 [networkChanges]（任意变化的去抖信号，用于触发接口重扫）。
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _vpnState = MutableStateFlow(VpnState())
    val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

    private val _networkChanges = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val networkChanges: SharedFlow<Unit> = _networkChanges.asSharedFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _vpnState.value = computeVpnState()
            _networkChanges.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            _vpnState.value = computeVpnState()
            _networkChanges.tryEmit(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _vpnState.value = computeVpnState()
            _networkChanges.tryEmit(Unit)
        }
    }

    /**
     * 扫描所有网络判定 VPN 出口状态。**不能**只看 `registerDefaultNetworkCallback` 回调里的「默认网络」caps：
     * bypassable VPN（如 Google One VPN，`VpnTransportInfo.bypassable=true`）下，未绑定网络的进程其默认网络
     * 解析为底层 WiFi（不带 `TRANSPORT_VPN`），默认网络 caps 会**漏报** VPN（实测 detected=false 但 VPN 在线）。
     * 扫 [ConnectivityManager.getAllNetworks] 找带 VPN transport 的网络才可靠（BindSocketSpikeTest 实证）。
     */
    @Suppress("DEPRECATION") // getAllNetworks：bypassable VPN 检测无现成替代；NetworkRequest 回调更重，此处足够
    private fun computeVpnState(): VpnState {
        val networks = cm?.allNetworks ?: return VpnState()
        for (n in networks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return VpnStateDetector.from(caps)
        }
        return VpnState()
    }

    /** 专门监听 VPN 网络的接入/断开/能力变化：bypassable VPN 的接入/断开不一定改变「默认网络」、
     *  从而不触发上面的默认网络回调，故单独监听 VPN transport 才能及时更新状态。 */
    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _vpnState.value = computeVpnState(); _networkChanges.tryEmit(Unit) }
        override fun onLost(network: Network) { _vpnState.value = computeVpnState(); _networkChanges.tryEmit(Unit) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _vpnState.value = computeVpnState(); _networkChanges.tryEmit(Unit) }
    }

    /**
     * 监听底层「非 VPN」网络（WiFi/以太网/热点上游）的接入、断开、IP 变化。
     *
     * **为何必需**：开着系统 VPN（如 Google One）时，默认网络恒为 VPN、Network 对象不变，换 WiFi / DHCP 续约
     * 只反映在底层网络的 [LinkProperties]（IP/路由）上——上面的默认网络回调与 VPN 回调都收不到这类变化。
     * 若不单独监听底层网络的 [onLinkPropertiesChanged]，换 WiFi 后 [networkChanges] 不发信号 → 上层 refresh 不跑
     * → 准入地址（[SubnetAccessController]）、入口 IP、mDNS 全停在旧网段 → 新网段客户端被准入层拒 → 表现为
     * 「换 WiFi 中断，需重启才恢复」。故必须补这条监听。NOT_VPN 不限 transport：WiFi/以太网/热点上游都覆盖。
     */
    private val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _networkChanges.tryEmit(Unit) }
        override fun onLost(network: Network) { _networkChanges.tryEmit(Unit) }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { _networkChanges.tryEmit(Unit) }
    }

    /**
     * 当前上网的底层链路是否为**蜂窝**(移动数据)。开着 VPN 时默认网络是 VPN、看不出真实上行,故扫
     * [ConnectivityManager.getAllNetworks] 找带 INTERNET 的非 VPN 网络看其 transport:只要存在 WiFi/以太网
     * 上行就返回 false(它们能组局域网);仅当上行只有蜂窝时返回 true。用于「移动网络需开热点才能共享」引导。
     */
    @Suppress("DEPRECATION") // getAllNetworks:与 computeVpnState 同因,bypassable VPN 下无现成替代
    fun uplinkIsCellular(): Boolean {
        val networks = cm?.allNetworks ?: return false
        var cellular = false
        for (n in networks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) return false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true
        }
        return cellular
    }

    fun start() {
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        // 单独监听 VPN transport（NetworkRequest.Builder 默认带 NOT_VPN，须先移除才能匹配 VPN 网络）。
        val vpnReq = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching { cm?.registerNetworkCallback(vpnReq, vpnCallback) }
        // 底层非 VPN 网络（WiFi/以太网/热点）的 IP 变化监听——换 WiFi 无缝的关键（见 underlyingCallback 注释）。
        val underlyingReq = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm?.registerNetworkCallback(underlyingReq, underlyingCallback) }
        _vpnState.value = computeVpnState()   // 注册后主动算一次，VPN 已稳定连接时即时检出
    }

    fun stop() {
        runCatching { cm?.unregisterNetworkCallback(callback) }
        runCatching { cm?.unregisterNetworkCallback(vpnCallback) }
        runCatching { cm?.unregisterNetworkCallback(underlyingCallback) }
    }
}
