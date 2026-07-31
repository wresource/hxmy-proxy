package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.Socks5ProxyServer
import com.mzstd.hxmyproxy.core.security.AccessController
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.security.NoAuthAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 接入层（TcpProxyServerBase）的**闸门与收场**：准入拒绝、连接数超限、计数释放、
 * stop/evict 拆在途连接、bind 失败暴露为状态。
 *
 * 这一层出错的形态最难排查——客户端只看到「连上了但什么都不发生」或「莫名其妙断线」，
 * 所以每条闸门都要断言：被拒的连接**立刻关**（不是挂着占 FD），既有连接**不受牵连**。
 */
class ProxyAdmissionTest {

    private val res = Resources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    /** 一律拒绝的准入器（模拟「用户没勾选任何接口」的 fail-closed 口径）。 */
    private object DenyAll : AccessController {
        override fun admit(localAddress: InetAddress, remoteAddress: InetAddress) = false
    }

    @After fun tearDown() {
        res.close()
        scope.cancel()
    }

    /** 准入拒绝：连接必须被立即关闭，且**不占**连接名额（否则被拒的连接会把上限吃光）。 */
    @Test(timeout = 20000) fun `准入拒绝的连接应被立即关闭且不占用连接计数`() {
        val registry = ConnectionRegistry(maxGlobal = 8, maxPerClient = 8)
        val server = socks(registry, access = DenyAll)
        val sock = connect(server)
        assertTrue("被准入拒绝的连接应被关闭", ProxyTestKit.closedByPeer(sock))
        assertEquals("拒绝路径不得占用连接名额", 0, registry.activeGlobal)
    }

    /**
     * accept 计数的语义是「accept 循环还活着、有连接进来」，故必须记在**准入判定之前**。
     * 若挪到准入之后，全被拒的场景下心跳里该数恒为 0——「SYN 没到手机」与「到了但被拒」再次同形，
     * 而这正是当初加这个计数要消灭的仪表盲区。
     */
    @Test(timeout = 20000) fun `accept计数在准入判定之前累加`() {
        val server = socks(ConnectionRegistry(), access = DenyAll)
        val sock = connect(server)
        assertTrue(ProxyTestKit.closedByPeer(sock))
        assertTrue("被拒的连接也应计入 accept 数", ProxyTestKit.await { server.acceptCount >= 1 })
    }

    /** 达到全局上限：新连接立即关闭，**既有连接照常收发**（超限不能牵连已建立的隧道）。 */
    @Test(timeout = 20000) fun `达到全局上限的新连接应被立即关闭且不影响既有连接`() {
        val registry = ConnectionRegistry(maxGlobal = 1, maxPerClient = 8)
        val echo = res.keep(ProxyTestKit.startEcho())
        val server = socks(registry)

        val first = tunnel(server, echo)
        assertEquals(1, registry.activeGlobal)

        val second = connect(server)
        assertTrue("超限连接必须被立即关闭，而不是挂着占 FD", ProxyTestKit.closedByPeer(second))

        ProxyTestKit.write(first, "alive")
        assertEquals("既有隧道不应被新连接的超限拒绝牵连", "alive", String(ProxyTestKit.readN(first.getInputStream(), 5)))
    }

    /**
     * 连接结束必须释放名额。泄漏的表现是隐形的：跑一天后某个客户端 IP 永远连不上，
     * 而日志里只有「reject.limit」——看起来像用户开太多连接，实则是计数没还回去。
     */
    @Test(timeout = 20000) fun `连接结束后应释放连接计数`() {
        val registry = ConnectionRegistry(maxGlobal = 4, maxPerClient = 4)
        val server = socks(registry)
        val sock = connect(server)
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x00))
        ProxyTestKit.readN(sock.getInputStream(), 2)
        assertEquals(1, registry.activeGlobal)

        sock.close()   // 客户端断开 → 握手读到 EOF → handle 收尾 → 释放
        assertTrue("连接关闭后计数应回到 0", ProxyTestKit.await { registry.activeGlobal == 0 })
    }

    /** stop() 要主动拆在途连接：否则阻塞中的 relay 会残留到空闲超时，FD 与线程都还不回来。 */
    @Test(timeout = 20000) fun `stop应关闭在途连接`() {
        val echo = res.keep(ProxyTestKit.startEcho())
        val server = socks(ConnectionRegistry())
        val sock = tunnel(server, echo)

        server.stop()

        assertTrue("stop 后在途隧道应被拆掉", ProxyTestKit.closedByPeer(sock))
        assertTrue("停止后不应再报绑定端口", ProxyTestKit.await { server.boundPort.value == null })
    }

    /**
     * 准入集合收缩时的 evict：判定为**可继续**的连接一根不能动，判定为**不再准入**的必须拆。
     * 谓词写反的后果是灾难性的——要么切网时全体掉线，要么该断的连接继续从旧网段进来。
     */
    @Test(timeout = 20000) fun `evict应只拆掉判定为不再准入的连接`() {
        val echo = res.keep(ProxyTestKit.startEcho())
        val server = socks(ConnectionRegistry())
        val sock = tunnel(server, echo)

        server.evictNotAdmitted { _, _ -> true }
        ProxyTestKit.write(sock, "keep")
        assertEquals("仍被准入的连接不能被误拆", "keep", String(ProxyTestKit.readN(sock.getInputStream(), 4)))

        server.evictNotAdmitted { _, _ -> false }
        assertTrue("不再准入的连接必须被拆", ProxyTestKit.closedByPeer(sock))
    }

    /**
     * bind 失败（这里用越界端口触发）必须暴露成 [ProxyServer.bindError] 状态供 UI 提示，
     * 而不是把异常抛进 scope——那会冒泡到全局 handler 直接崩掉 App。
     */
    @Test(timeout = 20000) fun `非法端口bind失败应暴露bindError而非崩溃`() {
        val server = socks(ConnectionRegistry(), autoStart = false)
        server.start(scope, 70000)   // 端口越界：InetSocketAddress 构造即抛
        assertTrue("bind 失败应置起 bindError", ProxyTestKit.await { server.bindError.value != null })
        assertEquals(ProxyError.PortInUse, server.bindError.value)
        assertNull("bind 失败不应报告绑定端口", server.boundPort.value)
    }

    // ---- 辅助 ----

    private fun socks(
        registry: ConnectionRegistry,
        access: AccessController = AllowAllAccessController,
        autoStart: Boolean = true,
    ): ProxyServer {
        val server = Socks5ProxyServer(
            Dispatchers.IO, access, registry,
            OutboundConnector(allowAll), RelayEngine(), { NoAuthAuthenticator }, { ConnectionLimits() },
            Dispatchers.IO,
        )
        if (autoStart) server.start(scope, 0)
        res.onClose { server.stop() }
        return server
    }

    private fun connect(server: ProxyServer): Socket =
        res.keep(ProxyTestKit.client(ProxyTestKit.awaitPort(server)))

    /** 建一条到 [echo] 的 SOCKS5 隧道并验证连通，返回客户端 socket。 */
    private fun tunnel(server: ProxyServer, echo: ServerSocket): Socket {
        val sock = connect(server)
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x00))
        assertEquals(0x00, ProxyTestKit.readN(sock.getInputStream(), 2)[1].toInt() and 0xFF)
        val port = echo.localPort
        ProxyTestKit.write(
            sock,
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (port shr 8).toByte(), (port and 0xFF).toByte()),
        )
        assertEquals(0x00, ProxyTestKit.readN(sock.getInputStream(), 10)[1].toInt() and 0xFF)
        return sock
    }
}
