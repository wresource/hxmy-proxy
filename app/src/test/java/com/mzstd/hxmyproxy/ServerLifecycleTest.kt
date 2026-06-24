package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.Socks5ProxyServer
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.security.NoAuthAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** 验证监听器 start→停→重启重绑（协议热重启依赖的机制）。 */
class ServerLifecycleTest {

    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    private fun newServer() = Socks5ProxyServer(
        Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
        OutboundConnector(allowAll), RelayEngine(), { NoAuthAuthenticator }, { ConnectionLimits() },
        Dispatchers.IO,
    )

    private fun awaitPort(s: ProxyServer): Int {
        repeat(300) { val p = s.boundPort.value; if (p != null && p > 0) return p; Thread.sleep(10) }
        throw AssertionError("未绑定端口")
    }

    private fun awaitCleared(s: ProxyServer) {
        repeat(200) { if (s.boundPort.value == null) return; Thread.sleep(10) }
    }

    private fun awaitBindError(s: ProxyServer): ProxyError? {
        repeat(300) { val e = s.bindError.value; if (e != null) return e; Thread.sleep(10) }
        return null
    }

    @Test(timeout = 15000)
    fun startStopRestartRebinds() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = newServer()

        server.start(scope, 0)
        assertTrue(awaitPort(server) > 0)

        server.stop()
        awaitCleared(server)
        assertNull("停止后 boundPort 应为 null", server.boundPort.value)

        // 重启（新临时端口）—— 依赖 SO_REUSEADDR
        server.start(scope, 0)
        assertTrue(awaitPort(server) > 0)

        server.stop()
        scope.cancel()
    }

    @Test(timeout = 15000)
    fun fixedPortReboundAfterStop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = newServer()
        server.start(scope, 0)
        val port = awaitPort(server)
        server.stop()
        awaitCleared(server)
        assertNull(server.boundPort.value)

        val server2 = newServer()
        server2.start(scope, port)   // 同端口重启验证可重绑
        assertTrue(awaitPort(server2) == port)
        server2.stop()
        scope.cancel()
    }

    /** 运行时改到被占用端口：bind 失败应暴露为 bindError（而非抛进 scope 崩溃），原监听不受影响。 */
    @Test(timeout = 15000)
    fun bindFailureSurfacesErrorWithoutCrashing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = newServer()
        first.start(scope, 0)
        val port = awaitPort(first)

        // 第二台抢同一正在监听的端口 → bind 必失败。
        val second = newServer()
        second.start(scope, port)

        assertEquals(ProxyError.PortInUse, awaitBindError(second))
        assertNull("bind 失败不应绑定端口", second.boundPort.value)
        // scope 仍存活、第一台仍在监听（异常没有冒泡毁掉整个引擎）。
        assertEquals(port, first.boundPort.value)

        first.stop()
        second.stop()
        scope.cancel()
    }
}
