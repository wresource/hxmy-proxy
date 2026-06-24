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
import org.junit.Assert.assertArrayEquals
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
        client(awaitPort(server)).use { sock ->
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
        client(awaitPort(server)).use { sock ->
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
        client(awaitPort(server)).use { sock ->
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
            { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO,
        ).also { it.start(scope, 0) }
        client(awaitPort(server)).use { sock ->
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
        client(awaitPort(server)).use { sock ->
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
        client(awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            val ep = upstream.localPort
            // Connection: close → 客户端要求单次即关，readBytes 读到 EOF（不触发 keep-alive 等待）
            out.write("GET http://127.0.0.1:$ep/ HTTP/1.1\r\nHost: 127.0.0.1:$ep\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
            val text = String(inp.readBytes())
            assertTrue(text.contains("200"))
            assertTrue(text.endsWith("hi"))
        }
        scope.cancel(); upstream.close()
    }

    /**
     * keep-alive 回归：同一客户端连接连发两个请求，取回两份**二进制**响应并逐字节校验。
     * 这正是浏览器加载网页小图/资源的模式——旧实现一连接只处理一个请求，第二个请求会丢/挂死。
     */
    @Test fun httpKeepAliveServesMultipleBinaryRequests() = runBlocking {
        val img1 = ByteArray(1500) { (it * 31 + 0x89).toByte() }   // 含 0x00/0xFF 等非 ASCII 字节
        val img2 = ByteArray(900) { (255 - (it % 256)).toByte() }
        val origin = startKeepAliveBinaryOrigin { path -> if (path.contains("img1")) img1 else img2 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = http(scope, allowAll, NoAuthAuthenticator)
        client(awaitPort(server)).use { sock ->
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            val ep = origin.localPort
            // 请求 1
            out.write("GET http://127.0.0.1:$ep/img1.png HTTP/1.1\r\nHost: 127.0.0.1:$ep\r\n\r\n".toByteArray()); out.flush()
            val (s1, b1) = readFramedResponse(inp)
            assertEquals(200, s1)
            assertArrayEquals("img1 二进制应逐字节一致", img1, b1)
            // 请求 2（复用同一连接 —— 旧代码在此 EOF/超时）
            out.write("GET http://127.0.0.1:$ep/img2.png HTTP/1.1\r\nHost: 127.0.0.1:$ep\r\n\r\n".toByteArray()); out.flush()
            val (s2, b2) = readFramedResponse(inp)
            assertEquals(200, s2)
            assertArrayEquals("img2 二进制应逐字节一致", img2, b2)
        }
        scope.cancel(); origin.close()
    }

    @Test fun httpConnectRequiresAuthWhenEnabled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = http(scope, allowAll, SingleCredentialAuthenticator("u", "p", enabled = true))
        client(awaitPort(server)).use { sock ->
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
        client(awaitPort(server)).use { sock ->
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
            OutboundConnector(egress), RelayEngine(), { auth }, { ConnectionLimits() }, Dispatchers.IO,
        ).also { it.start(scope, 0) }

    private fun http(scope: CoroutineScope, egress: EgressGuard, auth: com.mzstd.hxmyproxy.core.security.Authenticator): ProxyServer =
        HttpProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(egress), RelayEngine(), { auth }, { ConnectionLimits() }, Dispatchers.IO,
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

    /**
     * 客户端连接，统一设置 SO_TIMEOUT。否则机器高负载时代理协程可能被饿死、响应迟迟不来，
     * 阻塞 socket 读会永久挂起整个测试套件（JUnit @Test(timeout) 靠 Thread.interrupt 无法中断
     * 阻塞 socket 读）。设超时后，卡住的读改为抛 SocketTimeoutException → 快速失败而非挂死。
     */
    private fun client(port: Int): Socket = Socket("127.0.0.1", port).apply { soTimeout = 8000 }

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

    /** 读一个带 Content-Length 定界的 HTTP 响应：返回 (状态码, 响应体字节)。 */
    private fun readFramedResponse(inp: InputStream): Pair<Int, ByteArray> {
        val statusLine = readLine(inp)
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        var len = -1
        while (true) {
            val h = readLine(inp)
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && h.substring(0, i).trim().equals("Content-Length", true)) {
                len = h.substring(i + 1).trim().toInt()
            }
        }
        val body = if (len >= 0) readN(inp, len) else ByteArray(0)
        return status to body
    }

    /**
     * keep-alive 二进制源：单连接循环处理多个请求，每个请求回一份带 Content-Length 的二进制体，
     * **不发 Connection: close**、保持连接打开供复用。
     */
    private fun startKeepAliveBinaryOrigin(payload: (String) -> ByteArray): ServerSocket {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    try {
                        val inp = c.getInputStream(); val out = c.getOutputStream()
                        while (true) {
                            val reqLine = readLine(inp)
                            if (reqLine.isEmpty()) break // EOF / 客户端关闭
                            val path = reqLine.split(" ").getOrElse(1) { "/" }
                            while (true) { val h = readLine(inp); if (h.isEmpty()) break } // 排空头
                            val body = payload(path)
                            val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\n\r\n"
                            out.write(head.toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush()
                        }
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
