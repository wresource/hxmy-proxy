package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.NioRelayReactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

/** NioRelayReactor 单测（JVM loopback SocketChannel）：往返转发、EOF 半关结束、idle 超时、stop 拆隧道。 */
class NioRelayReactorTest {

    /** 一对已连接的阻塞 SocketChannel（loopback）。 */
    private fun connectedPair(): Pair<SocketChannel, SocketChannel> {
        val ss = ServerSocketChannel.open()
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val a = SocketChannel.open(ss.localAddress as InetSocketAddress)
        val b = ss.accept()
        ss.close()
        a.configureBlocking(true); b.configureBlocking(true)
        return a to b
    }

    private fun SocketChannel.writeStr(s: String) {
        val buf = ByteBuffer.wrap(s.toByteArray())
        while (buf.hasRemaining()) write(buf)
    }

    private fun SocketChannel.readStr(n: Int): String {
        val buf = ByteBuffer.allocate(n)
        while (buf.position() < n) if (read(buf) < 0) break
        return String(buf.array(), 0, buf.position())
    }

    @Test fun forwardsBothDirections() = runBlocking {
        val (clientPeer, clientCh) = connectedPair()
        val (upstreamCh, upstreamPeer) = connectedPair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = NioRelayReactor(workerCount = 1)
        reactor.start()
        val up = AtomicLong(0); val down = AtomicLong(0)
        val job = launch(Dispatchers.IO) {
            reactor.relay(clientCh, upstreamCh, 8192, 0) { u, d -> up.addAndGet(u); down.addAndGet(d) }
        }
        withTimeout(5000) {
            withContext(Dispatchers.IO) {
                clientPeer.writeStr("hello")
                assertEquals("hello", upstreamPeer.readStr(5))      // client → upstream
                upstreamPeer.writeStr("world!!")
                assertEquals("world!!", clientPeer.readStr(7))      // upstream → client
                // 关两端 → 双向 EOF → 整条隧道关闭（只关一端是半关，另一方向会正确续传不结束）
                clientPeer.close(); upstreamPeer.close()
            }
            job.join()
        }
        reactor.stop()
        assertEquals(5L, up.get())
        assertEquals(7L, down.get())
        runCatching { upstreamPeer.close() }
        Unit
    }

    @Test fun idleTimeoutClosesTunnel() = runBlocking {
        val (clientPeer, clientCh) = connectedPair()
        val (upstreamCh, upstreamPeer) = connectedPair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = NioRelayReactor(workerCount = 1, sweepIntervalMs = 100)
        reactor.start()
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 300) { _, _ -> } }
        withTimeout(5000) { job.join() }                           // 不发数据 → idle 300ms → sweep 拆
        reactor.stop()
        runCatching { clientPeer.close() }; runCatching { upstreamPeer.close() }
        Unit
    }

    @Test fun stopClosesActiveTunnels() = runBlocking {
        val (clientPeer, clientCh) = connectedPair()
        val (upstreamCh, upstreamPeer) = connectedPair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = NioRelayReactor(workerCount = 1)
        reactor.start()
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 0) { _, _ -> } }
        delay(200)                                                 // 确保注册
        reactor.stop()                                             // 拆所有在途隧道 → 协程结束
        withTimeout(5000) { job.join() }
        runCatching { clientPeer.close() }; runCatching { upstreamPeer.close() }
        Unit
    }
}
