package com.mzstd.hxmyproxy.core.proxy

import android.util.Log
import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.security.AccessController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "hxmyproxy"

interface ProxyServer {
    val protocol: ProxyProtocol

    /** 实际绑定端口；null 表示未运行。 */
    val boundPort: StateFlow<Int?>

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
) : ProxyServer {

    private val _boundPort = MutableStateFlow<Int?>(null)
    override val boundPort: StateFlow<Int?> = _boundPort.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptJob: Job? = null

    override fun start(scope: CoroutineScope, port: Int) {
        acceptJob = scope.launch(Dispatchers.IO) {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), ProxyTuning.ACCEPT_BACKLOG)
            } catch (e: Throwable) {
                server.closeQuietly()
                throw ProxyException(ProxyError.PortInUse)
            }
            serverSocket = server
            _boundPort.value = server.localPort
            try {
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (e: Throwable) {
                        if (isActive) continue else break
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
                    launch(ioDispatcher) {
                        try {
                            handle(client)
                        } catch (e: Throwable) {
                            Log.w(TAG, "$protocol error ${remote.hostAddress}: ${e.message}")
                        } finally {
                            client.closeQuietly()
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
        _boundPort.value = null
    }

    /**
     * 处理单个已准入连接（可阻塞，运行在 [ioDispatcher]）：完成握手、连上游、relay。
     * socket 最终由基类 finally 关闭（这里也可在 relay 内提前关闭）。
     */
    protected abstract suspend fun handle(client: Socket)
}
