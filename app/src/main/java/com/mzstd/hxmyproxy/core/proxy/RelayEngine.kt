package com.mzstd.hxmyproxy.core.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class RelayEngine {

    /**
     * 阻塞式双向转发；在 IO dispatcher 的协程内调用。
     * @param idleMillis 空闲超时（毫秒），<=0 表示不设空闲超时。
     * @param onBytes    上行/下行字节增量回调（用于计量），(up, down)。
     */
    suspend fun relay(
        client: Socket,
        upstream: Socket,
        bufferBytes: Int,
        idleMillis: Int,
        onBytes: (up: Long, down: Long) -> Unit = { _, _ -> },
    ) = coroutineScope {
        val to = if (idleMillis > 0) idleMillis else 0
        client.soTimeout = to
        upstream.soTimeout = to
        val closeAll = {
            client.closeQuietly()
            upstream.closeQuietly()
        }
        // 上行：client -> upstream
        val upJob = launch(Dispatchers.IO) {
            pump(client, upstream, bufferBytes, closeAll) { onBytes(it, 0) }
        }
        // 下行：upstream -> client（在当前协程跑）
        pump(upstream, client, bufferBytes, closeAll) { onBytes(0, it) }
        upJob.join()
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
