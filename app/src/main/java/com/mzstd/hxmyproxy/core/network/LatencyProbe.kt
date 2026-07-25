package com.mzstd.hxmyproxy.core.network

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 测到目标 `host:port` 的 TCP 连接耗时（毫秒）。超时（>[timeoutMs]）或失败返回 null（UI 显示“超时”）。
 *
 * **两处修正**（此前测出的数字并不诚实）：
 * 1. **DNS 不计入延迟**：原实现把 `InetSocketAddress(host, port)`（内部做阻塞 DNS 解析）放在计时开始之后，
 *    解析耗时被算进了“延迟”。现在先解析、再开表，量的才是纯 TCP 建连往返。
 * 2. **绑定实际出口网络**：原实现用不绑 Network 的裸 Socket、跟随系统默认网络，但
 *    [com.mzstd.hxmyproxy.core.proxy.OutboundConnector] 是 **per-socket 绑定**用户选定出口的
 *    （见 [UnderlyingNetworkProvider.egressNetwork]）。用户一旦手选 WIFI/CELLULAR/ETHERNET，
 *    裸 Socket 量的就不是代理实际走的那条路。传入 [network] 即可对齐。
 *
 * 全程在 IO 线程，绝不阻塞主线程。
 */
object LatencyProbe {
    const val TIMEOUT_MS = 1000

    /** [network] 非空时在该网络上解析并绑定 socket（对齐代理真实出口）；为空则跟随系统默认网络。 */
    suspend fun measureMillis(
        host: String,
        port: Int = 443,
        timeoutMs: Int = TIMEOUT_MS,
        network: Network? = null,
    ): Long? = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            // ① 先解析：DNS 耗时不属于「链路延迟」，且在该出口网络上解析可避免走 VPN 的 DNS。
            val addr: InetAddress = runCatching {
                (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).firstOrNull()
            }.getOrNull() ?: return@withContext null
            // ② 再绑网：必须在 connect 之前。
            runCatching { network?.bindSocket(socket) }
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(addr, port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000L
        } catch (e: Throwable) {
            null
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }
}
