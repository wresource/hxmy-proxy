package com.mzstd.hxmyproxy.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 测到目标 `host:port` 的 TCP 连接耗时（毫秒）。超时（>[timeoutMs]）或失败返回 null（UI 显示“超时”）。
 *
 * 用**不绑定 Network 的普通 Socket** → 跟随系统默认网络（含系统 VPN）：这正是代理对外分享所走的
 * 出口，故此延迟即“分享出去的质量”。全程在 IO 线程，绝不阻塞主线程。
 */
object LatencyProbe {
    const val TIMEOUT_MS = 1000

    suspend fun measureMillis(host: String, port: Int = 443, timeoutMs: Int = TIMEOUT_MS): Long? =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                (System.nanoTime() - start) / 1_000_000L
            } catch (e: Throwable) {
                null
            } finally {
                try { socket.close() } catch (_: Throwable) {}
            }
        }
}
