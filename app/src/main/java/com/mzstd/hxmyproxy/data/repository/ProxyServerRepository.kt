package com.mzstd.hxmyproxy.data.repository

import com.mzstd.hxmyproxy.core.model.ProxyEntry
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.model.ProxySettings
import com.mzstd.hxmyproxy.core.model.ShareInterface
import com.mzstd.hxmyproxy.core.model.ShareState
import com.mzstd.hxmyproxy.core.network.ConnectivityObserver
import com.mzstd.hxmyproxy.core.network.InterfaceScanner
import com.mzstd.hxmyproxy.core.network.LocalNetworkPermissionManager
import com.mzstd.hxmyproxy.core.network.MdnsPublisher
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.HttpProxyServer
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.PacServer
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.Socks5ProxyServer
import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import com.mzstd.hxmyproxy.core.security.SingleCredentialAuthenticator
import com.mzstd.hxmyproxy.core.security.SubnetAccessController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 代理引擎（单例）：持有 `limitedParallelism(N)` 调度器、三台 server、连接计数、mDNS 与连通性，
 * 由前台服务以其 Scope 启停；对外暴露 [state]（[ShareState]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProxyServerRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val interfaceScanner: InterfaceScanner,
    private val mdnsPublisher: MdnsPublisher,
    private val localNetworkPermissionManager: LocalNetworkPermissionManager,
    private val egressGuard: DefaultEgressGuard,
    private val authenticator: SingleCredentialAuthenticator,
    private val accessController: SubnetAccessController,
) {
    private val registry = ConnectionRegistry()
    private val relay = RelayEngine()
    private val connector = OutboundConnector(egressGuard)

    @Volatile private var currentSettings = ProxySettings()
    @Volatile private var running = false
    private var servers: List<ProxyServer> = emptyList()

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    fun isRunning(): Boolean = running

    /** 以服务 Scope 启动（幂等）。读取设置快照 → 建调度器/服务器 → 扫接口 → 起监听 → 订阅网络/设置变化。 */
    suspend fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        val s = settingsRepository.settings.first()
        currentSettings = s
        applyTunables(s)

        val ioDispatcher = Dispatchers.IO.limitedParallelism(s.limits.relayParallelism)
        val http = HttpProxyServer(ioDispatcher, accessController, registry, connector, relay, { authenticator }, { currentSettings.limits })
        val socks = Socks5ProxyServer(ioDispatcher, accessController, registry, connector, relay, { authenticator }, { currentSettings.limits })
        val pac = PacServer(ioDispatcher, accessController, registry) { generatePac() }
        servers = listOf(http, socks, pac)

        refresh()

        if (s.httpEnabled) http.start(scope, s.httpPort)
        if (s.socksEnabled) socks.start(scope, s.socksPort)
        if (s.pacEnabled) pac.start(scope, s.pacPort)

        connectivityObserver.start()
        scope.launch { connectivityObserver.networkChanges.collect { refresh() } }
        scope.launch {
            connectivityObserver.vpnState.collect { vpn ->
                _state.update { it.copy(vpn = vpn, diagnostics = it.diagnostics.copy(vpnDetected = vpn.detected)) }
            }
        }
        scope.launch {
            settingsRepository.settings.collect { ns ->
                currentSettings = ns
                applyTunables(ns)
                refresh()
            }
        }
        _state.update { it.copy(running = true) }
    }

    fun stop() {
        running = false
        servers.forEach { it.stop() }
        servers = emptyList()
        mdnsPublisher.unpublishAll()
        connectivityObserver.stop()
        _state.value = ShareState()
    }

    private fun applyTunables(s: ProxySettings) {
        registry.maxGlobal = s.limits.maxGlobalConnections
        registry.maxPerClient = s.limits.maxPerClientConnections
        egressGuard.blockPrivateLan = s.blockPrivateLanEgress
        authenticator.enabled = s.authEnabled
    }

    private fun refresh() {
        val s = currentSettings
        val interfaces = interfaceScanner.scan(s.selectedInterfaceIds)
        val selected = interfaces.filter { it.isSelected }
        accessController.update(selected.map { it.address }.toSet())
        publishMdns(s)
        val entries = computeEntries(selected, s)
        val perm = localNetworkPermissionManager.isGranted()
        _state.update { st ->
            st.copy(
                running = running,
                localNetworkPermissionGranted = perm,
                interfaces = interfaces,
                recommendedEntries = entries,
                diagnostics = st.diagnostics.copy(
                    localNetworkPermissionGranted = perm,
                    httpPortUp = portUp(ProxyProtocol.HTTP),
                    socksPortUp = portUp(ProxyProtocol.SOCKS5),
                    pacPortUp = portUp(ProxyProtocol.PAC),
                    mdnsPublished = s.mdnsEnabled && mdnsPublisher.lastRegisteredName != null,
                ),
            )
        }
    }

    private fun portUp(p: ProxyProtocol): Boolean =
        servers.firstOrNull { it.protocol == p }?.boundPort?.value != null

    private fun computeEntries(selected: List<ShareInterface>, s: ProxySettings): List<ProxyEntry> {
        val mdnsName = if (s.mdnsEnabled) MDNS_HOST else null
        val list = ArrayList<ProxyEntry>()
        for (iface in selected) {
            val ip = iface.address.hostAddress ?: continue
            if (s.socksEnabled) list.add(ProxyEntry(ip, s.socksPort, ProxyProtocol.SOCKS5, iface.id, mdnsName, priority = 10))
            if (s.httpEnabled) list.add(ProxyEntry(ip, s.httpPort, ProxyProtocol.HTTP, iface.id, mdnsName, priority = 5))
            if (s.pacEnabled) list.add(ProxyEntry(ip, s.pacPort, ProxyProtocol.PAC, iface.id, mdnsName, priority = 1))
        }
        return list
    }

    private fun publishMdns(s: ProxySettings) {
        if (!s.mdnsEnabled) { mdnsPublisher.unpublishAll(); return }
        val specs = buildList {
            if (s.httpEnabled) add(MdnsPublisher.ServiceSpec("hxmy proxy HTTP", "_http._tcp", s.httpPort))
            if (s.socksEnabled) add(MdnsPublisher.ServiceSpec("hxmy proxy SOCKS5", "_socks._tcp", s.socksPort))
            if (s.pacEnabled) add(MdnsPublisher.ServiceSpec("hxmy proxy PAC", "_http._tcp", s.pacPort))
        }
        if (specs.isNotEmpty()) mdnsPublisher.publish(specs)
    }

    /** 动态 PAC：`hxmyproxy.local` 在前，随后各接口 IP 兜底，最后 DIRECT（D1）。 */
    fun generatePac(): String {
        val entries = _state.value.recommendedEntries
        val socks = entries.filter { it.protocol == ProxyProtocol.SOCKS5 }
        val http = entries.filter { it.protocol == ProxyProtocol.HTTP }
        val tokens = ArrayList<String>()
        socks.firstOrNull { it.mdnsEndpoint != null }?.let { tokens.add("SOCKS5 ${it.mdnsEndpoint}") }
        socks.forEach { tokens.add("SOCKS5 ${it.ipEndpoint}") }
        http.firstOrNull { it.mdnsEndpoint != null }?.let { tokens.add("PROXY ${it.mdnsEndpoint}") }
        http.forEach { tokens.add("PROXY ${it.ipEndpoint}") }
        tokens.add("DIRECT")
        return "function FindProxyForURL(url, host) {\n  return \"${tokens.joinToString("; ")}\";\n}\n"
    }

    private companion object {
        const val MDNS_HOST = "hxmyproxy.local"
    }
}
