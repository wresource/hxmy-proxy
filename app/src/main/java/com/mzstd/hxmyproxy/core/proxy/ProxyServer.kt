package com.mzstd.hxmyproxy.core.proxy

import android.util.Log
import com.mzstd.hxmyproxy.core.log.FileLog
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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "hxmyproxy"

/** bind 端口占用时的有限重试:stop→start 快速重启时旧 socket 可能尚未释放(SO_REUSEADDR 救不了仍在 LISTEN 的端口)。 */
private const val BIND_RETRY_ATTEMPTS = 3
private const val BIND_RETRY_DELAY_MS = 150L

/** accept 抛系统错误（如 EMFILE 文件描述符耗尽）时的退避：避免错误持续期间 100% CPU 紧凑自旋。 */
private const val ACCEPT_ERROR_BACKOFF_MS = 100L

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

    /** 在 [scope] 内绑定并启动 accept 循环。 */
    fun start(scope: CoroutineScope, port: Int)

    fun stop()
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

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null

    /**
     * 在途已准入的 client socket。stop() 时主动关闭——使阻塞中的 relay read/write 立刻抛错、
     * 协程退出、线程归还有界池、FD 立即释放，而非残留到 idle 超时（最长 10 分钟）才回收。
     */
    private val inFlight = ConcurrentHashMap.newKeySet<Socket>()

    override fun start(scope: CoroutineScope, port: Int) {
        _bindError.value = null
        acceptJob = scope.launch(Dispatchers.IO) {
            // bind：对端口占用做有限重试——stop→start 快速重启时旧 ServerSocket 可能尚未释放端口。
            var bound: ServerSocket? = null
            var lastError: Throwable? = null
            for (attempt in 0 until BIND_RETRY_ATTEMPTS) {
                if (!isActive) return@launch
                val s = ServerSocket()
                try {
                    s.reuseAddress = true
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
            serverSocket = server
            _boundPort.value = server.localPort
            _bindError.value = null
            try {
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (e: Throwable) {
                        if (!isActive) break
                        // FD 耗尽（EMFILE: too many open files）时 accept 会持续立即抛错：退避避免 100% CPU
                        // 紧凑自旋，并记一条（仅此类系统错误；客户端正常断开不会进到这里）。
                        val msg = (e.message ?: "").lowercase()
                        if ("too many open files" in msg || "emfile" in msg) {
                            Log.w(TAG, "$protocol accept FD 耗尽，退避重试: ${e.message}")
                            FileLog.w(TAG, "$protocol accept too-many-open-files", e)
                            delay(ACCEPT_ERROR_BACKOFF_MS)
                        }
                        continue
                    }
                    val remote = (client.remoteSocketAddress as? InetSocketAddress)?.address
                    val local = (client.localSocketAddress as? InetSocketAddress)?.address
                    if (remote == null || local == null || !accessController.admit(local, remote)) {
                        client.closeQuietly(); continue
                    }
                    if (!registry.tryAcquire(remote)) {
                        Log.i(TAG, "$protocol reject ${remote.hostAddress} (limit; active=${registry.activeGlobal})")
                        client.closeQuietly(); continue
                    }
                    runCatching { client.tcpNoDelay = true }
                    Log.i(TAG, "$protocol accept ${remote.hostAddress} (active=${registry.activeGlobal})")
                    val tracker = accounting?.openConnection(remote)
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
        serverSocket?.closeQuietly()
        acceptJob?.cancel()
        acceptJob = null
        serverSocket = null
        // 主动关闭在途连接：阻塞中的 relay read/write 立即抛错 → 协程退出、线程归还有界池、FD 释放。
        // 只需关 client 端：一端断开后另一方向 pump 随之结束，handle 的 finally 会关上游 socket。
        inFlight.toList().forEach { it.closeQuietly() }
        inFlight.clear()
        _boundPort.value = null
        _bindError.value = null
    }

    /**
     * 处理单个已准入连接（可阻塞，运行在 [ioDispatcher]）：完成握手、连上游、relay。
     * [tracker] 为该连接的流量记账句柄（可空）；socket 最终由基类 finally 关闭。
     */
    protected abstract suspend fun handle(client: Socket, tracker: TrafficAccounting.ConnTracker?)
}
