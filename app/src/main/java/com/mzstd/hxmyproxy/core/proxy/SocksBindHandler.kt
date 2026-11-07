package com.mzstd.hxmyproxy.core.proxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.rules.RuleAction
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleInput
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

/** RFC1928 two-reply BIND. Bounded incoming secondary connections, not public NAT mapping. */
internal class SocksBindHandler(
    private val connector: OutboundConnector,
    private val factory: TcpBindListenerFactory,
    private val session: ConnectUdpSession,
    private val rules: RuleEngine?,
    private val relay: RelayEngine,
    private val relayDispatcher: CoroutineDispatcher,
    private val limits: () -> ConnectionLimits,
    private val waitMillis: Long = 30_000,
) {
    private val pending = AtomicInteger()
    private fun acquireSlot(): Boolean {
        val max = minOf(8,limits().relayParallelism)
        while (true) { val n = pending.get(); if (n >= max) return false; if (pending.compareAndSet(n,n+1)) return true }
    }
    private fun reply(output: OutputStream, code: Int, address: InetSocketAddress = InetSocketAddress("0.0.0.0",0)) {
        output.write(SocksUdpCodec.boundReply(address).also { it[1] = code.toByte() }); output.flush()
    }

    suspend fun handle(client: SocketChannel, output: OutputStream, rawHost: String?, port: Int,
                       epoch: Long?, tracker: TrafficAccounting.ConnTracker?) {
        val parsed = rawHost?.let { RuleInput.parse(it) }
        if (rawHost == null || parsed == null || parsed.fromUrl || parsed.domainSuffix || parsed.host.contains('/') ||
            (parsed.isIp && '-' in parsed.host) || rawHost.any { it.isWhitespace() || it in "/?#@%\\=*" }) {
            reply(output,2); return
        }
        val host = parsed.host
        val trace = RequestTrace.open("SOCKS5",client.socket().port)
        return requestBoundary(trace, host, output, onFailure = { writable, error ->
            if (writable != null) reply(writable, (error as? ProxyException)?.error?.socksReply ?: 1)
        }) { output ->
            val decision = rules?.decideDetailed(host, client.socket().inetAddress)
            val action = decision?.action ?: RuleAction.PROXY
            trace.rule(host,action,decision?.src); tracker?.bindHost(host,action == RuleAction.DIRECT)
            if (action == RuleAction.REJECT) { tracker?.recordBlocked(host); reply(output,2); return }
            if (!acquireSlot()) { trace.failed(host,"bind-capacity",ProxyError.TooManyConnections); reply(output,1); return }
            try {
                val owner = currentCoroutineContext()[Job]
                val lease = session.acquire(epoch) { reason -> owner?.cancel(CancellationException("BIND $reason")); client.closeQuietly() }
                if (lease == null) { reply(output,1); return }
                lease.use {
                    val prepared = try {
                        acquireUpstream(ProxyTuning.CONNECT_PHASE_TIMEOUT_MS) {
                            connector.prepareBind(host,action == RuleAction.DIRECT,factory,tracker?.let { it::bindEgress },trace)
                        } ?: throw ProxyException(ProxyError.RemoteTimeout,"bind-setup-timeout")
                    } catch (failure: ProxyException) {
                        currentCoroutineContext().ensureActive()
                        if (!lease.isCurrent) throw CancellationException("BIND ${lease.reason ?: "route-changed"}")
                        trace.failed(host,failure.stage ?: "bind-setup",failure.error); reply(output,failure.error.socksReply); return
                    }
                    prepared.use {
                        fun sameRule() = (rules?.decideDetailed(host, client.socket().inetAddress)?.action ?: RuleAction.PROXY) == action
                        if (!lease.isCurrent || !sameRule()) { reply(output,2); return }
                        reply(output,0,prepared.listener.bound)
                        trace.bindReady(host,prepared.listener.bound,prepared.listener.networkHandle)
                        client.configureBlocking(false)
                        val initial = ByteBuffer.allocate(limits().relayBufferBytes.coerceIn(1024,65536))
                        var clientEof = false
                        var peer: Socket? = null
                        var rejected = 0
                        val deadline = System.nanoTime()+waitMillis*1_000_000L
                        try {
                            while (peer == null && System.nanoTime() < deadline) {
                                currentCoroutineContext().ensureActive()
                                if (!lease.isCurrent || !sameRule()) break
                                if (!clientEof && initial.hasRemaining()) clientEof = client.read(initial) < 0
                                val candidate = prepared.listener.acceptOrNull()
                                if (candidate != null) {
                                    if (candidate.inetAddress in prepared.peers && (port == 0 || candidate.port == port)) peer = candidate
                                    else { candidate.close(); if (++rejected >= 32) break }
                                }
                                if (peer == null) delay(20)
                            }
                            currentCoroutineContext().ensureActive()
                            client.configureBlocking(true)
                            val accepted = peer
                            if (!lease.isCurrent || !sameRule()) { reply(output,2); trace.failed(host,"bind-policy-changed",ProxyError.AccessDenied); return }
                            if (accepted == null) { reply(output,6); trace.failed(host,"bind-accept-timeout",ProxyError.RemoteTimeout); return }
                            prepared.listener.close()
                            reply(output,0,accepted.remoteSocketAddress as InetSocketAddress)
                            trace.connected(host,accepted.inetAddress.hostAddress,accepted.port,prepared.listener.networkHandle)
                            coroutineScope {
                                val relayOwner = currentCoroutineContext()[Job]
                                val watcher = launch(Dispatchers.Default) {
                                    while (isActive) {
                                        delay(50)
                                        if (!lease.isCurrent || !sameRule()) {
                                            relayOwner?.cancel(CancellationException("BIND policy changed")); client.closeQuietly(); accepted.closeQuietly(); break
                                        }
                                    }
                                }
                                try {
                                    val config = limits()
                                    relay.relay(client.socket(),accepted,config.relayBufferBytes,config.idleTimeoutSeconds*1000,
                                        relayDispatcher,tracker?.let { it::add } ?: { _, _ -> },host,trace,
                                        initial.array().copyOf(initial.position()))
                                } finally { watcher.cancel() }
                            }
                        } catch (failure: IOException) {
                            currentCoroutineContext().ensureActive()
                            trace.failed(host,"bind-io",failure.javaClass.simpleName)
                        } finally { peer?.closeQuietly() }
                    }
                }
            } finally { pending.decrementAndGet() }
        }
    }
}
