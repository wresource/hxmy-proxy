package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.HttpProxyServer
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.PacServer
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.Socks5ProxyServer
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import com.mzstd.hxmyproxy.core.security.DefaultEgressGuard
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.security.NoAuthAuthenticator
import com.mzstd.hxmyproxy.core.security.SingleCredentialAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 端到端代理集成测试：loopback 上起真实 server + 假上游，用真实客户端 socket 走代理。
 * 无需 Android 框架（纯 java.net + 协程）。
 */
class ProxyIntegrationTest {

    private val allowAll = object : EgressGuard {
        override fun isAllowed(addr: InetAddress) = true
    }

    // ---- 测试用例 ----

    @Test fun socks5NoAuthRelays() = runBlocking {
        val echo = startEcho()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = socks(scope, allowAll, NoAuthAuthenticator)
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val method = readN(inp, 2)
            assertEquals(0x05, method[0].toInt() and 0xFF)
            assertEquals(0x00, method[1].toInt() and 0xFF)
            connectSocks(out, echo.localPort)
            assertEquals(0x00, readN(inp, 10)[1].toInt() and 0xFF)
            out.write("hello".toByteArray()); out.flush()
            assertEquals("hello", String(readN(inp, 5)))
        }
        scope.cancel(); echo.close()
    }

    @Test fun socks5AuthSucceeds() = runBlocking {
        val echo = startEcho()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = socks(scope, allowAll, SingleCredentialAuthenticator("user", "pass", enabled = true))
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush()
            assertEquals(0x02, readN(inp, 2)[1].toInt() and 0xFF)
            out.write(userPass("user", "pass")); out.flush()
            val status = readN(inp, 2)
            assertEquals(0x01, status[0].toInt() and 0xFF)
            assertEquals(0x00, status[1].toInt() and 0xFF)
            connectSocks(out, echo.localPort)
            assertEquals(0x00, readN(inp, 10)[1].toInt() and 0xFF)
        }
        scope.cancel(); echo.close()
    }

    @Test fun socks5AuthWrongPasswordRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = socks(scope, allowAll, SingleCredentialAuthenticator("user", "pass", enabled = true))
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x02)); out.flush()
            readN(inp, 2)
            out.write(userPass("user", "WRONG")); out.flush()
            assertTrue(readN(inp, 2)[1].toInt() and 0xFF != 0) // non-zero = failure
        }
        scope.cancel()
    }

    @Test fun socks5EgressGuardBlocksLoopback() = runBlocking {
        val echo = startEcho()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // DefaultEgressGuard blocks loopback targets -> REP 0x02 (not allowed by ruleset)
        val server = Socks5ProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(DefaultEgressGuard()), RelayEngine(),
            { NoAuthAuthenticator }, { ConnectionLimits() },
        ).also { it.start(scope, 0) }
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush(); readN(inp, 2)
            connectSocks(out, echo.localPort)
            assertEquals(0x02, readN(inp, 10)[1].toInt() and 0xFF)
        }
        scope.cancel(); echo.close()
    }

    @Test fun httpConnectTunnels() = runBlocking {
        val echo = startEcho()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = http(scope, allowAll, NoAuthAuthenticator)
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            val ep = echo.localPort
            out.write("CONNECT 127.0.0.1:$ep HTTP/1.1\r\nHost: 127.0.0.1:$ep\r\n\r\n".toByteArray()); out.flush()
            assertTrue(readLine(inp).contains("200"))
            while (readLine(inp).isNotEmpty()) { /* drain headers */ }
            out.write("ping".toByteArray()); out.flush()
            assertEquals("ping", String(readN(inp, 4)))
        }
        scope.cancel(); echo.close()
    }

    @Test fun httpPlainForwards() = runBlocking {
        val upstream = startHttp("hi")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = http(scope, allowAll, NoAuthAuthenticator)
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            val ep = upstream.localPort
            out.write("GET http://127.0.0.1:$ep/ HTTP/1.1\r\nHost: 127.0.0.1:$ep\r\n\r\n".toByteArray()); out.flush()
            val text = String(inp.readBytes())
            assertTrue(text.contains("200"))
            assertTrue(text.endsWith("hi"))
        }
        scope.cancel(); upstream.close()
    }

    @Test fun httpConnectRequiresAuthWhenEnabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = http(scope, allowAll, SingleCredentialAuthenticator("u", "p", enabled = true))
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            out.write("CONNECT 127.0.0.1:80 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); out.flush()
            assertTrue(readLine(inp).contains("407"))
        }
        scope.cancel()
    }

    @Test fun pacServed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = PacServer(Dispatchers.IO, AllowAllAccessController, ConnectionRegistry()) {
            "function FindProxyForURL(url, host) { return \"DIRECT\"; }"
        }.also { it.start(scope, 0) }
        Socket("127.0.0.1", awaitPort(server)).use { sock ->
            sock.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
            val text = String(sock.getInputStream().readBytes())
            assertTrue(text.contains("application/x-ns-proxy-autoconfig"))
            assertTrue(text.contains("FindProxyForURL"))
        }
        scope.cancel()
    }

    // ---- 辅助 ----

    private fun socks(scope: CoroutineScope, egress: EgressGuard, auth: com.mzstd.hxmyproxy.core.security.Authenticator): ProxyServer =
        Socks5ProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(egress), RelayEngine(), { auth }, { ConnectionLimits() },
        ).also { it.start(scope, 0) }

    private fun http(scope: CoroutineScope, egress: EgressGuard, auth: com.mzstd.hxmyproxy.core.security.Authenticator): ProxyServer =
        HttpProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(egress), RelayEngine(), { auth }, { ConnectionLimits() },
        ).also { it.start(scope, 0) }

    private fun connectSocks(out: java.io.OutputStream, port: Int) {
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (port shr 8).toByte(), (port and 0xFF).toByte()))
        out.flush()
    }

    private fun userPass(user: String, pass: String): ByteArray {
        val u = user.toByteArray(); val p = pass.toByteArray()
        val b = ByteArrayOutputStream()
        b.write(0x01); b.write(u.size); b.write(u); b.write(p.size); b.write(p)
        return b.toByteArray()
    }

    private fun awaitPort(server: ProxyServer): Int {
        repeat(300) {
            val p = server.boundPort.value
            if (p != null && p > 0) return p
            Thread.sleep(10)
        }
        throw AssertionError("server did not bind")
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw EOFException("expected $n bytes, got $off")
            off += r
        }
        return b
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun startEcho(): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val inp = c.getInputStream(); val out = c.getOutputStream()
                        val buf = ByteArray(4096)
                        while (true) { val r = inp.read(buf); if (r < 0) break; out.write(buf, 0, r); out.flush() }
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        c.close()
                    }
                }
            }
        }
        return s
    }

    private fun startHttp(body: String): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val inp = c.getInputStream()
                        val sb = StringBuilder()
                        while (true) {
                            val ch = inp.read()
                            if (ch < 0) break
                            sb.append(ch.toChar())
                            if (sb.endsWith("\r\n\r\n")) break
                        }
                        val bytes = body.toByteArray()
                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body"
                        c.getOutputStream().write(resp.toByteArray()); c.getOutputStream().flush()
                    } catch (e: Exception) {
                        // ignore
                    } finally {
                        c.close()
                    }
                }
            }
        }
        return s
    }
}
