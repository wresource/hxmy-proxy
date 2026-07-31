package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.PacServer
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * PAC / 扫码配置服务的路由与响应头。
 *
 * 这台服务是「扫码接入」的唯一入口，出错的形态是：手机扫码后浏览器白屏、或 iOS 拿到 PAC 却不认
 * （MIME 不对就当纯文本忽略）、或描述文件不下载只在页面上显示一堆 XML。
 * 因此断言重点是**状态码 + Content-Type/Disposition + 回链基址**，而不只是「有没有内容」。
 */
class PacServerTest {

    private val res = Resources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() {
        res.close()
        scope.cancel()
    }

    /** 未知路径必须 404（而不是把落地页当兜底返回——那样任何拼错的路径都像成功了）。 */
    @Test(timeout = 20000) fun `未知路径应回404`() {
        val (head, _) = get(server { "pac" }, "/does-not-exist")
        assertEquals(404, head.status)
        assertEquals(0, head.contentLength)
    }

    /** 只承载 GET；POST/PUT 等一律 405，不能落进 PAC 分支被当成正常拉取。 */
    @Test(timeout = 20000) fun `非GET方法应回405`() {
        val sock = res.keep(ProxyTestKit.client(ProxyTestKit.awaitPort(server { "pac" })))
        val head = ProxyTestKit.sendAndReadHead(sock, "POST /proxy.pac HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(405, head.status)
    }

    /**
     * PAC 的 MIME 必须是 application/x-ns-proxy-autoconfig —— 写错的话 iOS/macOS 会拒绝使用这份 PAC，
     * 现象是「代理配上了但全部直连」。同时带 max-age 缓存语义，降低 iOS 反复 fetch 撞上不可达的概率。
     */
    @Test(timeout = 20000) fun `PAC应带autoconfig类型与缓存头`() {
        val (head, body) = get(server { "function FindProxyForURL(u,h){return \"DIRECT\";}" }, "/proxy.pac")
        assertEquals(200, head.status)
        assertEquals("application/x-ns-proxy-autoconfig", head.value("Content-Type"))
        assertEquals("max-age=300", head.value("Cache-Control"))
        assertEquals("Content-Length 必须与实际字节数一致，否则客户端会截断或挂等", body.size, head.contentLength)
        assertTrue(String(body).contains("FindProxyForURL"))
    }

    /** 带查询串的 PAC 地址（有些客户端会加 ?t=时间戳 防缓存）也必须命中路由。 */
    @Test(timeout = 20000) fun `PAC路径带查询串仍应命中`() {
        val (head, _) = get(server { "pac" }, "/proxy.pac?t=12345")
        assertEquals(200, head.status)
        assertEquals("application/x-ns-proxy-autoconfig", head.value("Content-Type"))
    }

    /**
     * PAC 内容必须**每次请求现取**：接口/端口变化后新拉取的 PAC 要反映最新代理列表，
     * 若被缓存成常量，用户换网后所有客户端仍指向旧 IP（现象：切网后全体断网、重启 App 才好）。
     */
    @Test(timeout = 20000) fun `PAC内容应每次向provider现取`() {
        val n = AtomicInteger(0)
        val s = server { "pac#${n.incrementAndGet()}" }
        val (_, first) = get(s, "/proxy.pac")
        val (_, second) = get(s, "/proxy.pac")
        assertEquals("pac#1", String(first))
        assertEquals("pac#2", String(second))
    }

    /** 落地页的回链基址取**本次连接的本机地址**，扫码设备连的就是它，故必然可达。 */
    @Test(timeout = 20000) fun `根路径应返回落地页且回链指向本机`() {
        val s = server { "pac" }
        val port = ProxyTestKit.awaitPort(s)
        val (head, body) = get(s, "/")
        assertEquals(200, head.status)
        assertTrue(head.value("Content-Type")!!.startsWith("text/html"))
        assertTrue("落地页必须给出本机 PAC 地址", String(body).contains("http://127.0.0.1:$port/proxy.pac"))
    }

    /** `/setup` 与 `/` 是同一张页面（二维码里印哪个都行）。 */
    @Test(timeout = 20000) fun `setup与根路径应返回同一落地页`() {
        val s = server { "pac" }
        val (_, root) = get(s, "/")
        val (_, setup) = get(s, "/setup")
        assertEquals(String(root), String(setup))
    }

    /** 描述文件要能**被下载**（Content-Disposition + Apple 专用 MIME），否则 Safari 只会把 XML 显示出来。 */
    @Test(timeout = 20000) fun `mobileconfig应带下载头并解码SSID`() {
        val (head, body) = get(server { "pac" }, "/hxmy.mobileconfig?ssid=My%20WiFi")
        assertEquals(200, head.status)
        assertEquals("application/x-apple-aspen-config", head.value("Content-Type"))
        assertTrue(head.value("Content-Disposition")!!.contains("attachment"))
        assertTrue(head.value("Content-Disposition")!!.contains("hxmy.mobileconfig"))
        assertTrue("SSID 需 URL 解码后写入载荷", String(body).contains("<string>My WiFi</string>"))
    }

    /** HTTP 代理在跑时给 iPhone 发 Manual HTTP 描述文件（免 PAC 拉取，最稳的一条路）。 */
    @Test(timeout = 20000) fun `HTTP代理启用时描述文件应走ManualHTTP`() {
        val (_, body) = get(server(httpProxyPort = { 8080 }) { "pac" }, "/hxmy.mobileconfig?ssid=W")
        val xml = String(body)
        assertTrue(xml.contains("<key>ProxyType</key><string>Manual</string>"))
        assertTrue(xml.contains("<key>ProxyServerPort</key><integer>8080</integer>"))
        assertFalse("Manual 模式不该再塞 PAC URL（两者并存会让 iOS 行为不确定）", xml.contains("ProxyPACURL"))
    }

    /** 只开 SOCKS（无 HTTP 端口）时回退 Auto/PAC —— 这是 iPhone 唯一能用的形态。 */
    @Test(timeout = 20000) fun `未启用HTTP代理时描述文件应回退PAC`() {
        val (_, body) = get(server(httpProxyPort = { null }) { "pac" }, "/hxmy.mobileconfig?ssid=W")
        val xml = String(body)
        assertTrue(xml.contains("<key>ProxyType</key><string>Auto</string>"))
        assertTrue(xml.contains("ProxyPACURL"))
    }

    /** 语言跟**扫码设备的浏览器**走（可能是英文电脑），与手机 App 语言无关。 */
    @Test(timeout = 20000) fun `AcceptLanguage为英文时落地页应用英文`() {
        val s = server { "pac" }
        val sock = res.keep(ProxyTestKit.client(ProxyTestKit.awaitPort(s)))
        val head = ProxyTestKit.sendAndReadHead(
            sock,
            "GET / HTTP/1.1\r\nHost: x\r\nAccept-Language: en-US,en;q=0.9\r\n\r\n",
        )
        val html = String(ProxyTestKit.readBody(sock.getInputStream(), head))
        assertTrue(html.contains("lang=\"en\""))
        assertFalse("英文页不应混入中文", html.any { it in '一'..'鿿' })
    }

    /** 非法百分号编码（如 `%zz`）不能把请求打挂：解码失败回退空 SSID，仍返回可用的描述文件。 */
    @Test(timeout = 20000) fun `SSID百分号编码非法时应回退空串而非报错`() {
        val (head, body) = get(server { "pac" }, "/hxmy.mobileconfig?ssid=%zz")
        assertEquals(200, head.status)
        assertEquals("application/x-apple-aspen-config", head.value("Content-Type"))
        assertTrue(String(body).isNotEmpty())
    }

    /** 完全没有 ssid 参数（用户手输地址）也要正常出文件，而不是 500/404。 */
    @Test(timeout = 20000) fun `缺少ssid参数时仍应返回描述文件`() {
        val (head, _) = get(server { "pac" }, "/hxmy.mobileconfig?foo=bar")
        assertEquals(200, head.status)
    }

    // ---- 辅助 ----

    private fun server(
        httpProxyPort: () -> Int? = { null },
        pac: () -> String,
    ): ProxyServer {
        val s = PacServer(Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(), httpProxyPort, pac)
        s.start(scope, 0)
        res.onClose { s.stop() }
        return s
    }

    /** 发一次 GET 并读回（头, 体）；每次新连接（PAC 服务对每个请求都 Connection: close）。 */
    private fun get(server: ProxyServer, path: String): Pair<HttpHead, ByteArray> {
        val sock = res.keep(ProxyTestKit.client(ProxyTestKit.awaitPort(server)))
        val head = ProxyTestKit.sendAndReadHead(sock, "GET $path HTTP/1.1\r\nHost: x\r\n\r\n")
        return head to ProxyTestKit.readBody(sock.getInputStream(), head)
    }
}
