package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** 验证 RelayEngine 的流量计量 sink（喂给 Dashboard/通知的实时速率）。 */
class RelayTrafficTest {

    private fun Closeable.q() = try { close() } catch (e: Exception) {}

    @Test(timeout = 15000)
    fun accountsBytesBothDirections() {
        val up = AtomicLong(0)
        val down = AtomicLong(0)
        val relay = RelayEngine { u, d -> up.addAndGet(u); down.addAndGet(d) }

        val echo = startEcho()
        val clientServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val testClient = Socket("127.0.0.1", clientServer.localPort)
        val relayClient = clientServer.accept()              // relay 的 client 端
        val upstream = Socket("127.0.0.1", echo.localPort)   // relay 的 upstream 端

        // relay 跑在独立 scope（不被 runBlocking 等待）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { relay.relay(relayClient, upstream, 8192, 5000, Dispatchers.IO) }

        val payload = ByteArray(5000) { (it % 251).toByte() }
        testClient.getOutputStream().write(payload); testClient.getOutputStream().flush()
        val got = readN(testClient.getInputStream(), payload.size)
        Thread.sleep(100) // 让 sink 完成累计

        assertEquals("上行计量应等于发送字节数", payload.size.toLong(), up.get())
        assertEquals("下行计量应等于回显字节数", payload.size.toLong(), down.get())
        assertArrayEquals("中继不应损坏数据", payload, got)

        // 强制关闭所有 socket → 阻塞 read 终止 → relay 协程结束
        testClient.q(); relayClient.q(); upstream.q(); clientServer.q(); echo.q()
        scope.cancel()
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = input.read(b, off, n - off); if (r < 0) break; off += r }
        return b
    }

    private fun startEcho(): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val i = c.getInputStream(); val o = c.getOutputStream(); val buf = ByteArray(1024)
                        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
                    } catch (e: Exception) {} finally { c.close() }
                }
            }
        }
        return s
    }
}
