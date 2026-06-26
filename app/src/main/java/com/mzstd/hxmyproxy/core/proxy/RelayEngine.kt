package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    ) = coroutineScope {
        val to = if (idleMillis > 0) idleMillis else 0
        client.soTimeout = to
        upstream.soTimeout = to
        val closeAll = {
            client.closeQuietly()
            upstream.closeQuietly()
        }
        // 双向各一协程，都派发到受限的 relayDispatcher（搬字节并行度由它控制）。
        // 与 handle 的建连派发器分离：新连接的握手/建连不会被搬字节占满线程而饿死（队头阻塞）。
        val upJob = launch(relayDispatcher) {
            pump(client, upstream, bufferBytes, closeAll) { onTraffic(it, 0) }
        }
        val downJob = launch(relayDispatcher) {
            pump(upstream, client, bufferBytes, closeAll) { onTraffic(0, it) }
        }
        upJob.join()
        downJob.join()
        closeAll()
    }

    private fun pump(
        from: Socket,
        to: Socket,
        bufferBytes: Int,
        closeAll: () -> Unit,
        onBytes: (Long) -> Unit,
    ) {
        val buf = ByteArray(bufferBytes)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: SocketTimeoutException) {
                    closeAll(); break       // 空闲超时 → 拆除整条连接
                }
                if (n < 0) {                 // EOF：半关闭对端写端
                    runCatching { to.shutdownOutput() }
                    break
                }
                output.write(buf, 0, n)
                output.flush()
                onBytes(n.toLong())
            }
        } catch (e: Throwable) {
            closeAll()                       // 对端被关/重置等 → 结束本方向
        }
    }
}
