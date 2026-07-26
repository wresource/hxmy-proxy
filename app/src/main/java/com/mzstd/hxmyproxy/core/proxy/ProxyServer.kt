package com.mzstd.hxmyproxy.core.proxy

import android.util.Log
import com.mzstd.hxmyproxy.core.log.Ev
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.log.LogCat
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.security.AccessController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "hxmyproxy"

/** bind 端口占用时的有限重试:stop→start 快速重启时旧 socket 可能尚未释放(SO_REUSEADDR 救不了仍在 LISTEN 的端口)。 */
private const val BIND_RETRY_ATTEMPTS = 3
private const val BIND_RETRY_DELAY_MS = 150L

/** accept 抛系统错误（如 EMFILE 文件描述符耗尽）时的退避：避免错误持续期间 100% CPU 紧凑自旋。 */
private const val ACCEPT_ERROR_BACKOFF_MS = 100L

/** 拒连日志节流窗口：同一「原因+来源 IP」每这么久最多落一条。 */
private const val REJECT_LOG_INTERVAL_MS = 10_000L

/**
 * accept 成功落盘的节流窗口（按来源 IP）。此前 accept 成功只有 logcat（release 还被 R8 剥掉），
 * 导出日志里「SYN 没到手机」与「accept 了且一切正常」**完全同形**——7-26 排障里最大的仪表盲区。
 */
private const val ACCEPT_LOG_INTERVAL_MS = 30_000L


/**
 * 对端正常关闭类异常(客户端断开 / keep-alive 空闲取消 / 网站关连接):
 * 连接重置、管道断开、socket 关闭、协程取消。这些是 HTTP 代理的常态,不是 App 故障,
 * 因此**不写入错误日志**(否则会用上百条"正常断开"淹没真正的问题,如 bind 失败)。
 */
internal fun isPeerClosed(e: Throwable): Boolean {
    if (e is CancellationException) return true
    if (e is java.io.IOException) {
        val msg = (e.message ?: "").lowercase()
        return "reset" in msg || "broken pipe" in msg || "epipe" in msg ||
            "socket closed" in msg || "connection abort" in msg || "stream closed" in msg
    }
    return false
}

interface ProxyServer {
    val protocol: ProxyProtocol

    /** 实际绑定端口；null 表示未运行。 */
    val boundPort: StateFlow<Int?>

    /** bind 失败原因（端口被占用/无效）；null 表示无错误。运行时改端口可即时看到失败而非崩溃。 */
    val bindError: StateFlow<ProxyError?>

    /** 本次监听会话累计 accept 成功数（含自探连接）；供 PERF 心跳读取，定位「入站到底进没进来」。 */
    val acceptCount: Long get() = 0

    /** 在 [scope] 内绑定并启动 accept 循环。 */
    fun start(scope: CoroutineScope, port: Int)

    fun stop()

    /** 主动断开不再满足准入的在途连接（准入集合收缩时调用；监听与仍合法的连接不受影响）。 */
    fun evictNotAdmitted(admit: (local: InetAddress, remote: InetAddress) -> Boolean) {}
}

/**
 * 通用 TCP 接入：bind `0.0.0.0:port`（SO_REUSEADDR）→ accept 循环 → 准入([AccessController])
 * + 连接计数([ConnectionRegistry]) → 每连接派发到 [ioDispatcher]。子类实现 [handle]。
 */
abstract class TcpProxyServerBase(
    override val protocol: ProxyProtocol,
    private val ioDispatcher: CoroutineDispatcher,
    private val accessController: AccessController,
    private val registry: ConnectionRegistry,
    /** 流量记账（按客户端 IP / 目标域名）；为 null 时不统计（如 PAC 服务）。 */
    private val accounting: TrafficAccounting? = null,
) : ProxyServer {

    private val _boundPort = MutableStateFlow<Int?>(null)
    override val boundPort: StateFlow<Int?> = _boundPort.asStateFlow()

    private val _bindError = MutableStateFlow<ProxyError?>(null)
    override val bindError: StateFlow<ProxyError?> = _bindError.asStateFlow()

    private val acceptTotal = java.util.concurrent.atomic.AtomicLong(0)
    override val acceptCount: Long get() = acceptTotal.get()

    @Volatile private var serverChannel: ServerSocketChannel? = null
    @Volatile private var acceptJob: Job? = null

    /**
     * 在途已准入的 client 连接（NIO [SocketChannel]，握手期 blocking、relay 期可切非阻塞）。stop() 时主动
     * 关闭——使阻塞中的 relay read/write 立刻抛错、协程退出、线程归还、FD 立即释放，而非残留到 idle 超时。
     */
    private val inFlight = ConcurrentHashMap.newKeySet<SocketChannel>()

    override fun start(scope: CoroutineScope, port: Int) {
        _bindError.value = null
        acceptJob = scope.launch(Dispatchers.IO) {
            // bind：对端口占用做有限重试——stop→start 快速重启时旧监听 socket 可能尚未释放端口。
            var bound: ServerSocketChannel? = null
            var lastError: Throwable? = null
            for (attempt in 0 until BIND_RETRY_ATTEMPTS) {
                if (!isActive) return@launch
                val s = ServerSocketChannel.open()
                try {
                    s.configureBlocking(true)
                    s.setOption(StandardSocketOptions.SO_REUSEADDR, true)
                    s.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), ProxyTuning.ACCEPT_BACKLOG)
                    bound = s
                    break
                } catch (e: Throwable) {
                    s.closeQuietly()
                    lastError = e
                    if (attempt < BIND_RETRY_ATTEMPTS - 1) delay(BIND_RETRY_DELAY_MS)
                }
            }
            val server = bound ?: run {
                // bind 仍失败（端口被占用/无效）：暴露为状态而非抛进 scope（否则冒泡到全局 handler 崩溃 App），
                // UI 据此提示用户换端口。
                _bindError.value = ProxyError.PortInUse
                _boundPort.value = null
                Log.w(TAG, "$protocol bind :$port failed: ${lastError?.message}")
                FileLog.w(TAG, "$protocol bind :$port failed", lastError)
                return@launch
            }
            serverChannel = server
            _boundPort.value = (server.localAddress as InetSocketAddress).port
            _bindError.value = null
            // 以前只有 bind **失败**落盘，成功不落——导致「监听到底起没起来」在导出日志里无法判定。
            Ev.k(LogCat.SVC, "listen.up", "proto" to protocol, "port" to _boundPort.value, "bind" to "0.0.0.0")
            try {
                while (isActive) {
                    val client = try {
                        server.accept()                       // 阻塞 accept，返回阻塞模式 SocketChannel
                    } catch (e: Throwable) {
                        if (!isActive) break
                        // stop() 先关 serverChannel 再 cancel 协程——关闭唤醒的 accept 会在 cancel
                        // 送达前抛 ClosedChannelException（AsynchronousCloseException 是其子类），
                        // 这是正常收尾，直接退出，不能记成 accept 异常（否则每次停止都误报一条）。
                        if (e is java.nio.channels.ClosedChannelException) break
                        // 此前非 EMFILE 异常是**裸 continue：零日志、零退避**——若错误持续，这里就是
                        // 100% CPU 自旋且导出日志全程无痕（7-26 RCA 的 accept 卡死候选正卡在这个盲区，
                        // 无法证实也无法排除）。统一节流落盘（进 key.log）+ 统一退避。
                        val msg = (e.message ?: "").lowercase()
                        val emfile = "too many open files" in msg || "emfile" in msg
                        Log.w(TAG, "$protocol accept error，退避重试: ${e.message}")
                        Ev.throttled(
                            LogCat.CONN, "accept.error", "acceptErr:$protocol", REJECT_LOG_INTERVAL_MS,
                            key = true,
                            kv = arrayOf("proto" to protocol, "err" to e.toString(), "emfile" to (if (emfile) true else null)),
                        )
                        delay(ACCEPT_ERROR_BACKOFF_MS)
                        continue
                    }
                    client.configureBlocking(true)            // 握手期阻塞（子类用 channel.socket() 流）
                    val sock = client.socket()
                    val remote = (sock.remoteSocketAddress as? InetSocketAddress)?.address
                    val remotePort = (sock.remoteSocketAddress as? InetSocketAddress)?.port ?: 0
                    val local = (sock.localSocketAddress as? InetSocketAddress)?.address
                    // 内核 accept 计数——放在准入**之前**：语义是「accept 循环还活着且有连接进来」。
                    // 自探每 30s 必到 2 条（含被准入拒的 127 腿），心跳里该数停涨=accept 层卡死的直接证据。
                    acceptTotal.incrementAndGet()
                    // 自探识别用**源端口标记**而非「remote 是本机地址」：后者与真实的本机自用代理
                    //（设备内 nc 验证法 / WiFi 代理指向自身 LAN IP）完全同形，按地址过滤会把自用流量的
                    // 记账与拦截计数一并抹掉（review 证实的回归）。见 SelfProbeMarks。
                    val probeConn = remote != null && (remote.isLoopbackAddress || remote == local) &&
                        SelfProbeMarks.consume(remotePort)
                    if (remote == null || local == null || !accessController.admit(local, remote)) {
                        // 准入拒绝以前**完全静默**（只 closeQuietly），导出日志里查不到任何痕迹，
                        // 排障时无法区分「没连上来」与「连上来被拒」。进 key.log，永不被高频日志冲掉。
                        // 自探的 127 腿被拒属设计内（loopback 不放行），不落行——否则每 30s 一条灌 key 环。
                        if (!probeConn) {
                            Ev.throttled(
                                LogCat.ADMIT, "reject.notAdmitted", "admit:${remote?.hostAddress}", REJECT_LOG_INTERVAL_MS,
                                key = true,
                                kv = arrayOf("proto" to protocol, "local" to local?.hostAddress, "remote" to remote?.hostAddress),
                            )
                        }
                        client.closeQuietly(); continue
                    }
                    if (!registry.tryAcquire(remote)) {
                        // 全链路**唯一按来源 IP 区分**的闸门——「只拒某一台客户端」的现象只可能出自这里。
                        // 以前只有 Log.i（仅 logcat、导出看不到），是排障黑箱。
                        Ev.throttled(
                            LogCat.CONN, "reject.limit", "limit:${remote.hostAddress}", REJECT_LOG_INTERVAL_MS,
                            key = true,
                            kv = arrayOf(
                                "proto" to protocol,
                                "remote" to remote.hostAddress,
                                "perClient" to "${registry.activeFor(remote)}/${registry.maxPerClient}",
                                "global" to "${registry.activeGlobal}/${registry.maxGlobal}",
                            ),
                        )
                        client.closeQuietly(); continue
                    }
                    runCatching { sock.tcpNoDelay = true }
                    Log.i(TAG, "$protocol accept ${remote.hostAddress} (active=${registry.activeGlobal})")
                    // 仅探针本身不落 accept 行、不进流量账（避免 30s 一次的自探把日志与 UI 客户端列表
                    // 噪音化：clients 里出现本机 IP、LinkProbe 反过来探自己）。真实的本机自用连接照常记。
                    if (!probeConn) {
                        Ev.throttled(
                            LogCat.CONN, "accept", "accept:${remote.hostAddress}", ACCEPT_LOG_INTERVAL_MS,
                            level = "I",
                            kv = arrayOf("proto" to protocol, "remote" to remote.hostAddress, "active" to registry.activeGlobal),
                        )
                    }
                    val tracker = if (probeConn) null else accounting?.openConnection(remote, protocol)
                    inFlight.add(client)
                    launch(ioDispatcher) {
                        try {
                            handle(client, tracker)
                        } catch (e: Throwable) {
                            // 客户端正常断开（连接重置/管道断开/keep-alive 取消）是常态 → 仅 debug，不进错误日志。
                            if (isPeerClosed(e)) {
                                Log.d(TAG, "$protocol peer-closed ${remote.hostAddress}: ${e.message}")
                            } else {
                                Log.w(TAG, "$protocol error ${remote.hostAddress}: ${e.message}")
                                FileLog.w(TAG, "$protocol error ${remote.hostAddress}", e)
                            }
                        } finally {
                            inFlight.remove(client)
                            client.closeQuietly()
                            tracker?.close()
                            registry.release(remote)
                            Log.i(TAG, "$protocol close ${remote.hostAddress} (active=${registry.activeGlobal})")
                        }
                    }
                }
            } finally {
                server.closeQuietly()
                _boundPort.value = null
            }
        }
    }

    override fun stop() {
        // 监听下线也要留痕：只有 listen.up 而无 down 时，「代理什么时候不再监听的」在导出日志里无法判定。
        Ev.k(LogCat.SVC, "listen.down", "proto" to protocol, "port" to _boundPort.value, "inFlight" to inFlight.size)
        serverChannel?.closeQuietly()
        acceptJob?.cancel()
        acceptJob = null
        serverChannel = null
        // 主动关闭在途连接：阻塞中的 relay read/write 立即抛错 → 协程退出、线程归还有界池、FD 释放。
        // 只需关 client 端：一端断开后另一方向 pump 随之结束，handle 的 finally 会关上游 socket。
        inFlight.toList().forEach { it.closeQuietly() }
        inFlight.clear()
        _boundPort.value = null
        _bindError.value = null
    }

    override fun evictNotAdmitted(admit: (InetAddress, InetAddress) -> Boolean) {
        // 判定口径与 accept 准入完全一致（local 地址）。已关闭的 channel 跳过（等 handle/reactor sweep 清理，
        // 反复 close 只会刷日志）。NIO 隧道裸 close 不产生 selector 事件，reactor sweep 的
        // externallyClosed 检测会在 ≤1 周期内拆干净并 resume 协程。
        inFlight.toList().forEach { ch ->
            if (!ch.isOpen) return@forEach
            val sock = runCatching { ch.socket() }.getOrNull()
            val local = (sock?.localSocketAddress as? InetSocketAddress)?.address
            val remote = (sock?.remoteSocketAddress as? InetSocketAddress)?.address
            if (local == null || remote == null || !admit(local, remote)) {
                Log.i(TAG, "$protocol evict ${remote?.hostAddress ?: "?"} (准入集收缩)")
                ch.closeQuietly()
            }
        }
    }

    /**
     * 处理单个已准入连接（握手期阻塞，运行在 [ioDispatcher]）：完成握手、连上游、relay。
     * [client] 为阻塞模式 [SocketChannel]（子类握手用 `client.socket()` 流；进入非阻塞 relay 前自行切非阻塞）。
     * [tracker] 为该连接的流量记账句柄（可空）；channel 最终由基类 finally 关闭。
     */
    protected abstract suspend fun handle(client: SocketChannel, tracker: TrafficAccounting.ConnTracker?)
}
