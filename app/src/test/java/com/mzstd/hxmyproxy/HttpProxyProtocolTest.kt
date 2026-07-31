package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.HttpProxyServer
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.rules.RuleEngine
import com.mzstd.hxmyproxy.core.rules.RuleMatcher
import com.mzstd.hxmyproxy.core.security.AllowAllAccessController
import com.mzstd.hxmyproxy.core.security.Authenticator
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.security.NoAuthAuthenticator
import com.mzstd.hxmyproxy.core.security.SingleCredentialAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.Base64

/**
 * HTTP 代理的**错误路径与协议边界**：畸形请求行、CONNECT 目标非法、认证失败的各种形态、
 * 上游不可达、规则拦截，以及转发时的头改写。
 *
 * 这些路径共同的用户可感知失效形态是：浏览器一直转圈（代理挂住不回）或整条连接被莫名重置，
 * 而不是收到一个能解释原因的状态码。所以每个用例都断言「回了哪个码 + 连接怎么收场」。
 */
class HttpProxyProtocolTest {

    private val res = Resources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    @After fun tearDown() {
        res.close()
        scope.cancel()
    }

    // ---- CONNECT / 请求行边界 ----

    /** 请求行不足三段（如只有 "GET /"）：必须回 400，而不是数组越界崩掉整条连接。 */
    @Test(timeout = 20000) fun `请求行少于三段应回400`() {
        val head = exchange(proxy(), "GET /\r\n\r\n")
        assertEquals(400, head.status)
    }

    /** 首个请求就是空行（有些扫描器/半开连接会这样）：回 400 并收场，不能当成合法请求继续读。 */
    @Test(timeout = 20000) fun `首请求为空行应回400`() {
        val head = exchange(proxy(), "\r\n")
        assertEquals(400, head.status)
    }

    /** CONNECT 端口是必填的（RFC 7231 authority-form）；缺端口不能猜 443，应回 400。 */
    @Test(timeout = 20000) fun `CONNECT缺端口应回400`() {
        val head = exchange(proxy(), "CONNECT example.com HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertEquals(400, head.status)
    }

    /** 端口越界（>65535）同样是非法 authority，回 400；若不校验会在建连处抛异常、客户端只看到连接断。 */
    @Test(timeout = 20000) fun `CONNECT端口越界应回400`() {
        val head = exchange(proxy(), "CONNECT example.com:70000 HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(400, head.status)
    }

    /** 代理必须收到 absolute-form；客户端误把代理当源站（origin-form）时回 400 而非空转。 */
    @Test(timeout = 20000) fun `普通请求用origin-form应回400`() {
        val head = exchange(proxy(), "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertEquals(400, head.status)
    }

    /** URI 语法非法（未闭合的 IPv6 方括号）会让 URI() 抛异常——必须被吃掉转成 400，不能冒泡。 */
    @Test(timeout = 20000) fun `绝对URI语法非法应回400`() {
        val head = exchange(proxy(), "GET http://[bad/ HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(400, head.status)
    }

    // ---- 认证 ----

    /** 认证开启时**普通 HTTP 转发**也要挡（此前只有 CONNECT 有回归测试）：407 且带 Proxy-Authenticate。 */
    @Test(timeout = 20000) fun `启用认证时普通HTTP请求应回407并带认证挑战`() {
        val head = exchange(
            proxy(auth = SingleCredentialAuthenticator("u", "p", enabled = true)),
            "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n",
        )
        assertEquals(407, head.status)
        assertTrue("407 必须带挑战头，否则客户端不会弹出/重发凭据", head.value("Proxy-Authenticate")!!.startsWith("Basic"))
    }

    /** 只支持 Basic：Bearer 之类的方案不得被当作已认证放行。 */
    @Test(timeout = 20000) fun `Proxy-Authorization非Basic方案应回407`() {
        val head = exchange(
            proxy(auth = SingleCredentialAuthenticator("u", "p", enabled = true)),
            "CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Bearer tok\r\n\r\n",
        )
        assertEquals(407, head.status)
    }

    /** base64 损坏会让解码抛异常：必须转成 407，而不是把异常抛穿导致连接被重置。 */
    @Test(timeout = 20000) fun `Basic凭据base64损坏应回407`() {
        val head = exchange(
            proxy(auth = SingleCredentialAuthenticator("u", "p", enabled = true)),
            "CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic !!!not-base64!!!\r\n\r\n",
        )
        assertEquals(407, head.status)
    }

    /** 解出来没有冒号 → 无法切分用户名/密码，必须拒（否则可能把整串当用户名、空密码放行）。 */
    @Test(timeout = 20000) fun `Basic凭据缺冒号应回407`() {
        val raw = Base64.getEncoder().encodeToString("useronly".toByteArray())
        val head = exchange(
            proxy(auth = SingleCredentialAuthenticator("u", "p", enabled = true)),
            "CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: Basic $raw\r\n\r\n",
        )
        assertEquals(407, head.status)
    }

    /** 密码错误 → 407（不是 403/200）：客户端据此重新提示输入密码。 */
    @Test(timeout = 20000) fun `Basic凭据错误应回407`() {
        val head = exchange(
            proxy(auth = SingleCredentialAuthenticator("u", "p", enabled = true)),
            "CONNECT example.com:443 HTTP/1.1\r\nProxy-Authorization: ${basic("u", "WRONG")}\r\n\r\n",
        )
        assertEquals(407, head.status)
    }

    /** 凭据正确 → 隧道真的能通（守护 Basic 解码这一段：解错了会表现成「密码明明对却一直 407」）。 */
    @Test(timeout = 20000) fun `Basic凭据正确应放行CONNECT并可收发`() {
        val echo = res.keep(ProxyTestKit.startEcho())
        val server = proxy(auth = SingleCredentialAuthenticator("u", "p", enabled = true))
        val sock = connect(server)
        val head = ProxyTestKit.sendAndReadHead(
            sock,
            "CONNECT 127.0.0.1:${echo.localPort} HTTP/1.1\r\n" +
                "Proxy-Authorization: ${basic("u", "p")}\r\n\r\n",
        )
        assertEquals(200, head.status)
        ProxyTestKit.write(sock, "ping")
        assertEquals("ping", String(ProxyTestKit.readN(sock.getInputStream(), 4)))
    }

    // ---- 上游失败 ----

    /** 上游拒连（端口无人监听）：CONNECT 必须回 502，客户端才知道是上游的问题而非代理挂了。 */
    @Test(timeout = 20000) fun `上游拒绝连接时CONNECT应回502`() {
        val head = exchange(proxy(), "CONNECT 127.0.0.1:${ProxyTestKit.deadPort()} HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(502, head.status)
    }

    /** 同上，普通转发路径独立实现了一遍错误映射，也必须回 502（曾各写各的容易漏）。 */
    @Test(timeout = 20000) fun `上游拒绝连接时普通转发应回502`() {
        val head = exchange(
            proxy(),
            "GET http://127.0.0.1:${ProxyTestKit.deadPort()}/ HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n",
        )
        assertEquals(502, head.status)
    }

    // ---- 规则拦截 ----

    /** 规则判 REJECT：回 403 且**不建上游连接**（广告拦截的整个价值就在于不发出这次请求）。 */
    @Test(timeout = 20000) fun `规则判REJECT时CONNECT应回403`() {
        val head = exchange(
            proxy(rules = rejecting("blocked.example")),
            "CONNECT blocked.example:443 HTTP/1.1\r\nHost: blocked.example\r\n\r\n",
        )
        assertEquals(403, head.status)
    }

    @Test(timeout = 20000) fun `规则判REJECT时普通HTTP应回403`() {
        val head = exchange(
            proxy(rules = rejecting("blocked.example")),
            "GET http://blocked.example/ad.js HTTP/1.1\r\nHost: blocked.example\r\nConnection: close\r\n\r\n",
        )
        assertEquals(403, head.status)
    }

    /** 未命中规则的域名不受影响（守护「拦截表不误伤」——只测拦到会漏掉过度拦截的回归）。 */
    @Test(timeout = 20000) fun `未命中拦截规则的目标应正常转发`() {
        val origin = res.keep(RecordingOrigin())
        val server = proxy(rules = rejecting("blocked.example"))
        val head = exchange(
            server,
            "GET http://127.0.0.1:${origin.port}/ok HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\nConnection: close\r\n\r\n",
        )
        assertEquals(200, head.status)
        assertNotNull("上游应真的收到了请求", origin.nextRequest())
    }

    // ---- 转发时的头改写 ----

    /**
     * 转给上游的必须是 origin-form（带 query）、保留 Host、剥掉 Proxy-* 与逐跳头、强制 Connection: close。
     * 写错的表现：源站看到 absolute-form 而返回 400，或代理凭据被泄漏给上游站点。
     */
    @Test(timeout = 20000) fun `转发到上游应改写为origin-form并剥离代理专用头`() {
        val origin = res.keep(RecordingOrigin())
        val head = exchange(
            proxy(),
            "GET http://127.0.0.1:${origin.port}/a/b?x=1&y=2 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${origin.port}\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Proxy-Authorization: ${basic("u", "p")}\r\n" +
                "X-Keep: 1\r\n" +
                "Connection: close\r\n\r\n",
        )
        assertEquals(200, head.status)
        val req = origin.nextRequest() ?: throw AssertionError("上游没收到请求")
        assertEquals("GET /a/b?x=1&y=2 HTTP/1.1", req.line)
        assertEquals("127.0.0.1:${origin.port}", req.value("Host"))
        assertFalse("Proxy-Authorization 泄漏给源站 = 把代理密码送出去了", req.has("Proxy-Authorization"))
        assertFalse("Proxy-Connection 属逐跳头，不应转发", req.has("Proxy-Connection"))
        assertTrue("非逐跳头必须原样透传", req.has("X-Keep"))
        assertEquals("对上游强制 close 以获得干净的响应定界", "close", req.value("Connection"))
    }

    /** 客户端没给 Host（HTTP/1.0 风格）时，代理要据目标补齐，否则源站按虚拟主机会返回错站点/400。 */
    @Test(timeout = 20000) fun `客户端缺Host头时应据目标补齐`() {
        val origin = res.keep(RecordingOrigin())
        val head = exchange(
            proxy(),
            "GET http://127.0.0.1:${origin.port}/ HTTP/1.1\r\nConnection: close\r\n\r\n",
        )
        assertEquals(200, head.status)
        val req = origin.nextRequest() ?: throw AssertionError("上游没收到请求")
        assertEquals("127.0.0.1:${origin.port}", req.value("Host"))
    }

    /**
     * 上游响应里的逐跳头必须被剥掉、由代理写自己的 Connection——否则上游的
     * `Connection: keep-alive` 会和代理的决定打架，客户端可能永远等一个不会再来的响应。
     */
    @Test(timeout = 20000) fun `上游响应的逐跳头应被剥离且Connection由代理决定`() {
        val origin = res.keep(
            RecordingOrigin(
                extraHeaders = listOf(
                    "Connection" to "keep-alive",
                    "Keep-Alive" to "timeout=5",
                    "X-Origin" to "yes",
                ),
            ),
        )
        val head = exchange(
            proxy(),
            "GET http://127.0.0.1:${origin.port}/ HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        assertEquals(200, head.status)
        assertEquals("Connection 只能有一份（代理自己的那份）", 1, head.count("Connection"))
        assertEquals("close", head.value("Connection"))
        assertFalse("Keep-Alive 属逐跳头，不应透给客户端", head.has("Keep-Alive"))
        assertEquals("非逐跳头必须原样回给客户端", "yes", head.value("X-Origin"))
    }

    /** keep-alive 复用连接上的第二个请求同样要走完整校验：畸形请求行照样回 400 而不是被静默丢弃。 */
    @Test(timeout = 20000) fun `keep-alive连接上的第二个请求畸形应回400`() {
        val origin = res.keep(RecordingOrigin())
        val sock = connect(proxy())
        val first = ProxyTestKit.sendAndReadHead(
            sock,
            "GET http://127.0.0.1:${origin.port}/1 HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n\r\n",
        )
        assertEquals(200, first.status)
        ProxyTestKit.readBody(sock.getInputStream(), first)   // 排空响应体，才轮到下一个请求
        assertEquals("keep-alive", first.value("Connection"))

        val second = ProxyTestKit.sendAndReadHead(sock, "BAD\r\n\r\n")
        assertEquals(400, second.status)
    }

    // ---- 辅助 ----

    private fun proxy(
        auth: Authenticator = NoAuthAuthenticator,
        rules: RuleEngine? = null,
    ): ProxyServer {
        val server = HttpProxyServer(
            Dispatchers.IO, AllowAllAccessController, ConnectionRegistry(),
            OutboundConnector(allowAll), RelayEngine(), { auth }, { ConnectionLimits() },
            Dispatchers.IO, ruleEngine = rules,
        )
        server.start(scope, 0)
        res.onClose { server.stop() }
        return server
    }

    private fun rejecting(host: String): RuleEngine = RuleEngine().apply {
        update(RuleEngine.Snapshot(userReject = RuleMatcher().apply { add(host) }))
    }

    private fun connect(server: ProxyServer): Socket =
        res.keep(ProxyTestKit.client(ProxyTestKit.awaitPort(server)))

    private fun exchange(server: ProxyServer, request: String): HttpHead =
        ProxyTestKit.sendAndReadHead(connect(server), request)

    private fun basic(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
}
