package com.mzstd.hxmyproxy.data.repository

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
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
import com.mzstd.hxmyproxy.core.proxy.NioRelayReactor
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.PacGenerator
import com.mzstd.hxmyproxy.core.proxy.PacServer
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.Socks5ProxyServer
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import com.mzstd.hxmyproxy.core.security.SingleCredentialAuthenticator
import com.mzstd.hxmyproxy.core.security.SubnetAccessController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "hxmyproxy"

/** accept 握手线程池固定大小：握手是短工，建连(suspend)与 relay 期间 handle 均挂起不占线程，无需随连接数放大。 */
private const val ACCEPT_THREADS = 64

/** 每连接约占的 FD 数（下行 client + 上行 upstream），用于按系统 FD 软上限反推安全的最大连接数。 */
private const val FD_PER_CONN = 2
/** 给 App 自身保留的 FD（DataStore/日志/线程 pipe/监听 socket 等）。 */
private const val FD_RESERVED = 256

/** 启用 NIO 非阻塞 relay（少量 selector 线程替代每隧道 2 阻塞线程）。过渡 flag，后续可提为设置项；
 *  false 回退旧阻塞 RelayEngine；connectChannel 反射 fd 不可用时也会单连接自动回退阻塞。 */
private const val USE_NIO_RELAY = true
/** NIO relay selector 线程数上限：超过对吞吐无益（瓶颈在出口带宽/RTT，非 selector）。实际按 CPU 核数取，封顶于此。 */
private const val NIO_RELAY_WORKERS_MAX = 4

/**
 * 代理引擎（单例）：持有 accept/relay 有界线程池、三台 server、连接计数、mDNS 与连通性，
 * 由前台服务以其 Scope 启停；对外暴露 [state]（[ShareState]）。
 */
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
    private val signalProvider: com.mzstd.hxmyproxy.core.network.SignalProvider,
    private val endpointHistoryRepository: EndpointHistoryRepository,
    private val credentialStore: CredentialStore,
    private val ruleEngine: com.mzstd.hxmyproxy.core.rules.RuleEngine,
    private val ruleRepository: RuleRepository,
    private val underlyingNetworkProvider: com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider,
) {
    // 活跃连接数变化时即时推送到 UI（不必等 1s ticker）。
    private val registry = ConnectionRegistry(onChange = { active ->
        _state.update { it.copy(activeConnections = active) }
    })
    private val totalUp = AtomicLong(0)
    private val totalDown = AtomicLong(0)
    // 共享流量计量：RelayEngine（CONNECT/SOCKS）与 HttpProxyServer（普通 HTTP keep-alive 转发）共用。
    private val trafficSink: (Long, Long) -> Unit = { up, down ->
        if (up > 0) totalUp.addAndGet(up)
        if (down > 0) totalDown.addAndGet(down)
    }
    private val relay = RelayEngine(trafficSink)
    private val connector = OutboundConnector(egressGuard, underlyingNetworkProvider = underlyingNetworkProvider)
    // 按 IP/域名的流量记账（喂监控页会话/域名列表）；add 内部同时把增量喂全局 totalUp/Down。
    private val accounting = TrafficAccounting(globalSink = trafficSink)
    /** 规则重建请求（CONFLATED：只保留最新设置）。串行 worker 消费，杜绝快速开关导致多个 rebuild 乱序覆盖、状态残留。 */
    private val rebuildRequests = kotlinx.coroutines.channels.Channel<ProxySettings>(kotlinx.coroutines.channels.Channel.CONFLATED)

    @Volatile private var currentSettings = ProxySettings()
    @Volatile private var running = false
    @Volatile private var servers: List<ProxyServer> = emptyList()
    @Volatile private var engineScope: CoroutineScope? = null
    // 本次会话的子 scope：所有会话内协程（监听/收集器/ticker）都挂这里，stop() 一次性取消，避免停后残留重启。
    @Volatile private var sessionScope: CoroutineScope? = null
    @Volatile private var serverObservers: Job? = null
    // accept 握手 / relay 搬字节各自的有界线程池：硬限阻塞线程数，杜绝用户把连接数/并行度「拉满」时
    // limitedParallelism 弹性视图导致的线程爆炸 → native OOM。会话级，stop()/热重启时 shutdownNow。
    @Volatile private var acceptExecutor: ExecutorService? = null
    @Volatile private var relayExecutor: ExecutorService? = null
    /** NIO 非阻塞 relay 反应堆（会话级：startServers 创建+start、stopServers stop）。 */
    @Volatile private var nioReactor: NioRelayReactor? = null
    /** 系统单进程 FD 软上限（/proc/self/limits）。-1=未读，0=读取失败（则不钳制）。 */
    @Volatile private var cachedFdLimit = -1
    @Volatile private var lastServerKey: String = ""
    @Volatile private var lastRecordedEntryKey: String = ""
    /** 上次刷新时已选接口的 IP 集合；与本次比较，在 WiFi 切换/IP 变化时主动重发 mDNS（新 IP 的 A 记录）。 */
    @Volatile private var lastInterfaceIps: Set<String> = emptySet()

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    fun isRunning(): Boolean = running

    /** 以服务 Scope 启动（幂等）。读取设置快照 → 建调度器/服务器 → 扫接口 → 起监听 → 订阅网络/设置变化。 */
    suspend fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        // 新会话边界：累计流量与连接计数归零（避免上次会话残留）。
        totalUp.set(0)
        totalDown.set(0)
        registry.reset()
        accounting.reset()
        val s = settingsRepository.settings.first()
        currentSettings = s
        applyTunables(s)
        // 初始凭据就位（在 server 接受连接前），避免首个连接时认证器为空。
        credentialStore.credentials.first().let { c ->
            authenticator.username = c.username
            authenticator.password = c.password
        }

        // 会话子 scope（SupervisorJob 挂在服务 scope 下）：stop() 取消它即停掉本会话全部协程，
        // 服务 scope 仍存活以便下次 start；避免"停后残留的 settings 收集器又把监听重启起来"。
        val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = session
        engineScope = session
        startServers(session, s)
        refresh() // 入口历史记录在 refresh() 内完成（覆盖启动后才选接口 / IP 变化）

        connectivityObserver.start()
        underlyingNetworkProvider.start()
        session.launch { connectivityObserver.networkChanges.collect { refresh() } }
        // mDNS 注册是异步的（系统 Probing ~1s）：注册真正完成/失败后刷新诊断，
        // 避免 mdnsPublished 停在 publish 那一刻的「未发布」假象（真机日志证实服务其实注册成功）。
        session.launch { mdnsPublisher.registeredName.collect { refresh() } }
        session.launch {
            connectivityObserver.vpnState.collect { vpn ->
                _state.update { it.copy(vpn = vpn, diagnostics = it.diagnostics.copy(vpnDetected = vpn.detected)) }
            }
        }
        // 规则重建 worker：串行消费 + CONFLATED 取最新设置 —— 快速开关（如广告表开/关）时只会重建到最终状态，
        // 不会出现「关掉后慢的『开』重建后完成、把规则覆盖回开状态」的残留 bug。
        session.launch(Dispatchers.IO) {
            for (s in rebuildRequests) ruleRepository.rebuild(s)
        }
        session.launch {
            settingsRepository.settings.collect { ns ->
                currentSettings = ns
                applyTunables(ns)
                rebuildRequests.trySend(ns)
                if (running && serverKey(ns) != lastServerKey) {
                    // 端口 / 协议开关 / 并行度变更 → 热重启监听，即时生效（无需手动保存）
                    stopServers()
                    startServers(session, ns)
                }
                refresh()
            }
        }
        // 凭据变更即时推入认证器（与 server 生命周期解耦，无需重启）。
        session.launch {
            credentialStore.credentials.collect { c ->
                authenticator.username = c.username
                authenticator.password = c.password
            }
        }
        // 实时速率 + 活跃连接（约 1s 窗口）
        session.launch {
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
                val sig = signalProvider.current()
                accounting.ageOut(ACCOUNTING_AGE_OUT_MS)
                val snap = accounting.snapshot(TOP_DOMAINS)
                _state.update {
                    it.copy(
                        activeConnections = registry.activeGlobal,
                        uploadRateBps = upRate,
                        downloadRateBps = downRate,
                        totalBytes = up + down,
                        signalLevel = sig.level,
                        signalDbm = sig.dbm,
                        clients = snap.clients,
                        topDomains = snap.topDomains,
                    )
                }
            }
        }
        // lockdown 探活：绑底层网络连不通但 VPN 能连 → 疑似系统「阻止无 VPN 连接」拦了出口分流。
        session.launch(Dispatchers.IO) {
            delay(3000)
            if (running && underlyingNetworkProvider.current() != null) {
                val realOk = probeEgress(bypass = true)
                _state.update { it.copy(lockdownSuspected = !realOk && probeEgress(bypass = false)) }
            }
        }
        _state.update { it.copy(running = true) }
    }

    /** 探活：经底层网络（bypass=true）或默认网络（含 VPN）连国内可达的 223.5.5.5:53。 */
    private suspend fun probeEgress(bypass: Boolean): Boolean = runCatching {
        connector.connect("223.5.5.5", 53, bypassVpn = bypass).use { true }
    }.getOrDefault(false)

    fun stop() {
        running = false
        stopServers()
        mdnsPublisher.unpublishAll()
        connectivityObserver.stop()
        underlyingNetworkProvider.stop()
        // 取消本会话全部协程（收集器/ticker）：杜绝停止后 settings 收集器又把监听重启起来。
        sessionScope?.cancel()
        sessionScope = null
        engineScope = null
        totalUp.set(0)
        totalDown.set(0)
        lastRecordedEntryKey = ""
        lastInterfaceIps = emptySet()
        registry.reset()
        accounting.reset()
        _state.value = ShareState()
    }

    /** 只启动用户开启的协议监听（关掉的不占端口）。 */
    private fun startServers(scope: CoroutineScope, s: ProxySettings) {
        // 两个有界线程池分工，解除"建连排在搬字节后面"的队头阻塞；同时对阻塞线程数设真实硬上限：
        //  - acceptDispatcher：每连接 handle（握手/读头/明文 keep-alive 转发）。固定 ACCEPT_THREADS——
        //    握手是短工，建连(suspend connect)与 relay 期间 handle 均挂起不占线程，故不随 maxGlobal 放大。
        //  - relayDispatcher：relay 双向搬字节。每连接双向各占 1 槽 → 容量 2×relayParallelism（≤128，硬顶）。
        //    用 newFixedThreadPool 而非 Dispatchers.IO.limitedParallelism(N)：后者是弹性视图、不受
        //    io.parallelism 钳制，「拉满」时会把底层池弹到上千线程 → native OOM 崩溃（见 HxmyProxyApp 注释）。
        acceptExecutor?.shutdownNow()
        relayExecutor?.shutdownNow()
        val accExec = Executors.newFixedThreadPool(ACCEPT_THREADS, daemonFactory("hxmy-accept"))
        val relExec = Executors.newFixedThreadPool(2 * s.limits.relayParallelism, daemonFactory("hxmy-relay"))
        acceptExecutor = accExec
        relayExecutor = relExec
        val acceptDispatcher = accExec.asCoroutineDispatcher()
        val relayDispatcher = relExec.asCoroutineDispatcher()
        // NIO 非阻塞 relay 反应堆（会话级）：flag 开则创建并 start，CONNECT/SOCKS 隧道走它；否则 null（回退阻塞 relay）。
        // selector 数按 CPU 核数自动拉满（NIO 的并行维度），封顶 NIO_RELAY_WORKERS_MAX。
        val nioWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, NIO_RELAY_WORKERS_MAX)
        val reactor = if (USE_NIO_RELAY) NioRelayReactor(workerCount = nioWorkers).also { it.start() } else null
        nioReactor = reactor
        val list = mutableListOf<ProxyServer>()
        if (s.httpEnabled) {
            HttpProxyServer(acceptDispatcher, accessController, registry, connector, relay, { authenticator }, { currentSettings.limits }, relayDispatcher, accounting, trafficSink, ruleEngine, reactor, USE_NIO_RELAY)
                .also { it.start(scope, s.httpPort); list += it }
        }
        if (s.socksEnabled) {
            Socks5ProxyServer(acceptDispatcher, accessController, registry, connector, relay, { authenticator }, { currentSettings.limits }, relayDispatcher, accounting, ruleEngine, reactor, USE_NIO_RELAY)
                .also { it.start(scope, s.socksPort); list += it }
        }
        if (s.pacEnabled) {
            PacServer(
                acceptDispatcher, accessController, registry,
                httpProxyPort = { if (currentSettings.httpEnabled) currentSettings.httpPort else null },
            ) { generatePac() }
                .also { it.start(scope, s.pacPort); list += it }
        }
        servers = list
        lastServerKey = serverKey(s)
        // 观察每台 server 的 bind 结果：失败（端口占用/无效）即时汇入 state 提示用户，而非崩溃或静默。
        serverObservers?.cancel()
        serverObservers = scope.launch {
            list.forEach { srv ->
                launch { srv.bindError.collect { recomputeServerStatus() } }
                launch { srv.boundPort.collect { recomputeServerStatus() } }
            }
        }
    }

    private fun stopServers() {
        serverObservers?.cancel()
        serverObservers = null
        // 先各 server.stop()（主动关在途 socket → 阻塞 read 抛错 → relay 协程退出、线程空出），
        // 再 shutdownNow 回收池线程释放内存。顺序很重要：阻塞 read 不响应线程中断，只响应 socket 关闭。
        servers.forEach { it.stop() }
        servers = emptyList()
        // reactor 拆除全部在途隧道（resume 各 relay 协程）+ 关 selector；再回收阻塞池。
        nioReactor?.stop()
        nioReactor = null
        acceptExecutor?.shutdownNow()
        relayExecutor?.shutdownNow()
        acceptExecutor = null
        relayExecutor = null
        _state.update { it.copy(portBindErrors = emptySet()) }
    }

    /** 守护线程工厂：daemon 不阻止进程退出；命名便于在 trace/线程转储中识别 accept/relay 池。 */
    private fun daemonFactory(name: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { r ->
            Thread(r, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    /** 汇总各 server 的 bind 状态（端口是否起来 / bind 失败的协议）到 state，供诊断与设置页提示。 */
    private fun recomputeServerStatus() {
        // 停止后到来的滞后回调不再发布（stop() 已重置 state，避免被旧 server 列表覆盖）。
        if (!running) return
        val errs = servers.filter { it.bindError.value != null }.map { it.protocol }.toSet()
        _state.update { st ->
            st.copy(
                portBindErrors = errs,
                diagnostics = st.diagnostics.copy(
                    httpPortUp = portUp(ProxyProtocol.HTTP),
                    socksPortUp = portUp(ProxyProtocol.SOCKS5),
                    pacPortUp = portUp(ProxyProtocol.PAC),
                ),
            )
        }
    }

    /**
     * 影响监听结构的设置指纹（端口/协议开关）；变化即触发热重启。
     * relayParallelism 已移出——避免拖性能滑块触发热重启把活跃隧道变孤儿（其变更在下次启动时生效）。
     */
    private fun serverKey(s: ProxySettings): String =
        "${s.httpEnabled}:${s.httpPort}|${s.socksEnabled}:${s.socksPort}|${s.pacEnabled}:${s.pacPort}"

    /** 停止态也扫描接口，便于用户先选接口再启动（运行态由 [refresh] 维护，故此处直接返回）。 */
    suspend fun refreshInterfaces() {
        if (running) return
        val s = settingsRepository.settings.first()
        currentSettings = s
        val interfaces = interfaceScanner.scan(s.selectedInterfaceIds)
        val perm = localNetworkPermissionManager.isGranted()
        val sig = signalProvider.current()
        _state.update {
            it.copy(
                interfaces = interfaces,
                localNetworkPermissionGranted = perm,
                diagnostics = it.diagnostics.copy(localNetworkPermissionGranted = perm),
                signalLevel = sig.level,
                signalDbm = sig.dbm,
            )
        }
    }

    private fun applyTunables(s: ProxySettings) {
        // 按系统 FD 预算反推安全上限：用户拉满 maxGlobal 时,2×FD/连接可能逼近 rlimit → EMFILE。
        val fdCap = fdSafeMaxGlobal()
        val effectiveMax = s.limits.maxGlobalConnections.coerceAtMost(fdCap)
        if (effectiveMax < s.limits.maxGlobalConnections) {
            FileLog.w(TAG, "maxGlobal=${s.limits.maxGlobalConnections} 超 FD 安全上限 $fdCap" +
                "(每连接约 $FD_PER_CONN FD, rlimit=$cachedFdLimit),已钳制为 $effectiveMax")
        }
        registry.maxGlobal = effectiveMax
        registry.maxPerClient = s.limits.maxPerClientConnections.coerceAtMost(effectiveMax)
        accounting.maxDomains = s.limits.maxTrackedDomains
        egressGuard.blockPrivateLan = s.blockPrivateLanEgress
        authenticator.enabled = s.authEnabled
    }

    /**
     * 按 FD 预算反推安全的最大连接数：每连接约占 [FD_PER_CONN] 个 FD,另预留 [FD_RESERVED] 给 App 自身。
     * 读不到 rlimit 时返回 Int.MAX（退回不钳制）,避免误限。结果缓存（rlimit 进程生命周期内不变）。
     */
    private fun fdSafeMaxGlobal(): Int {
        if (cachedFdLimit < 0) cachedFdLimit = readFdSoftLimit()
        if (cachedFdLimit <= 0) return Int.MAX_VALUE
        return ((cachedFdLimit - FD_RESERVED) / FD_PER_CONN)
            .coerceAtLeast(ConnectionLimits.RANGE_GLOBAL.first)
    }

    /** 读 /proc/self/limits 的 "Max open files" 软上限;失败返回 0。 */
    private fun readFdSoftLimit(): Int = runCatching {
        java.io.File("/proc/self/limits").readLines()
            .firstOrNull { it.startsWith("Max open files") }
            ?.split(Regex("\\s+"))?.getOrNull(3)?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun refresh() {
        val s = currentSettings
        val interfaces = interfaceScanner.scan(s.selectedInterfaceIds)
        val selected = interfaces.filter { it.isSelected }
        accessController.update(selected.map { it.address }.toSet())
        publishMdns(s)
        // WiFi 切换 / IP 变化（DHCP 续约）时主动重发 mDNS：端口不变故 publishMdns 幂等不重注册，
        // 但必须重注册才能在新 IP 上通告 A 记录（NsdManager 不自动跟随网络变化）。仅在已有 IP→新 IP 时触发。
        val currentIps = selected.mapNotNull { it.address.hostAddress }.toSet()
        if (running && s.mdnsEnabled && lastInterfaceIps.isNotEmpty() && currentIps != lastInterfaceIps) {
            mdnsPublisher.republish()
        }
        lastInterfaceIps = currentIps
        val entries = computeEntries(selected, s)
        // 记录入口到历史（运行中、入口非空、且与上次不同才写——覆盖启动后选接口/IP 变化，避免重复写盘）
        if (running && entries.isNotEmpty()) {
            val entryKey = entries.joinToString("|") { "${it.protocol}:${it.host}:${it.port}" }
            if (entryKey != lastRecordedEntryKey) {
                lastRecordedEntryKey = entryKey
                val now = System.currentTimeMillis()
                engineScope?.launch {
                    endpointHistoryRepository.record(
                        entries.map { com.mzstd.hxmyproxy.core.model.HistoryEndpoint(it.protocol, it.host, it.port, now) },
                    )
                }
            }
        }
        val perm = localNetworkPermissionManager.isGranted()
        val sig = signalProvider.current()
        _state.update { st ->
            st.copy(
                running = running,
                localNetworkPermissionGranted = perm,
                interfaces = interfaces,
                recommendedEntries = entries,
                signalLevel = sig.level,
                signalDbm = sig.dbm,
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
        const val TOP_DOMAINS = 20
        const val ACCOUNTING_AGE_OUT_MS = 5 * 60 * 1000L
    }
}
