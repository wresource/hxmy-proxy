package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 双向字节泵：在 client <-> upstream 间转发，直到任一方向 EOF/空闲/出错。
 *
 * - 某方向读到 EOF → `shutdownOutput()` 对端写端（半关闭），另一方向继续直到自身结束。
 * - 任一方向空闲超过 idle 超时 → 关闭两端，强制拆除（避免半开连接长期占用 FD）。
 * - 两个方向都结束后保证关闭两端（FD 不泄漏）。
 */
class RelayEngine(
    /** 流量计量回调（字节增量），(up, down)；由引擎层注入以统计实时速率。 */
    private val onTraffic: (up: Long, down: Long) -> Unit = { _, _ -> },
) {

    /**
     * 阻塞式双向转发；在 IO dispatcher 的协程内调用。
     * @param idleMillis 空闲超时（毫秒），<=0 表示不设空闲超时。
     */
    suspend fun relay(
        client: Socket,
        upstream: Socket,
        bufferBytes: Int,
        idleMillis: Int,
        relayDispatcher: CoroutineDispatcher,
        onTraffic: (Long, Long) -> Unit = this.onTraffic,
        host: String? = null,
        trace: RequestTrace? = null,
    ) {
        val evidence = RelayCloseEvidence()
        val up = AtomicLong(); val down = AtomicLong()
        var clientStreams: DuplexSocketStreams? = null
        var upstreamStreams: DuplexSocketStreams? = null
        val closeAll: () -> Unit = {
            clientStreams?.close(); upstreamStreams?.close()
            runCatching { client.close() }.onFailure { evidence.cleanupFailure("close", "client", it) }
            runCatching { upstream.close() }.onFailure { evidence.cleanupFailure("close", "upstream", it) }
        }
        try {
            coroutineScope {
                runCatching { trace?.relayStarted(client, upstream) }
                val to = if (idleMillis > 0) idleMillis else 0
                client.soTimeout = to
                upstream.soTimeout = to
                val cStreams = DuplexSocketStreams(client, to).also { clientStreams = it }
                val uStreams = DuplexSocketStreams(upstream, to).also { upstreamStreams = it }
                // stop() cancels the parent before externally closing accepted sockets. A pump
                // may observe that close before this scope's join-finally gets CPU time.
                val relayJob = coroutineContext[Job]
                val cancelled = { relayJob?.isCancelled == true }
                // 双向各一协程，都派发到受限的 relayDispatcher（搬字节并行度由它控制）。
                // 与 handle 的建连派发器分离：新连接的握手/建连不会被搬字节占满线程而饿死（队头阻塞）。
                val upJob = launch(relayDispatcher) {
                    pump(cStreams, uStreams, upstream, "client", "upstream", bufferBytes, evidence, cancelled, closeAll) { up.addAndGet(it); onTraffic(it, 0) }
                }
                val downJob = launch(relayDispatcher) {
                    pump(uStreams, cStreams, client, "upstream", "client", bufferBytes, evidence, cancelled, closeAll) { down.addAndGet(it); onTraffic(0, it) }
                }
                try {
                    upJob.join()
                    downJob.join()
                    evidence.terminate("eof") // both pumps finished; earlier errors keep precedence
                } finally {
                    // Close before coroutineScope joins blocking children on cancellation.
                    if (!isActive) evidence.terminate("cancelled")
                    closeAll()
                }
            }
        } catch (e: CancellationException) {
            evidence.terminate("cancelled")
            throw e
        } catch (e: Throwable) {
            evidence.terminate("relay-error", "setup", null, e)
            throw e
        } finally {
            closeAll()
            runCatching { trace?.tunnelClosed(host, evidence, up.get(), down.get()) }
        }
    }

    private fun pump(
        from: DuplexSocketStreams,
        destination: DuplexSocketStreams,
        to: Socket,
        fromSide: String,
        toSide: String,
        bufferBytes: Int,
        evidence: RelayCloseEvidence,
        cancelled: () -> Boolean,
        closeAll: () -> Unit,
        onBytes: (Long) -> Unit,
    ) {
        val buf = ByteArray(bufferBytes)
        var operation = "stream-open"
        var side = fromSide
        try {
            val input = from.input
            side = toSide
            val output = destination.output
            while (true) {
                operation = "read"; side = fromSide
                val n = try {
                    input.read(buf)
                } catch (e: SocketTimeoutException) {
                    if (cancelled()) evidence.terminate("cancelled")
                    else evidence.terminate("idle", "read", fromSide)
                    closeAll(); break       // 空闲超时 → 拆除整条连接
                }
                if (n < 0) {                 // EOF：半关闭对端写端
                    evidence.observedEof(fromSide)
                    runCatching { to.shutdownOutput() }.onFailure { evidence.cleanupFailure("shutdownOutput", toSide, it) }
                    break
                }
                operation = "write"; side = toSide
                output.write(buf, 0, n)
                output.flush()
                operation = "accounting"
                onBytes(n.toLong())
            }
        } catch (e: Throwable) {
            if (operation != "accounting" && cancelled()) evidence.terminate("cancelled")
            else evidence.terminate(if (operation == "accounting") "relay-error" else "io-error", operation,
                if (operation == "accounting") null else side, e)
            closeAll()                       // 对端被关/重置等 → 结束本方向
        }
    }
}
