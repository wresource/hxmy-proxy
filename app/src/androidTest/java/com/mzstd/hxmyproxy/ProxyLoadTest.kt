package com.mzstd.hxmyproxy

import android.util.Log
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.HttpProxyServer
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.PacServer
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 真机（Pixel 10 Pro / API 37）端到端代理负载/功能测试 —— "代理服务器正常该干的事"。
 *
 * 全程 loopback：client → 真 App 代理(127.0.0.1) → 设备本地源(127.0.0.1)。
 * 回环连接豁免 ACCESS_LOCAL_NETWORK，且不依赖外网，确定可复现。客户端用 HttpURLConnection
 * （App/SDK 底层常用），分别走 HTTP 代理 与 SOCKS5 代理；CONNECT 隧道用原始 socket 验证。
 *
 * 覆盖：网页浏览(HTML) / App API(JSON GET+POST) / CONNECT 隧道 / SOCKS5 / PAC /
 *      50 并发 / 大文件吞吐。另含对真实站点(bing)的容错冒烟（外网不通则跳过、不判失败）。
 */
@RunWith(AndroidJUnit4::class)
class ProxyLoadTest {

    private val tag = "hxmyLoadTest"
    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    private lateinit var scope: CoroutineScope
    private lateinit var origin: ServerSocket
    private var originPort = 0
    private lateinit var http: ProxyServer
    private lateinit var socks: ProxyServer
    private lateinit var pac: ProxyServer
    private var httpPort = 0
    private var socksPort = 0
    private var pacPort = 0

    private val downloadBytes = 8 * 1024 * 1024 // 8 MB 吞吐样本

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        origin = startOrigin()
        originPort = origin.localPort
        http = HttpProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(allowAll), RelayEngine(), { NoAuthAuthenticator }, { ConnectionLimits() },
        ).also { it.start(scope, 0) }
        socks = Socks5ProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(allowAll), RelayEngine(), { NoAuthAuthenticator }, { ConnectionLimits() },
        ).also { it.start(scope, 0) }
        pac = PacServer(Dispatchers.IO, AllowAllAccessController, ConnectionRegistry()) {
            "function FindProxyForURL(url, host) { return \"PROXY 127.0.0.1:$httpPort; DIRECT\"; }"
        }.also { it.start(scope, 0) }
        httpPort = awaitPort(http); socksPort = awaitPort(socks); pacPort = awaitPort(pac)
        Log.i(tag, "ports origin=$originPort http=$httpPort socks=$socksPort pac=$pacPort")
    }

    @After fun tearDown() {
        runCatching { http.stop() }; runCatching { socks.stop() }; runCatching { pac.stop() }
        runCatching { scope.cancel() }; runCatching { origin.close() }
    }

    // ---------- 功能：网页浏览 ----------

    @Test fun httpProxy_browsesHtmlPage() {
        val (code, body) = httpGetVia(httpProxy(), "http://127.0.0.1:$originPort/")
        assertEquals(200, code)
        assertTrue("应返回 HTML 页", body.contains("hxmy proxy test page"))
    }

    // ---------- 功能：App API（JSON 读 + 写）----------

    @Test fun httpProxy_appApiJsonGet() {
        val (code, body) = httpGetVia(httpProxy(), "http://127.0.0.1:$originPort/api/users")
        assertEquals(200, code)
        assertTrue("应返回 JSON 用户列表", body.contains("\"name\":\"alice\""))
    }

    @Test fun httpProxy_appApiJsonPostEcho() {
        val payload = "{\"event\":\"login\",\"uid\":42}"
        val (code, body) = httpPostVia(httpProxy(), "http://127.0.0.1:$originPort/api/echo", payload)
        assertEquals(200, code)
        assertEquals("POST 回显体应一致（代理需转发请求体）", payload, body)
    }

    // ---------- 功能：CONNECT 隧道（HTTPS 浏览的隧道机制）----------

    @Test fun httpProxy_connectTunnelCarriesHttp() {
        Socket("127.0.0.1", httpPort).use { sock ->
            sock.soTimeout = 8000
            val out = sock.getOutputStream(); val inp = sock.getInputStream()
            out.write("CONNECT 127.0.0.1:$originPort HTTP/1.1\r\nHost: 127.0.0.1:$originPort\r\n\r\n".toByteArray())
            out.flush()
            assertTrue("CONNECT 应 200 建立隧道", readLine(inp).contains("200"))
            while (readLine(inp).isNotEmpty()) { /* drain headers */ }
            // 隧道内发原始 HTTP，验证不透明字节双向转发
            out.write("GET /api/users HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val resp = String(inp.readBytes())
            assertTrue("隧道内应取回 JSON", resp.contains("200") && resp.contains("alice"))
        }
    }

    // ---------- 功能：SOCKS5 浏览 + API ----------

    @Test fun socks5Proxy_browsesAndApi() {
        val (c1, b1) = httpGetVia(socksProxy(), "http://127.0.0.1:$originPort/")
        assertEquals(200, c1); assertTrue(b1.contains("hxmy proxy test page"))
        val (c2, b2) = httpGetVia(socksProxy(), "http://127.0.0.1:$originPort/api/users")
        assertEquals(200, c2); assertTrue(b2.contains("alice"))
    }

    // ---------- 功能：PAC 分发 ----------

    @Test fun pac_servedToClients() {
        val (code, body) = httpGetVia(Proxy.NO_PROXY, "http://127.0.0.1:$pacPort/proxy.pac")
        assertEquals(200, code)
        assertTrue("PAC 应含 FindProxyForURL", body.contains("FindProxyForURL"))
    }

    // ---------- 负载：50 并发 ----------

    @Test fun httpProxy_50ConcurrentRequests() {
        val n = 50
        val pool = Executors.newFixedThreadPool(32)
        val ok = AtomicInteger(0); val done = CountDownLatch(n)
        val start = System.nanoTime()
        repeat(n) {
            pool.execute {
                try {
                    val (code, body) = httpGetVia(httpProxy(), "http://127.0.0.1:$originPort/api/users")
                    if (code == 200 && body.contains("alice")) ok.incrementAndGet()
                } catch (e: Exception) {
                    Log.w(tag, "concurrent req failed: ${e.message}")
                } finally { done.countDown() }
            }
        }
        assertTrue("50 并发应在 30s 内完成", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.i(tag, "concurrency: ${ok.get()}/$n ok in ${ms}ms")
        assertEquals("全部并发请求应成功", n, ok.get())
    }

    // ---------- 负载：大文件吞吐 ----------

    @Test fun httpProxy_throughputLargeDownload() {
        val conn = (URL("http://127.0.0.1:$originPort/download").openConnection(httpProxy()) as HttpURLConnection)
        conn.connectTimeout = 8000; conn.readTimeout = 20000
        val start = System.nanoTime()
        val total: Long
        conn.inputStream.use { total = drain(it) }
        val ms = (System.nanoTime() - start) / 1_000_000
        conn.disconnect()
        assertEquals("下载字节数应一致", downloadBytes.toLong(), total)
        val mbps = if (ms > 0) (total.toDouble() / (1024 * 1024)) / (ms / 1000.0) else Double.NaN
        Log.i(tag, "throughput: ${total / (1024 * 1024)}MB in ${ms}ms = %.1f MB/s".format(mbps))
        assertTrue("吞吐应 > 1 MB/s（loopback）", mbps > 1.0)
    }

    // ---------- 真实外网容错冒烟（不通则跳过，不判失败）----------

    @Test fun httpProxy_realInternetSmokeTolerant() {
        // 用 HTTPS（走 CONNECT 隧道，不受 App 明文策略限制），真正验证代理把流量送达真实互联网。
        // 模拟器 NAT 直连出口在本网络可能受限 → 不通则跳过、不判失败。
        var anyReachable = false
        for (host in listOf("https://www.bing.com/", "https://example.com/")) {
            try {
                val (code, _) = httpGetVia(httpProxy(), host, timeoutMs = 8000)
                Log.i(tag, "real-internet $host via proxy -> HTTP $code")
                if (code in 200..399) anyReachable = true
            } catch (e: Exception) {
                Log.w(tag, "real-internet $host via proxy skipped: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.i(tag, "real-internet egress reachable via proxy = $anyReachable")
    }

    // ================= 辅助 =================

    private fun httpProxy() = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
    private fun socksProxy() = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

    private fun httpGetVia(proxy: Proxy, url: String, timeoutMs: Int = 8000): Pair<Int, String> {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            requestMethod = "GET"; setRequestProperty("Connection", "close")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.let { String(it.readBytes()) } ?: ""
            code to body
        } finally { conn.disconnect() }
    }

    private fun httpPostVia(proxy: Proxy, url: String, payload: String): Pair<Int, String> {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Connection", "close")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..399) conn.inputStream else conn.errorStream)?.let { String(it.readBytes()) } ?: ""
            code to body
        } finally { conn.disconnect() }
    }

    private fun drain(input: InputStream): Long {
        val buf = ByteArray(64 * 1024); var total = 0L
        val bin = BufferedInputStream(input, buf.size)
        while (true) { val r = bin.read(buf); if (r < 0) break; total += r }
        return total
    }

    private fun awaitPort(server: ProxyServer): Int {
        repeat(300) { val p = server.boundPort.value; if (p != null && p > 0) return p; Thread.sleep(10) }
        throw AssertionError("server did not bind")
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
        return sb.toString()
    }

    /**
     * 设备本地 HTTP/1.1 源服务器（模拟网站 + App 后端）。每连接一请求，响应带
     * Content-Length 与 Connection: close 后关闭，确定可控。
     */
    private fun startOrigin(): ServerSocket {
        val s = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return s
    }

    private fun handle(c: Socket) {
        val inp = c.getInputStream(); val out = c.getOutputStream()
        // 解析请求行
        val requestLine = readLine(inp)
        if (requestLine.isEmpty()) return
        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }
        // 解析头，取 Content-Length
        var contentLength = 0
        while (true) {
            val h = readLine(inp); if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0 && h.substring(0, idx).trim().equals("Content-Length", true)) {
                contentLength = h.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val b = ByteArray(contentLength); var off = 0
            while (off < contentLength) { val r = inp.read(b, off, contentLength - off); if (r < 0) break; off += r }
            b
        } else ByteArray(0)

        when {
            method == "POST" && path.startsWith("/api/echo") ->
                respond(out, 200, "application/json", body)
            path.startsWith("/api/users") ->
                respond(out, 200, "application/json",
                    "[{\"id\":1,\"name\":\"alice\"},{\"id\":2,\"name\":\"bob\"}]".toByteArray())
            path.startsWith("/download") -> {
                val data = ByteArray(downloadBytes) { (it and 0xFF).toByte() }
                respond(out, 200, "application/octet-stream", data)
            }
            path == "/" || path.startsWith("/index") ->
                respond(out, 200, "text/html",
                    "<html><head><title>hxmy</title></head><body><h1>hxmy proxy test page</h1></body></html>".toByteArray())
            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, contentType: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else if (code == 404) "Not Found" else "Status"
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val buf = ByteArrayOutputStream()
        buf.write(header.toByteArray()); buf.write(body)
        out.write(buf.toByteArray()); out.flush()
    }
}
