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
import com.mzstd.hxmyproxy.core.proxy.PacGenerator
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
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
    private val totalUp = AtomicLong(0)
    private val totalDown = AtomicLong(0)
    private val relay = RelayEngine { up, down ->
        if (up > 0) totalUp.addAndGet(up)
        if (down > 0) totalDown.addAndGet(down)
    }
    private val connector = OutboundConnector(egressGuard)

    @Volatile private var currentSettings = ProxySettings()
    @Volatile private var running = false
    private var servers: List<ProxyServer> = emptyList()
    @Volatile private var engineScope: CoroutineScope? = null
    @Volatile private var lastServerKey: String = ""

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

        engineScope = scope
        startServers(scope, s)
        refresh()

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
                if (serverKey(ns) != lastServerKey) {
                    // 端口 / 协议开关 / 并行度变更 → 热重启监听，即时生效（无需手动保存）
                    stopServers()
                    startServers(scope, ns)
                }
                refresh()
            }
        }
        // 实时速率 + 活跃连接（约 1s 窗口）
        scope.launch {
            var lastUp = 0L
            var lastDown = 0L
            while (isActive) {
                delay(1000)
                val up = totalUp.get()
                val down = totalDown.get()
                val upRate = (up - lastUp).coerceAtLeast(0)
                val downRate = (down - lastDown).coerceAtLeast(0)
                lastUp = up
                lastDown = down
                _state.update {
                    it.copy(
                        activeConnections = registry.activeGlobal,
                        uploadRateBps = upRate,
                        downloadRateBps = downRate,
                    )
                }
            }
        }
        _state.update { it.copy(running = true) }
    }

    fun stop() {
        running = false
        stopServers()
        mdnsPublisher.unpublishAll()
        connectivityObserver.stop()
        engineScope = null
        totalUp.set(0)
        totalDown.set(0)
        _state.value = ShareState()
    }

    /** 只启动用户开启的协议监听（关掉的不占端口）。 */
    private fun startServers(scope: CoroutineScope, s: ProxySettings) {
        val ioDispatcher = Dispatchers.IO.limitedParallelism(s.limits.relayParallelism)
        val list = mutableListOf<ProxyServer>()
        if (s.httpEnabled) {
            HttpProxyServer(ioDispatcher, accessController, registry, connector, relay, { authenticator }, { currentSettings.limits })
                .also { it.start(scope, s.httpPort); list += it }
        }
        if (s.socksEnabled) {
            Socks5ProxyServer(ioDispatcher, accessController, registry, connector, relay, { authenticator }, { currentSettings.limits })
                .also { it.start(scope, s.socksPort); list += it }
        }
        if (s.pacEnabled) {
            PacServer(ioDispatcher, accessController, registry) { generatePac() }
                .also { it.start(scope, s.pacPort); list += it }
        }
        servers = list
        lastServerKey = serverKey(s)
    }

    private fun stopServers() {
        servers.forEach { it.stop() }
        servers = emptyList()
    }

    /** 影响监听结构的设置指纹（端口/协议开关/并行度）；变化即触发热重启。 */
    private fun serverKey(s: ProxySettings): String =
        "${s.httpEnabled}:${s.httpPort}|${s.socksEnabled}:${s.socksPort}|${s.pacEnabled}:${s.pacPort}|${s.limits.relayParallelism}"

    /** 停止态也扫描接口，便于用户先选接口再启动（运行态由 [refresh] 维护，故此处直接返回）。 */
    suspend fun refreshInterfaces() {
        if (running) return
        val s = settingsRepository.settings.first()
        currentSettings = s
        val interfaces = interfaceScanner.scan(s.selectedInterfaceIds)
        val perm = localNetworkPermissionManager.isGranted()
        _state.update {
            it.copy(
                interfaces = interfaces,
                localNetworkPermissionGranted = perm,
                diagnostics = it.diagnostics.copy(localNetworkPermissionGranted = perm),
            )
        }
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

    /** 动态 PAC（委托 [PacGenerator]）。 */
    fun generatePac(): String = PacGenerator.generate(_state.value.recommendedEntries)

    private companion object {
        const val MDNS_HOST = "hxmyproxy.local"
    }
}
