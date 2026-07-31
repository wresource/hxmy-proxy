package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.proxy.ConnectionRegistry
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyServer
import com.mzstd.hxmyproxy.core.proxy.RelayEngine
import com.mzstd.hxmyproxy.core.proxy.Socks5ProxyServer
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
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * SOCKS5（RFC1928/RFC1929）的**协议错误与边界**：版本号、方法协商、子协商、命令与地址类型。
 *
 * 这些路径写错的用户可感知形态：客户端（Chrome/Telegram/系统级 SOCKS）连上后一直转圈——
 * 因为服务端既不回复也不断开。所以每个用例都断言「回了规范的 REP 码」或「明确断开」二选一，
 * 绝不允许第三种结局（挂着）。
 */
class Socks5ProtocolTest {

    private val res = Resources()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    @After fun tearDown() {
        res.close()
        scope.cancel()
    }

    // ---- greeting / 方法协商 ----

    /** 版本号不是 5：直接断开，且**一个字节都不回**（回了反而会让对端把垃圾当成 SOCKS4 响应解析）。 */
    @Test(timeout = 20000) fun `greeting版本非5应直接断开`() {
        val sock = connect(socks())
        ProxyTestKit.write(sock, byteArrayOf(0x04, 0x01, 0x00))
        assertTrue("非 SOCKS5 应断开连接", ProxyTestKit.closedByPeer(sock))
    }

    /** NMETHODS=0 是畸形 greeting（后面没有方法列表可读）：断开，不能傻等 0 字节。 */
    @Test(timeout = 20000) fun `greeting方法数为0应断开`() {
        val sock = connect(socks())
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x00))
        assertTrue(ProxyTestKit.closedByPeer(sock))
    }

    /** 开了认证但客户端只肯 NO_AUTH → 回 0xFF（无可接受方法），不得降级放行。 */
    @Test(timeout = 20000) fun `启用认证但客户端只提供无认证方法应回0xFF`() {
        val sock = connect(socks(auth = SingleCredentialAuthenticator("u", "p", enabled = true)))
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x00))
        val r = ProxyTestKit.readN(sock.getInputStream(), 2)
        assertEquals(0x05, r[0].toInt() and 0xFF)
        assertEquals(0xFF, r[1].toInt() and 0xFF)
    }

    /**
     * 没开认证、但客户端只提供「用户名/密码」方法 → 同样回 0xFF。
     * 反面（服务端擅自改用 NO_AUTH 回应）会让严格客户端认为协商结果与自己提供的不符而中断。
     */
    @Test(timeout = 20000) fun `未启用认证但客户端只提供用户名密码方法应回0xFF`() {
        val sock = connect(socks())
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x02))
        val r = ProxyTestKit.readN(sock.getInputStream(), 2)
        assertEquals(0xFF, r[1].toInt() and 0xFF)
    }

    /** 客户端两种方法都提供时，选哪个只由**服务端是否开认证**决定。 */
    @Test(timeout = 20000) fun `方法协商应按服务端认证开关选择`() {
        val open = connect(socks())
        ProxyTestKit.write(open, byteArrayOf(0x05, 0x02, 0x00, 0x02))
        assertEquals("未开认证应选 NO_AUTH", 0x00, ProxyTestKit.readN(open.getInputStream(), 2)[1].toInt() and 0xFF)

        val guarded = connect(socks(auth = SingleCredentialAuthenticator("u", "p", enabled = true)))
        ProxyTestKit.write(guarded, byteArrayOf(0x05, 0x02, 0x00, 0x02))
        assertEquals("开了认证应选 USER/PASS", 0x02, ProxyTestKit.readN(guarded.getInputStream(), 2)[1].toInt() and 0xFF)
    }

    /** RFC1929 子协商版本必须是 0x01；不是就断开（不能把后续字节错位当成用户名长度继续读）。 */
    @Test(timeout = 20000) fun `子协商版本非1应断开`() {
        val sock = connect(socks(auth = SingleCredentialAuthenticator("u", "p", enabled = true)))
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x02))
        ProxyTestKit.readN(sock.getInputStream(), 2)
        ProxyTestKit.write(sock, byteArrayOf(0x02, 0x01, 'u'.code.toByte(), 0x01, 'p'.code.toByte()))
        assertTrue(ProxyTestKit.closedByPeer(sock))
    }

    // ---- request 阶段 ----

    /** request 阶段版本号仍须是 5（协商完不代表后面可以乱来）：不是就断开。 */
    @Test(timeout = 20000) fun `request阶段版本非5应断开`() {
        val sock = greeted(socks())
        ProxyTestKit.write(sock, byteArrayOf(0x04, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50))
        assertTrue(ProxyTestKit.closedByPeer(sock))
    }

    /** 未知地址类型 → REP=0x08（address type not supported），这是 RFC 规定的码，客户端据此报错。 */
    @Test(timeout = 20000) fun `不支持的地址类型应回REP08`() {
        val sock = greeted(socks())
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x00, 0x07))
        assertEquals(0x08, replyCode(sock))
    }

    /** V1 只支持 CONNECT：BIND(0x02) 必须回 REP=0x07（command not supported）而不是静默忽略。 */
    @Test(timeout = 20000) fun `BIND命令应回REP07`() {
        val sock = greeted(socks())
        ProxyTestKit.write(sock, request(cmd = 0x02, port = 80))
        assertEquals(0x07, replyCode(sock))
    }

    /** UDP ASSOCIATE(0x03) 同样不支持 → REP=0x07。 */
    @Test(timeout = 20000) fun `UDP_ASSOCIATE命令应回REP07`() {
        val sock = greeted(socks())
        ProxyTestKit.write(sock, request(cmd = 0x03, port = 80))
        assertEquals(0x07, replyCode(sock))
    }

    /** 域名长度 0 是畸形请求：断开，不能拿空域名去解析。 */
    @Test(timeout = 20000) fun `域名长度为0应断开`() {
        val sock = greeted(socks())
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x00, 0x03, 0x00))
        assertTrue(ProxyTestKit.closedByPeer(sock))
    }

    /** 上游拒连 → REP=0x05（connection refused）。映射错会让客户端把「对端没开端口」当成代理坏了。 */
    @Test(timeout = 20000) fun `上游拒绝连接应回REP05`() {
        val sock = greeted(socks())
        ProxyTestKit.write(sock, request(cmd = 0x01, port = ProxyTestKit.deadPort()))
        assertEquals(0x05, replyCode(sock))
    }

    /** 规则判 REJECT → REP=0x02（not allowed by ruleset），且不去建上游连接。 */
    @Test(timeout = 20000) fun `规则判REJECT应回REP02`() {
        val sock = greeted(socks(rules = rejecting("blocked.example")))
        ProxyTestKit.write(sock, domainRequest("blocked.example", 443))
        assertEquals(0x02, replyCode(sock))
    }

    /** ATYP=域名（0x03）这条路径由代理自己解析目标——它与 IP 直连是两段不同代码，各需一条端到端验证。 */
    @Test(timeout = 20000) fun `域名形式的CONNECT应能连通`() {
        val echo = res.keep(ProxyTestKit.startEcho())
        val sock = greeted(socks())
        ProxyTestKit.write(sock, domainRequest("localhost", echo.localPort))
        assertEquals(0x00, replyCode(sock))
        ProxyTestKit.write(sock, "hello")
        assertEquals("hello", String(ProxyTestKit.readN(sock.getInputStream(), 5)))
    }

    /**
     * ATYP=IPv6（0x04）必须读满 **16** 字节地址——只读 4 字节的话后续端口会整体错位，
     * 表现为「IPv6 目标随机连到别的端口」。用真实 ::1 回环端到端验证读取长度正确。
     */
    @Test(timeout = 20000) fun `IPv6地址应完整读取16字节`() {
        val echo6 = try {
            ProxyTestKit.startEcho(InetAddress.getByName("::1"))
        } catch (e: Exception) {
            null
        }
        Assume.assumeTrue("本机没有 IPv6 回环，跳过", echo6 != null)
        res.keep(echo6!!)

        val sock = greeted(socks())
        val port = echo6.localPort
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(0x05, 0x01, 0x00, 0x04))
        body.write(InetAddress.getByName("::1").address)     // 16 字节
        body.write(byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
        ProxyTestKit.write(sock, body.toByteArray())
        assertEquals(0x00, replyCode(sock))
        ProxyTestKit.write(sock, "v6")
        assertEquals("v6", String(ProxyTestKit.readN(sock.getInputStream(), 2)))
    }

    // ---- 辅助 ----

    private fun socks(
        auth: Authenticator = NoAuthAuthenticator,
        rules: RuleEngine? = null,
    ): ProxyServer {
        val server = Socks5ProxyServer(
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

    /** 连接并完成 NO_AUTH 协商，停在 request 阶段。 */
    private fun greeted(server: ProxyServer): Socket {
        val sock = connect(server)
        ProxyTestKit.write(sock, byteArrayOf(0x05, 0x01, 0x00))
        assertEquals(0x00, ProxyTestKit.readN(sock.getInputStream(), 2)[1].toInt() and 0xFF)
        return sock
    }

    /** VER CMD RSV ATYP=IPv4 127.0.0.1 PORT。 */
    private fun request(cmd: Int, port: Int): ByteArray = byteArrayOf(
        0x05, cmd.toByte(), 0x00, 0x01, 127, 0, 0, 1,
        (port shr 8).toByte(), (port and 0xFF).toByte(),
    )

    /** VER CMD=CONNECT RSV ATYP=域名 LEN HOST PORT。 */
    private fun domainRequest(host: String, port: Int): ByteArray {
        val h = host.toByteArray(Charsets.US_ASCII)
        val b = ByteArrayOutputStream()
        b.write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
        b.write(h.size)
        b.write(h)
        b.write(byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
        return b.toByteArray()
    }

    /** 读 10 字节 reply，返回 REP 码。 */
    private fun replyCode(sock: Socket): Int {
        val r = ProxyTestKit.readN(sock.getInputStream(), 10)
        assertEquals("reply 的版本位必须是 5", 0x05, r[0].toInt() and 0xFF)
        return r[1].toInt() and 0xFF
    }
}
