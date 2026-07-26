package com.mzstd.hxmyproxy.data.repository

import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.network.LinkProbe
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
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
    private val permissionProbe: com.mzstd.hxmyproxy.core.network.PermissionProbe,
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
    // 上次实际生效的准入网段(hostAddress 集)。①换网瞬间 scan 空时保留旧准入不清空;②evict 只在准入真变化时跑。
    @Volatile private var lastAdmitKey: Set<String> = emptySet()
    /** 上次 refresh 的接口扫描快照（只在变化时落盘，避免刷屏淹没关键事件）。 */
    @Volatile private var lastScanKey: String = ""
    /** 最近见过的客户端地址（ticker 里在线时更新）：手动刷新的段② 探测目标——客户端断连后
     *  accounting 里就没有它了,而那恰是最需要探它的时刻。stop() 清空。 */
    @Volatile private var lastSeenClients: List<java.net.InetAddress> = emptyList()
    /** 最近一次配对自探结果 "loop/lan"（ok/fail/-）；心跳带上，UI 不展示。 */
    @Volatile private var lastSelfProbe: String = "-"
    /** 上次落盘过的自探状态（只记变化：正常运行期零噪音）。 */
    @Volatile private var lastSelfProbeLogged: String? = null
    /** 心跳序号（会话内），每 HEARTBEAT_KEY_EVERY 条镜像一条进 key.log。 */
    private var heartbeatN = 0L

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    /** 手动刷新服务的进行/结果状态（见 [manualReset]）。 */
    private val _resetState = MutableStateFlow(ManualResetPhase.IDLE)
    val resetState: StateFlow<ManualResetPhase> = _resetState.asStateFlow()

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
        session.launch {
            connectivityObserver.networkChanges.collect {
                // 换网后旧网络下解析的 IP 可能不可达：清自建 DNS 缓存，让新请求立刻走新网络解析。
                connector.clearDnsCache()
                refresh()
            }
        }
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
                    // 与 start()/stop() 一致地清零计数：stopServers 会 shutdownNow 掉 dispatcher，
                    // 在途连接 finally 里的 registry.release() 恢复时机不可控 → 计数可能残留，
                    // 表现为某个客户端 IP 卡在 maxPerClient、之后新连接被静默拒绝（唯一按来源 IP 的闸门）。
                    registry.reset()
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
            var tick = 0L
            while (isActive) {
                delay(1000)
                tick++
                val up = totalUp.get()
                val down = totalDown.get()
                val upRate = (up - lastUp).coerceAtLeast(0)
                val downRate = (down - lastDown).coerceAtLeast(0)
                lastUp = up
                lastDown = down
                val sig = signalProvider.current()
                accounting.ageOut(ACCOUNTING_AGE_OUT_MS)
                val snap = accounting.snapshot(TOP_DOMAINS)
                // 段①（客户端→本机）链路时延：每 LINK_PROBE_TICKS 秒探一次在线客户端。
                // 独立协程跑，绝不阻塞这个 1s 速率 ticker；没有客户端就不探（省电、不对着空气发包）。
                if (snap.clients.isNotEmpty()) lastSeenClients = snap.clients.map { it.clientIp }
                if (tick % LINK_PROBE_TICKS == 0L && snap.clients.isNotEmpty()) {
                    val ips = snap.clients.map { it.clientIp }
                    session.launch(Dispatchers.IO) { runCatching { LinkProbe.sample(ips) } }
                }
                // 配对自探（30s）：127.0.0.1 与本机 LAN IP 各连一次本机监听端口，状态变化才落盘。
                if (tick % SELF_PROBE_TICKS == 0L) {
                    session.launch(Dispatchers.IO) { runCatching { selfProbe() } }
                }
                // PERF 心跳（60s）：静默期也有一句话现状。放在 ticker 协程里直接调（本就在 IO 上）。
                if (tick % HEARTBEAT_TICKS == 0L) heartbeat(snap.clients.size, sig)
                _state.update {
                    it.copy(
                        activeConnections = registry.activeGlobal,
                        uploadRateBps = upRate,
                        downloadRateBps = downRate,
                        totalBytes = up + down,
                        signalLevel = sig.level,
                        signalDbm = sig.dbm,
                        linkStats = LinkProbe.stats() ?: com.mzstd.hxmyproxy.core.model.LinkStats(),
                        clients = snap.clients,
                        topDomains = snap.topDomains,
                        blockedTotal = snap.blockedTotal,
                        topBlockedDomains = snap.topBlocked,
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
        // 热点(AP)接口出现**不走网络回调**（它不是上网网络，ConnectivityManager 不上报）→ 只能周期重扫捕捉。
        // 换到「蜂窝+开热点」后,热点接口 up 时自动被 refresh 纳入入口+准入,无需手动重启共享。低频(数秒)且 refresh 幂等,耗电可忽略。
        session.launch {
            while (isActive) {
                delay(HOTSPOT_RESCAN_MS)
                // 仅「走蜂窝上网」时才需周期重扫捕捉热点(AP)接口(它不走网络回调);有 WiFi/以太网入口时
                // 接口/IP 变化都由 ConnectivityObserver 回调驱动 refresh,无需每 3 秒空扫 —— 否则稳定态下
                // scan+写盘 每 3 秒空转(实测每分钟 ~20 次),徒增 CPU/IO。加此条件后 WiFi 场景风暴归零。
                if (connectivityObserver.uplinkIsCellular()) refresh()
            }
        }
        _state.update { it.copy(running = true) }
    }

    /** 探活：经底层网络（bypass=true）或默认网络（含 VPN）连国内可达的 223.5.5.5:53。 */
    private suspend fun probeEgress(bypass: Boolean): Boolean = runCatching {
        connector.connect("223.5.5.5", 53, bypassVpn = bypass).use { true }
    }.getOrDefault(false)

    /**
     * 「手动刷新服务」（用户明确要的手动操作，不做自动）。三段：
     * ① app 层全量重置——清 DNS 缓存、重启监听（含全新 NIO reactor）、清连接计数、
     *   重注册网络回调、重申请出口网络句柄、重算准入；
     * ② 主动向最近见过的客户端发探测包（ICMP/TCP-RST）——手机主动出站的每个单播帧都携带
     *   sender IP+MAC，有机会刷新客户端与 AP 侧的 ARP/转发表项，不用飞行模式就打通；
     * ③ 探测全失败 → [ManualResetPhase.DONE_LINK_DEAD]，UI 提示陈旧状态在系统 WiFi 层
     *   （app 无权开关 WiFi，Android 10 起 setWifiEnabled 已失效）并引导拉起系统网络面板。
     */
    fun manualReset() {
        val session = sessionScope ?: return
        if (!running || !_resetState.compareAndSet(ManualResetPhase.IDLE, ManualResetPhase.RUNNING)) return
        session.launch(Dispatchers.IO) {
            val result = runCatching {
                Ev.k(LogCat.SVC, "manual.reset", "by" to "user")
                // 段①：全量重置 app 层。
                connector.clearDnsCache()
                stopServers()
                registry.reset()
                connectivityObserver.stop()
                connectivityObserver.start()
                underlyingNetworkProvider.pause()
                underlyingNetworkProvider.start()
                startServers(session, currentSettings)
                refresh()
                // 段②：主动探测最近客户端（每个目标最多 3 发；发包本身就是目的——刷沿路表项）。
                val targets = lastSeenClients
                if (targets.isEmpty()) return@runCatching ManualResetPhase.DONE_NO_CLIENT
                val reachable = targets.any { addr ->
                    (1..MANUAL_PROBE_ATTEMPTS).any { LinkProbe.probe(addr) != null }
                }
                if (reachable) ManualResetPhase.DONE_OK else ManualResetPhase.DONE_LINK_DEAD
            }.getOrElse { ManualResetPhase.DONE_LINK_DEAD }
            Ev.k(LogCat.SVC, "manual.reset.done", "result" to result, "targets" to lastSeenClients.size)
            _resetState.value = result
        }
    }

    /** UI 消费完结果提示后回位（RUNNING 期间不允许清，防止把进行中的状态吞掉）。 */
    fun ackManualReset() {
        if (_resetState.value != ManualResetPhase.RUNNING) _resetState.value = ManualResetPhase.IDLE
    }

    /**
     * 配对自探：分别经 loopback（127.0.0.1）与本机 LAN IP 连一次本机监听端口。
     * 判读：loop=ok + lan=fail ⇒ 内核对 LAN IP 的入站路径被拦；双 ok 而客户端仍连不上
     * ⇒ SYN 根本没到手机（app 无罪的铁证——7-26 排障里「SYN 没到」与「accept 了」同形的盲区，
     * 这一对探针把它从外面切开）。注意 LAN 自探同样走内核本机路径、不经无线，所以它**不能**
     * 证明无线层可达——它的价值恰恰是把「app/内核」与「无线/对端」两个世界分开。
     * 只在状态变化时落 key.log（正常运行零噪音）；最近结果随心跳可见。
     */
    private fun selfProbe() {
        val port = servers.firstNotNullOfOrNull { it.boundPort.value } ?: return
        val lan = lastAdmitKey.firstOrNull()
        val loopOk = tcpSelfProbe("127.0.0.1", port)
        val lanOk = lan?.let { tcpSelfProbe(it, port) }
        val state = (if (loopOk) "ok" else "fail") + "/" + (lanOk?.let { if (it) "ok" else "fail" } ?: "-")
        lastSelfProbe = state
        if (state != lastSelfProbeLogged) {
            Ev.kw(LogCat.CONN, "selfprobe", "state" to "${lastSelfProbeLogged ?: "-"}->$state", "port" to port)
            lastSelfProbeLogged = state
        }
    }

    /**
     * 先 bind 拿本地源端口并登记到 [SelfProbeMarks]，再 connect——accept 侧凭源端口识别探针，
     * 不误伤真实的本机自用连接。注意 127 腿会被准入拒（loopback 不放行），但 connect 的三次握手
     * 在内核 backlog 层就已完成并成功返回，探测语义（端口活着、内核可达）不受影响。
     */
    private fun tcpSelfProbe(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.bind(java.net.InetSocketAddress(0))
            com.mzstd.hxmyproxy.core.proxy.SelfProbeMarks.mark(s.localPort)
            s.connect(java.net.InetSocketAddress(host, port), SELF_PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * PERF 心跳：60s 一条进主环、每 [HEARTBEAT_KEY_EVERY] 条镜像进 key.log，随后 flush。
     * 7-26 排障的核心教训：正常流量不落盘 ⇒ 「故障起止时刻」在导出日志里不可定位，
     * 所有以静默段为锚的推论全部作废。心跳给每分钟一个确定的存活断面。
     * 纪律：localAllow 打**实际生效**的 lastAdmitKey，不打本轮算出的值。
     */
    private fun heartbeat(clients: Int, sig: com.mzstd.hxmyproxy.core.network.SignalInfo) {
        heartbeatN++
        val kv = arrayOf(
            "ports" to servers.mapNotNull { s -> s.boundPort.value?.let { "${s.protocol}:$it" } }
                .joinToString(",").ifEmpty { "none" },
            "conn" to registry.activeGlobal,
            "accept" to servers.sumOf { it.acceptCount },
            "clients" to clients,
            "localAllow" to lastAdmitKey.joinToString("|").ifEmpty { "<empty>" },
            "rssi" to sig.dbm,
            "link" to (LinkProbe.stats()?.let { "${it.p50Ms}/${it.p95Ms}" } ?: "-"),
            "probe" to lastSelfProbe,
        )
        if (heartbeatN % HEARTBEAT_KEY_EVERY == 0L) Ev.k(LogCat.PERF, "hb", *kv) else Ev.i(LogCat.PERF, "hb", *kv)
        FileLog.flush()
    }

    fun stop() {
        running = false
        stopServers()
        mdnsPublisher.unpublishAll()
        connectivityObserver.stop()
        underlyingNetworkProvider.pause()   // 撤销出口保活但保留监听：停止态出口卡仍显示在线状态
        // 取消本会话全部协程（收集器/ticker）：杜绝停止后 settings 收集器又把监听重启起来。
        sessionScope?.cancel()
        sessionScope = null
        engineScope = null
        totalUp.set(0)
        totalDown.set(0)
        lastRecordedEntryKey = ""
        lastInterfaceIps = emptySet()
        lastSeenClients = emptyList()
        _resetState.value = ManualResetPhase.IDLE
        // 会话边界必须清（曾漏清）：残留的 lastAdmitKey 会让新会话首次 refresh 在「接口扫描恰好为空」时
        // 跳过准入更新（保护分支判据正是 lastAdmitKey.isEmpty()），准入停在上个会话的旧值。
        lastAdmitKey = emptySet()
        lastScanKey = ""
        registry.reset()
        accounting.reset()
        LinkProbe.reset()   // 会话边界：上次会话的链路样本不带到这次
        heartbeatN = 0
        lastSelfProbe = "-"
        lastSelfProbeLogged = null   // 下个会话第一次自探必落一条基线（"-->ok/ok"）
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
        // stop() 内部会 join 等 worker 收尾完成——保证全部 resume 已发出后才走到下面的
        // shutdownNow，消除「resume 分发撞上已关闭 dispatcher」的竞态。
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
        underlyingNetworkProvider.start()   // 停止态也监听（幂等，不拉起蜂窝）：出口卡显示各网络在线状态
        val perm = localNetworkPermissionManager.isGranted()
        val sig = signalProvider.current()
        _state.update {
            it.copy(
                interfaces = interfaces,
                egressStatus = underlyingNetworkProvider.status(),
                localNetworkPermissionGranted = perm,
                // 未运行时也写入各协议启用态,否则诊断 enabled 停留默认 true → 关闭的协议被误报红叉(审查发现)。
                diagnostics = it.diagnostics.copy(
                    localNetworkPermissionGranted = perm,
                    httpEnabled = s.httpEnabled,
                    socksEnabled = s.socksEnabled,
                    pacEnabled = s.pacEnabled,
                    notificationPermissionGranted = permissionProbe.notificationsEnabled(),
                    batteryOptimizationIgnored = permissionProbe.batteryUnrestricted(),
                ),
                signalLevel = sig.level,
                signalDbm = sig.dbm,
            )
        }
    }

    private fun applyTunables(s: ProxySettings) {
        // 诊断日志总开关：关闭后 FileLog/Ev 一律不写盘（已有文件保留，可继续查看/导出）。
        com.mzstd.hxmyproxy.core.log.FileLog.enabled = s.logEnabled
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
        connector.backupDnsEnabled = s.backupDnsEnabled
        underlyingNetworkProvider.setEgressChoice(s.egressChoice)
        underlyingNetworkProvider.setDirectEgressChoice(s.directEgressChoice)
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
        // 回退规则:用户**选过**接口(selectedIds 非空)但一个都没匹配上(selected 空)→ 说明选的接口因换网/换热点
        // 消失了,回退到「全部可共享接口」,让新出现的热点自动成为入口 + 准入放行,无需重启共享(换 WiFi→热点这类
        // **换接口类型**场景旧选中 wlan0 对不上新接口 ap0)。若用户压根没选(selectedIds 空),尊重「没选=没入口」不回退。
        val effective = if (selected.isEmpty() && s.selectedInterfaceIds.isNotEmpty()) interfaces else selected
        val admitKey = effective.mapNotNull { it.address.hostAddress }.toSet()
        // 带接口名与类型：7-26 排障时 scan 里冒出过一个只有 IP 的 10.168.249.89，是什么接口至今无解
        //（当时 scanKey 只拼 IP）。名字+类型是判定「陌生接口从哪来」的最起码信息。
        val scanKey = interfaces.joinToString(",") {
            "${it.name}(${it.type}):${it.address.hostAddress ?: "?"}" + if (it.isSelected) "*" else ""
        }
        val prevAllow = lastAdmitKey
        // 【换网中断修复】scan 返回空(换网/换热点瞬间网卡短暂无接口)且曾有准入 → **保留上次 admit,不清空**，
        // 否则瞬态空集 fail-closed 会把正在连的老客户端(如 192.168.50.65)拒掉,等新接口稳定后正常更新即可。
        // 「没选=全拒」不受影响:那种情况 interfaces 非空(扫得到接口只是没选)→ 照常走下面更新为空。
        if (interfaces.isNotEmpty() || lastAdmitKey.isEmpty()) {
            // 【长连接卡顿/直连慢修复】evict 只在准入集**真的变化**时才跑。原来每次 refresh 都 evict,叠加每 3 秒
            // 回调风暴 → 每 3 秒清扫一遍在途连接,大文件长连接(如 arxiv PDF 下载)被反复打断 → 又慢又卡。
            val admitChanged = admitKey != lastAdmitKey
            accessController.update(effective.map { it.address }.toSet())
            lastAdmitKey = admitKey
            if (admitChanged) servers.forEach { it.evictNotAdmitted(accessController::admit) }
        }
        // **只在变化时落盘**：此前每次 refresh 无条件记一行，一次导出里 108 条一模一样的 refresh
        // 把关键事件淹没（教训 7）；且只记快照不记「旧->新」，导致 selIds 的 4/5 变化被读反（教训 5）。
        // 字段名 admit= 也改为 localAllow=：它是「允许连入的本机监听地址」，不是「允许的来源网段」——
        // SubnetAccessController.admit 只看 local、remote 形参从未被引用，旧名把排查方向带偏过（教训 6）。
        // 日志必须打**实际生效**的 lastAdmitKey，不打本轮算出的 admitKey——保护分支未进入时两者不同，
        // 打算出值会造出假的 <empty:fail-closed>（7-26 的 14:47:25 那条曾把根因判断带偏）。
        // kept=true 显式标出「本次扫描为空、保留上次准入」，与真清空在日志里可区分。
        if (scanKey != lastScanKey || lastAdmitKey != prevAllow) {
            Ev.k(
                LogCat.IFACE, "refresh",
                "scan" to scanKey,
                "selIds" to s.selectedInterfaceIds.size,
                "localAllow" to lastAdmitKey.joinToString("|").ifEmpty { "<empty:fail-closed>" },
                "prevAllow" to (if (lastAdmitKey != prevAllow) prevAllow.joinToString("|").ifEmpty { "<empty>" } else null),
                "kept" to (if (admitKey != lastAdmitKey) true else null),
                "srcCheck" to "none",
            )
        }
        lastScanKey = scanKey
        publishMdns(s)
        // WiFi 切换 / IP 变化（DHCP 续约）时主动重发 mDNS：端口不变故 publishMdns 幂等不重注册，
        // 但必须重注册才能在新 IP 上通告 A 记录（NsdManager 不自动跟随网络变化）。仅在已有 IP→新 IP 时触发。
        val currentIps = effective.mapNotNull { it.address.hostAddress }.toSet()
        val ipChanged = running && s.mdnsEnabled && currentIps.isNotEmpty() &&
            lastInterfaceIps.isNotEmpty() && currentIps != lastInterfaceIps
        // **先**更新 lastInterfaceIps 再 republish：republish 会改 mdnsPublisher.registeredName，触发
        // `registeredName.collect { refresh() }` 的另一次 refresh；若此时 lastInterfaceIps 仍是旧值，
        // 那次 refresh 会判定 IP「还在变」→ 再次 republish → registeredName 又变 → 无限 republish 风暴
        // （NsdManager 抖动 + CPU 打满 → 前台服务被系统杀 → 服务停止）。先更新即可让那次 refresh 不再 republish。
        // 切换瞬间网卡可能短暂无 IP（currentIps 空）：此时不更新（保留旧 IP），等新 IP 出现再比较触发，避免漏发。
        if (currentIps.isNotEmpty()) lastInterfaceIps = currentIps
        if (ipChanged) mdnsPublisher.republish()
        val entries = computeEntries(effective, s)
        // 走蜂窝上网且没有任何可共享入口(没开热点)→ 引导用户开个人热点。放 refresh 里算,随网络变化即时更新。
        val needsHotspotHint = entries.isEmpty() && connectivityObserver.uplinkIsCellular()
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
                admissionEmpty = effective.isEmpty(),
                needsHotspotHint = needsHotspotHint,
                egressStatus = underlyingNetworkProvider.status(),
                signalLevel = sig.level,
                signalDbm = sig.dbm,
                diagnostics = st.diagnostics.copy(
                    localNetworkPermissionGranted = perm,
                    httpPortUp = portUp(ProxyProtocol.HTTP),
                    socksPortUp = portUp(ProxyProtocol.SOCKS5),
                    pacPortUp = portUp(ProxyProtocol.PAC),
                    httpEnabled = s.httpEnabled,
                    socksEnabled = s.socksEnabled,
                    pacEnabled = s.pacEnabled,
                    notificationPermissionGranted = permissionProbe.notificationsEnabled(),
                    batteryOptimizationIgnored = permissionProbe.batteryUnrestricted(),
                    mdnsPublished = s.mdnsEnabled && mdnsPublisher.lastRegisteredName != null,
                ),
            )
        }
    }

    private fun portUp(p: ProxyProtocol): Boolean =
        servers.firstOrNull { it.protocol == p }?.boundPort?.value != null

    private fun computeEntries(selected: List<ShareInterface>, s: ProxySettings): List<ProxyEntry> {
        // hxmyproxy.local 不再作为入口便利名：NsdManager 任何版本都无法注册自定义 mDNS 主机名（无 setHostname），
        // 该名从未被通告 A 记录、客户端解析不到（实测 macOS 解析失败、用 IP 正常）。故 mdnsName 恒 null、入口只给
        // 真实 IP（符合 D1「IP 永为兜底」）。mDNS 仍注册 DNS-SD 服务（_http._tcp 等），进阶用户可用 Bonjour 服务发现；
        // 要让 hxmyproxy.local 真正可解析须自建 mDNS responder（jmDNS），留作后续可选便利层。
        val mdnsName: String? = null
        val list = ArrayList<ProxyEntry>()
        for (iface in selected) {
            val ip = iface.address.hostAddress ?: continue
            // 展示顺序 HTTP > SOCKS5 > PAC(HTTP 最通用、客户端配置最简单)。priority 与顺序一致(HTTP 最高),
            // 作为唯一优先级来源,避免与通知/主页硬编码的「HTTP 优先」相矛盾(旧值 SOCKS5 最高是反的)。
            if (s.httpEnabled) list.add(ProxyEntry(ip, s.httpPort, ProxyProtocol.HTTP, iface.id, mdnsName, priority = 30))
            if (s.socksEnabled) list.add(ProxyEntry(ip, s.socksPort, ProxyProtocol.SOCKS5, iface.id, mdnsName, priority = 20))
            if (s.pacEnabled) list.add(ProxyEntry(ip, s.pacPort, ProxyProtocol.PAC, iface.id, mdnsName, priority = 10))
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
        const val TOP_DOMAINS = 20
        const val ACCOUNTING_AGE_OUT_MS = 5 * 60 * 1000L
        /** 热点接口出现无网络回调,周期重扫捕捉的间隔(秒级足够快、又不至于耗电)。 */
        const val HOTSPOT_RESCAN_MS = 3000L
        /** 段① 链路探测间隔（以 1s ticker 计数）：10 秒一次，仅在有在线客户端时执行。 */
        const val LINK_PROBE_TICKS = 10L
        /** 配对自探间隔（1s ticker 计数）：127.0.0.1 与本机 LAN IP 各连一次本机监听端口。 */
        const val SELF_PROBE_TICKS = 30L
        /** PERF 心跳间隔（1s ticker 计数）。 */
        const val HEARTBEAT_TICKS = 60L
        /** 每几次心跳镜像一条进 key.log（60s×10=10 分钟一条，不挤占 256KB 关键环）。 */
        const val HEARTBEAT_KEY_EVERY = 10L
        /** 自探 TCP connect 超时。本机连本机走内核 loopback 路径，1.5s 足够宽裕。 */
        const val SELF_PROBE_TIMEOUT_MS = 1500
        /** 手动刷新段② 对每个目标客户端的最大探测次数。 */
        const val MANUAL_PROBE_ATTEMPTS = 3
    }
}

/** 「手动刷新服务」的状态机：IDLE → RUNNING → DONE_*（UI 提示后 ack 回 IDLE）。 */
enum class ManualResetPhase {
    IDLE, RUNNING,
    /** 重置完成，且至少一个最近客户端探测有响应。 */
    DONE_OK,
    /** 重置完成，但本会话还没见过任何客户端（无从探测）。 */
    DONE_NO_CLIENT,
    /** 重置完成，全部最近客户端探测无响应——陈旧状态大概率在系统 WiFi 层，引导用户开面板。 */
    DONE_LINK_DEAD,
}
