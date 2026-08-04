package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.security.EgressGuard
import com.mzstd.hxmyproxy.core.stats.EgressKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class OutboundConnectorTest {

    private val allowAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }

    @Test(timeout = 10000) fun ordersIpv4First() {
        val c = OutboundConnector(allowAll)
        val v6a = InetAddress.getByName("::1")
        val v4 = InetAddress.getByName("127.0.0.1")
        val v6b = InetAddress.getByName("2001:db8::1")
        val ordered = c.orderAddresses(listOf(v6a, v4, v6b))
        assertTrue("第一个应为 IPv4", ordered.first() is Inet4Address)
        assertEquals(v4, ordered.first())
    }

    @Test(timeout = 10000) fun skipsEgressBlockedAddressToReachable() = runBlocking {
        val echo = startEcho()
        // 阻断 10.1.2.3，放行 loopback —— 被护栏拦的地址不参与连接
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = addr.hostAddress != "10.1.2.3" }
        val c = OutboundConnector(guard)
        val s = c.connectAny(
            listOf(InetAddress.getByName("10.1.2.3"), InetAddress.getByName("127.0.0.1")),
            echo.localPort,
        )
        assertTrue(s.isConnected)
        s.getOutputStream().write("hi".toByteArray()); s.getOutputStream().flush()
        val b = ByteArray(2); s.getInputStream().read(b)
        assertEquals("hi", String(b))
        s.close(); echo.close()
    }

    /**
     * Happy Eyeballs：首地址是 TEST-NET-1（RFC 5737，不可路由→黑洞），次地址是 echo。
     * 应在 ~250ms 交错后连上 echo，**远早于单地址 5s 连接超时**——@Test timeout 4500ms 即证明没有干等首地址超时。
     */
    @Test(timeout = 4500) fun racesPastUnreachableFirstToReachableSecond() = runBlocking {
        val echo = startEcho()
        val c = OutboundConnector(allowAll)
        val s = c.connectAny(
            listOf(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("127.0.0.1")),
            echo.localPort,
        )
        assertTrue(s.isConnected)
        // 确认胜出的是 echo（次地址），不是黑洞首地址。
        assertEquals(echo.localPort, (s.remoteSocketAddress as InetSocketAddress).port)
        s.soTimeout = 2000
        s.getOutputStream().write("ok".toByteArray()); s.getOutputStream().flush()
        val b = ByteArray(2); s.getInputStream().read(b)
        assertEquals("ok", String(b))
        s.close(); echo.close()
    }

    @Test(timeout = 10000) fun emptyAddressesThrows() = runBlocking {
        try { OutboundConnector(allowAll).connectAny(emptyList(), 80); fail("应抛 ProxyException") }
        catch (e: ProxyException) { /* ok */ }
    }

    @Test(timeout = 10000) fun allBlockedThrows() = runBlocking {
        val guard = object : EgressGuard { override fun isAllowed(addr: InetAddress) = false }
        try { OutboundConnector(guard).connectAny(listOf(InetAddress.getByName("127.0.0.1")), 9); fail("应抛 ProxyException") }
        catch (e: ProxyException) { /* ok */ }
    }

    // ================================================================================
    // 以下为补充用例。覆盖面刻意限定在**不依赖真实 Android 网络栈**的三块语义上：
    //   1) 出口选择（bypass=DIRECT 的 fail-closed / PROXY 的降级）——纯分支逻辑，只需要一个
    //      UnderlyingNetworkProvider 替身就能全部走到，是「豆包国家不符合」那类泄漏的最后一道闸；
    //   2) DNS 缓存与失败路径——用一个**计数调度器**当探针：resolve() 每次真查 DNS 都会
    //      withContext(dnsDispatcher)，命中缓存则连派发都不发生，于是缓存是否生效变得可断言；
    //   3) Happy Eyeballs 编排的错因与资源边界——用本机 loopback / RFC5737 黑洞地址真跑。
    // 绑定 Network、DoH 走真实 HTTPS 这两块无法在 JVM 里如实验证，故意不写用例（写了也是假通过：
    // android.jar 是 stub 且 isReturnDefaultValues=true，org.json 会静默返回默认值）。
    // ================================================================================

    /**
     * IPv4 优先之外，**同族内必须保持传入顺序**（sortedBy 是稳定排序）。
     * 为什么重要：resolve() 合并结果时把系统解析放前面、DoH 放后面（DoH 是兜底），而
     * connectAny 又只取前 MAX_HE_CANDIDATES 个地址竞速。若排序不稳定，靠前的优选地址会被
     * 挤出扇出窗口——表现成「偶发地某些站点首屏特别慢」，且完全无从复现。
     */
    @Test fun `IPv4 优先且同族内保持传入顺序`() {
        val c = OutboundConnector(allowAll)
        val v4a = InetAddress.getByName("203.0.113.1")
        val v4b = InetAddress.getByName("203.0.113.2")
        val v6a = InetAddress.getByName("2001:db8::1")
        val v6b = InetAddress.getByName("2001:db8::2")
        assertEquals(
            listOf(v4a, v4b, v6a, v6b),
            c.orderAddresses(listOf(v6a, v4a, v6b, v4b)),
        )
    }

    /**
     * 候选为空有两种成因，必须给出**不同**的错因：解析不出地址=DnsFailure，全被出口护栏拦=AccessDenied。
     * 错了会表现成：诊断页/客户端把「护栏拒绝」显示成「DNS 失败」（或反之），SOCKS5 回码也从
     * 0x02 变成 0x04 —— 用户照着「DNS 失败」去换 DNS、关备用 DNS 折腾半天，而真凶是禁私网开关。
     */
    @Test(timeout = 10000) fun `候选为空时区分「解析不出」与「被护栏全拦」两种错因`() = runBlocking {
        val e1 = runCatching { OutboundConnector(allowAll).connectAny(emptyList(), 80) }.exceptionOrNull()
        assertTrue("空地址应抛 ProxyException，实际：$e1", e1 is ProxyException)
        assertSame("空地址的错因应是 DNS 失败", ProxyError.DnsFailure, (e1 as ProxyException).error)

        val denyAll = object : EgressGuard { override fun isAllowed(addr: InetAddress) = false }
        val e2 = runCatching {
            OutboundConnector(denyAll).connectAny(listOf(InetAddress.getByName("127.0.0.1")), 80)
        }.exceptionOrNull()
        assertTrue("全被拦应抛 ProxyException，实际：$e2", e2 is ProxyException)
        assertSame("被护栏拦下的错因应是 AccessDenied", ProxyError.AccessDenied, (e2 as ProxyException).error)
    }

    /**
     * 目标端口无人监听 ⇒ ConnectException ⇒ 必须映射成 [ProxyError.ConnectionRefused]。
     * 错了（落进 Unknown）会表现成：SOCKS5 回 0x01「一般性失败」而不是 0x05，客户端与「死因归因」
     * 统计都只能显示「未知错误」——而这恰恰是最常见、最该一眼看出的一种失败。
     */
    @Test(timeout = 10000) fun `端口无人监听时错因是「连接被拒绝」而不是未知错误`() = runBlocking {
        // 先占一个端口再立刻释放：拿到一个几乎必然无人监听的端口号。
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val e = runCatching {
            OutboundConnector(allowAll).connectAny(listOf(InetAddress.getByName("127.0.0.1")), deadPort)
        }.exceptionOrNull()
        assertTrue("应抛 ProxyException，实际：$e", e is ProxyException)
        assertSame(ProxyError.ConnectionRefused, (e as ProxyException).error)
    }

    /**
     * Happy Eyeballs 的**扇出上限**（MAX_HE_CANDIDATES=6）：超出的地址一个都不该被尝试。
     * 这里前 6 个是 RFC5737 黑洞地址、第 7 个是本机 echo——若上限失效，echo 一定会收到连接。
     * 错了会表现成：个别 anycast/CDN 返回十几条 A 记录时，单个域名就并发拉起十几条建连，
     * 首屏几十个域名叠加即线程与 FD 风暴（这正是当初加上限的原因）。
     * 注：改动 MAX_HE_CANDIDATES 时本用例需要同步调整——它就是用来把这个取舍钉住的。
     */
    @Test(timeout = 30000) fun `候选超过扇出上限时多出来的地址一个都不会被尝试`() = runBlocking {
        val echo = CountingEcho()
        try {
            val blackholes = (1..6).map { InetAddress.getByName("192.0.2.$it") }
            val r = runCatching {
                OutboundConnector(allowAll).connectAny(blackholes + InetAddress.getByName("127.0.0.1"), echo.port)
            }
            r.getOrNull()?.close()
            delay(300)   // 给 accept 线程留出把「本不该发生的连接」记上一笔的时间
            assertEquals("第 7 个地址被尝试了 —— 扇出上限失效", 0, echo.accepted.get())
            assertTrue("6 个不可达地址全部失败后应抛 ProxyException，实际：${r.exceptionOrNull()}",
                r.exceptionOrNull() is ProxyException)
        } finally { echo.close() }
    }

    /**
     * 截断**不得把某一族整体挤出候选池** —— 上一条用例的镜像，也是本组里唯一能抓住那个缺陷的方向。
     *
     * 前 6 个是 RFC5737 黑洞（IPv4）、第 7 个是 IPv6 上的 echo。旧实现按 IPv4 优先排序后一刀切前 6，
     * **IPv6 被整体删除**，于是 6 个黑洞全超时、连接彻底失败；而它本该是 IPv4 全挂时的救命稻草。
     * 依据 RFC 8305 §3.1「必须截断时每族至少留一个」。
     *
     * 方向不能反过来写成「6 个 v6 黑洞 + 1 个 v4 echo」：那样 IPv4 优先排序会把 echo 顶到 index 0，
     * 改动前后都必然连上，用例恒绿、证明不了任何东西。
     */
    @Test(timeout = 30000) fun `截断不得把 IPv6 整族挤出候选池`() = runBlocking {
        val echo = try {
            CountingEcho("::1")
        } catch (e: Exception) {
            // 无 IPv6 loopback 的环境（部分 CI 容器）跳过，而不是红——本用例测的是选址，不是本机有没有 v6。
            org.junit.Assume.assumeNoException("本机没有 IPv6 loopback，跳过", e)
            return@runBlocking
        }
        try {
            val blackholes = (1..6).map { InetAddress.getByName("192.0.2.$it") }
            val v6 = InetAddress.getByName("::1")
            val r = runCatching {
                OutboundConnector(allowAll).connectAny(blackholes + v6, echo.port)
            }
            r.getOrNull()?.close()
            delay(300)
            assertEquals(
                "IPv6 被截断整族丢掉了 —— IPv4 全是黑洞时它本该救场（RFC 8305 §3.1）",
                1, echo.accepted.get(),
            )
        } finally { echo.close() }
    }

    /** 按族配额截断的边界：单族不受影响、族内与族间顺序不变、次要族不得挤光主要族。 */
    @Test fun `按族配额截断的边界行为`() {
        val c = OutboundConnector(allowAll)
        val v4 = (1..8).map { InetAddress.getByName("203.0.113.$it") }
        val v6 = (1..3).map { InetAddress.getByName("2001:db8::$it") }

        // 未超限：原样返回，一个不动。
        assertEquals(v4.take(4), c.capByFamily(v4.take(4), max = 6))

        // 单族超限：没有族要保，仍是原样截断（钉住上一条「扇出上限」用例的行为不被本改动带偏）。
        assertEquals(v4.take(6), c.capByFamily(v4, max = 6))

        // 双族超限：次要族保底 1 个，且**顺序不变**（v4 仍全部在 v6 之前）。
        val mixed = v4 + v6
        val capped = c.capByFamily(mixed, max = 6)
        assertEquals(6, capped.size)
        assertEquals("次要族应恰好保底 1 个", 1, capped.count { it !is Inet4Address })
        assertEquals("主要族应拿到剩余名额", v4.take(5), capped.filterIsInstance<Inet4Address>())
        assertEquals("保留下来的地址必须维持原相对顺序", capped.sortedBy { mixed.indexOf(it) }, capped)

        // max=1 的极端：次要族名额不得挤光主要族，主要族至少留 1 个。
        assertEquals(listOf(v4[0]), c.capByFamily(mixed, max = 1))
    }

    /**
     * **指定出口时也必须走 DNS 缓存** —— 此前这是一段死代码。
     *
     * 缓存的读与写都写在 `network == null` 分支里，而 `egress=VPN`（→ vpnNet）与
     * `direct=WIFI`（→ wifiNet）都让 network 非空 ⇒ 用户一旦选了具体出口，
     * **100% 的流量都绕开缓存**，每条连接都要重新解析一次。0804 实测的卡死就是这么攒出来的：
     * 6 条并发打同一域名 = 6 个各自阻塞一条 dns 线程的独立解析任务。
     * 修法是把缓存键改成 (netId, host)、两个分支共用同一套读写。
     */
    @Test(timeout = 15000) fun `指定出口时也走 DNS 缓存`() = runBlocking {
        val echo = CountingEcho()
        val dns = CountingDispatcher()
        try {
            val net = mockk<Network>()
            every { net.networkHandle } returns 201L
            every { net.getAllByName(any()) } returns arrayOf(InetAddress.getByName("127.0.0.1"))
            every { net.socketFactory } returns SocketFactory.getDefault()
            val provider = mockk<UnderlyingNetworkProvider>()
            every { provider.egressNetwork() } returns net
            every { provider.current() } returns null

            val c = OutboundConnector(allowAll, dnsDispatcher = dns, underlyingNetworkProvider = provider)
            c.connect("cached.example", echo.port).close()
            val n1 = dns.dispatches.get()
            assertTrue("首次必须真的解析一次", n1 >= 1)
            c.connect("cached.example", echo.port).close()
            assertEquals("第二次应命中缓存，不该再派发解析", n1, dns.dispatches.get())
        } finally { echo.close() }
    }

    /**
     * **同域名并发解析必须去重（单飞）**。浏览器对一个域名开 6~8 条连接是常态，
     * 没有单飞就是 6~8 个独立解析任务各占一条 dns 线程；池只有 16 条，
     * 一个页面几十个域名即可填满，而排队无声无息（无日志、无指标、队列无界）。
     */
    @Test(timeout = 20000) fun `同域名并发解析只发一次`() = runBlocking {
        val echo = CountingEcho()
        val dns = CountingDispatcher()
        try {
            val c = OutboundConnector(allowAll, dnsDispatcher = dns)
            coroutineScope {
                repeat(6) { launch { runCatching { c.connect("localhost", echo.port).close() } } }
            }
            assertEquals("6 条并发对同一域名应只触发 1 次真实解析", 1, dns.dispatches.get())
        } finally { echo.close() }
    }

    /**
     * DNS 缓存：TTL 内同一域名只解析一次。首屏一个站点几十条连接全打同一域名，
     * 少了这层缓存就是几十次系统解析——DNS 慢或被污染时首屏直接卡住（Stripe/Claude 这类多域名站点重灾区）。
     * 探针：计数调度器统计「解析块被派发了几次」，命中缓存则一次派发都不会有。
     */
    @Test(timeout = 15000) fun `TTL 内同一域名只解析一次`() = runBlocking {
        val echo = CountingEcho()
        val dns = CountingDispatcher()
        try {
            val c = OutboundConnector(allowAll, dnsDispatcher = dns)
            c.connect("localhost", echo.port).close()
            val n1 = dns.dispatches.get()
            assertTrue("首次连接应真的做过一次解析", n1 >= 1)
            c.connect("localhost", echo.port).close()
            assertEquals("TTL 内第二次连接又去解析了一遍 —— DNS 缓存没生效", n1, dns.dispatches.get())
        } finally { echo.close() }
    }

    /**
     * [OutboundConnector.clearDnsCache] 必须真的让 TTL 内的条目立即失效。
     * 这个方法只在**网络切换**时被编排层调用：换了 WiFi 后旧网络下解析出的内网 IP 往往不可达，
     * 若清缓存变成空操作，用户会看到「切网后要等 30 秒才恢复」——而且没有任何报错，只是干等超时。
     */
    @Test(timeout = 15000) fun `clearDnsCache 后必须重新解析`() = runBlocking {
        val echo = CountingEcho()
        val dns = CountingDispatcher()
        try {
            val c = OutboundConnector(allowAll, dnsDispatcher = dns)
            c.connect("localhost", echo.port).close()
            val n1 = dns.dispatches.get()
            c.clearDnsCache()
            c.connect("localhost", echo.port).close()
            assertTrue("清缓存后没有重新解析 —— 换网后仍会用旧网络的 IP", dns.dispatches.get() > n1)
        } finally { echo.close() }
    }

    /**
     * 解析**失败**的结果绝不能进缓存。若把失败也缓存 30 秒，一次瞬时 DNS 抖动就变成该域名
     * 30 秒黑名单：用户刷新页面、切前后台都无效，只能干等——这类「自愈很慢」的体验最难排查。
     * （关掉备用 DNS 只是为了让失败路径不去打真实 DoH，保证用例快且不依赖外网。）
     */
    @Test(timeout = 20000) fun `解析失败不进缓存`() = runBlocking {
        val dns = CountingDispatcher()
        val c = OutboundConnector(allowAll, dnsDispatcher = dns)
        c.backupDnsEnabled = false
        val bad = "hxmy-no-such-host.invalid"   // RFC2606 保留 TLD，永不解析
        runCatching { c.connect(bad, 80) }
        val n1 = dns.dispatches.get()
        assertTrue("首次失败也应真的尝试过解析", n1 >= 1)
        runCatching { c.connect(bad, 80) }
        assertTrue("第二次没有重新解析 —— 失败结果被缓存成了黑名单", dns.dispatches.get() > n1)
    }

    /**
     * 解析全败时对外抛的必须是 [ProxyError.DnsFailure]，而不是让 [UnknownHostException] 裸奔上去。
     * 错了会表现成：SOCKS5 回 0x01 而非 0x04（host unreachable），HTTP 侧与「死因归因」统计
     * 全部归进「未知错误」，用户看到的诊断信息与真实原因（域名解析不出来）对不上。
     */
    @Test(timeout = 20000) fun `解析全败时抛 DnsFailure 而不是让 UnknownHostException 冒泡`() = runBlocking {
        val c = OutboundConnector(allowAll)
        c.backupDnsEnabled = false   // 不打真实 DoH：单测不依赖外网
        val e = runCatching { c.connect("hxmy-no-such-host.invalid", 80) }.exceptionOrNull()
        assertTrue("应抛 ProxyException，实际：$e", e is ProxyException)
        assertSame(ProxyError.DnsFailure, (e as ProxyException).error)
    }

    /**
     * **DIRECT(bypass) 的 fail-closed**：拿不到非 VPN 物理网络时，四个入口一律断开，
     * 绝不回落系统默认路由。这是本类最要命的一条语义——默认路由在 always-on/lockdown VPN 下
     * 正是那条 VPN，回落等于把「本要绕开 VPN 的直连流量」又原样送进 VPN（豆包「国家不符合」根因）。
     * 用例刻意把目标设成**真实可达**的本机 echo：一旦有人把 fail-closed 改成降级，连接会成功、
     * echo 会收到连接，两条断言都会红。同时验证 bypass 读的是 DIRECT 槽 current()，
     * 而不是 PROXY 槽 egressNetwork()（两个槽混用会让 DIRECT 流量走到用户为 PROXY 选的出口上）。
     */
    @Test(timeout = 15000) fun `DIRECT 拿不到物理网络时四个入口全部 fail-closed`() = runBlocking {
        val echo = CountingEcho()
        try {
            val provider = mockk<UnderlyingNetworkProvider>()
            every { provider.current() } returns null   // 仅 VPN 在线
            // egressNetwork() 故意不打桩：走错槽会立刻炸出 MockKException，与「正确 fail-closed」清晰可辨。
            val c = OutboundConnector(allowAll, underlyingNetworkProvider = provider)
            val loopback = InetAddress.getByName("127.0.0.1")
            // 连上了就顺手关掉并记 null——「没抛异常」本身就是失败信号（等于 DIRECT 流量被放了出去）。
            val errors = linkedMapOf<String, Throwable?>()
            suspend fun attempt(name: String, block: suspend () -> Closeable) {
                errors[name] = try { block().close(); null } catch (t: Throwable) { t }
            }
            attempt("connect(host)") { c.connect("localhost", echo.port, bypassVpn = true) }
            attempt("connect(addr)") { c.connect(loopback, echo.port, bypassVpn = true) }
            attempt("connectChannel(host)") { c.connectChannel("localhost", echo.port, bypassVpn = true) }
            attempt("connectChannel(addr)") { c.connectChannel(loopback, echo.port, bypassVpn = true) }
            for ((name, e) in errors) {
                assertTrue("$name 没有 fail-closed，而是把 DIRECT 流量放了出去", e != null)
                assertTrue("$name 抛的不是 ProxyException：$e", e is ProxyException)
                assertSame("$name 的错因应是 AccessDenied", ProxyError.AccessDenied, (e as ProxyException).error)
            }
            delay(300)
            assertEquals("fail-closed 时不该有任何连接真的发出去", 0, echo.accepted.get())
            verify(exactly = 4) { provider.current() }
            verify(exactly = 0) { provider.egressNetwork() }
        } finally { echo.close() }
    }

    /**
     * 反过来：**PROXY(非 bypass) 不 fail-closed**。拿不到用户指定的出口（AUTO 本就返回 null）时
     * 走系统默认路由把连接建起来——PROXY 场景「能上网」优先。
     * 错了（把 fail-closed 也套到 PROXY 上）会表现成：用户出口选 AUTO 就整体连不上网。
     * 同时验证 PROXY 路径**不**去读 DIRECT 槽 current()。
     */
    @Test(timeout = 15000) fun `PROXY 路径拿不到指定出口时走系统默认而不是断开`() = runBlocking {
        val echo = CountingEcho()
        try {
            val provider = mockk<UnderlyingNetworkProvider>()
            every { provider.egressNetwork() } returns null   // AUTO
            // current() 不打桩：PROXY 路径一旦误读 DIRECT 槽就会炸。
            val c = OutboundConnector(allowAll, underlyingNetworkProvider = provider)
            val s = c.connect(InetAddress.getByName("127.0.0.1"), echo.port)
            assertTrue(s.isConnected)
            assertEquals("hi", echoRoundTrip(s))
            s.close()
            verify(exactly = 0) { provider.current() }
        } finally { echo.close() }
    }

    /**
     * 出口归类：**没有归类器就什么都不上报**，由计量侧的默认值兜底。
     * 错了（缺席时顺手报个 OTHER 或按 bypassVpn 猜一个）会让历史流量表里出现凭空捏造的分档，
     * 而这张表用户是拿来判断「这个月蜂窝花了多少」的——宁可归进「其他」，不能假装知道。
     */
    @Test(timeout = 15000) fun `没有归类器时不上报出口`() = runBlocking {
        val echo = CountingEcho()
        try {
            var reported = 0
            val c = OutboundConnector(allowAll)   // egressClassifier = null
            c.connect(InetAddress.getByName("127.0.0.1"), echo.port, onEgress = { reported++ }).close()
            assertEquals("归类器缺席时不该上报任何出口", 0, reported)
        } finally { echo.close() }
    }

    /**
     * 上报的出口必须是**归类器对实际绑定网络的裁决**，不是调用方按 bypassVpn 猜的。
     * 这里 network=null（走系统默认），归类器把 null 判成 VPN——现实里正是如此：有 VPN 时
     * 本进程的默认路由就是那条 VPN。若哪天有人改成「bypassVpn=false 就记 VPN、true 就记 WIFI」，
     * 这条会红，而线上表现是历史流量表把隧道流量记成 Wi-Fi 直连。
     */
    @Test(timeout = 15000) fun `上报的是归类器对实际网络的裁决而不是按 bypassVpn 猜`() = runBlocking {
        val echo = CountingEcho()
        try {
            val kinds = mutableListOf<EgressKind>()
            val c = OutboundConnector(
                allowAll,
                egressClassifier = { n -> if (n == null) EgressKind.VPN else EgressKind.WIFI },
            )
            c.connect(InetAddress.getByName("127.0.0.1"), echo.port, onEgress = { kinds.add(it) }).close()
            assertEquals(listOf(EgressKind.VPN), kinds)
        } finally { echo.close() }
    }

    /**
     * **DNS 双路互援**：指定出口上解析失败要改用默认网络重解，而不是直接判 DNS 失败。
     * 错了会表现成：用户一选具体出口（Wi-Fi/蜂窝）就大面积「解析失败」，切回 AUTO 就好。
     *
     * 关键在于怎么与「外层降级」区分——两条路最后都会连上 echo，光断言「连上了」测不出东西。
     * 用出口归类当判别器：互援成功后连接仍绑在出口网络上（归类=WIFI）；若互援没生效而是外层
     * 降级默认路由，归类会是 VPN。断言 WIFI 就把「在哪一层被救回来的」钉死了。
     */
    @Test(timeout = 15000) fun `指定出口解析失败时改用默认网络解析且连接仍留在该出口`() = runBlocking {
        val echo = CountingEcho()
        try {
            val net = mockk<Network>()
            every { net.networkHandle } returns 101L   // 缓存键按 netId 分桶，mock 必须给
            every { net.getAllByName(any()) } throws UnknownHostException("模拟出口网络 DNS 失败")
            every { net.socketFactory } returns SocketFactory.getDefault()
            val provider = mockk<UnderlyingNetworkProvider>()
            every { provider.egressNetwork() } returns net
            every { provider.current() } returns null   // 本例只验「默认网络」这一路，物理网缺席

            val kinds = mutableListOf<EgressKind>()
            val c = OutboundConnector(
                allowAll,
                underlyingNetworkProvider = provider,
                egressClassifier = { n -> if (n == null) EgressKind.VPN else EgressKind.WIFI },
            )
            val s = c.connect("localhost", echo.port, onEgress = { kinds.add(it) })
            assertEquals("hi", echoRoundTrip(s))
            s.close()
            assertEquals("互援没在 resolve 内生效，连接被降级到了默认路由", listOf(EgressKind.WIFI), kinds)
        } finally { echo.close() }
    }

    /**
     * **0803 实证缺陷的回归**：出口网与「进程默认网络」**同源**失效时，物理网必须能救回解析。
     *
     * 现场是这样的：用户配 `egress=VPN`，系统 VPN 半死。第一路 `network.getAllByName` 死在 VPN 上；
     * 而第二路的「进程默认网络」**就是那条同样的 VPN**（app 的 uid 落在系统 VPN 的 uidrange 内），
     * 于是「双路互援」两路同源、必然一起失败，第三路 DoH 也走默认网络出去、同样死在那条 VPN 上。
     * 唯一活着的物理网在那个分支里一次都没被用到 —— 日志自证：同一个 accounts.google.com
     * 在该分支三路全败，VPN 句柄消失走到另一分支后被物理网一次救回。
     *
     * 用 `.invalid`（RFC 2606 保留 TLD）保证「默认网络」这一路真的解析不出来，
     * 只有物理网 mock 能给出地址。改动前这里必然是 DnsFailure。
     * 同时复用上一条的判别器：连接必须**仍绑在出口网上**（归类=WIFI），
     * 证明是在 resolve 内被救回的，而不是被外层降级到默认路由。
     */
    @Test(timeout = 15000) fun `出口网与默认网络同源失效时物理网必须能救回解析`() = runBlocking {
        val echo = CountingEcho()
        try {
            val host = "nonexistent-hxmy-probe.invalid"
            val egress = mockk<Network>()
            every { egress.networkHandle } returns 102L   // 缓存键按 netId 分桶，mock 必须给
            every { egress.getAllByName(any()) } throws UnknownHostException("模拟出口网(VPN) DNS 失败")
            every { egress.socketFactory } returns SocketFactory.getDefault()
            val phy = mockk<Network>()
            every { phy.networkHandle } returns 103L   // 缓存键按 netId 分桶，mock 必须给
            every { phy.getAllByName(host) } returns arrayOf(InetAddress.getByName("127.0.0.1"))
            val provider = mockk<UnderlyingNetworkProvider>()
            every { provider.egressNetwork() } returns egress
            every { provider.current() } returns phy

            val kinds = mutableListOf<EgressKind>()
            val c = OutboundConnector(
                allowAll,
                underlyingNetworkProvider = provider,
                egressClassifier = { n -> if (n == null) EgressKind.VPN else EgressKind.WIFI },
            ).apply { backupDnsEnabled = false }   // 单测不打真实 DoH
            val s = c.connect(host, echo.port, onEgress = { kinds.add(it) })
            assertEquals("hi", echoRoundTrip(s))
            s.close()
            assertEquals("连接必须仍绑在出口网上，而不是被外层降级", listOf(EgressKind.WIFI), kinds)
        } finally { echo.close() }
    }

    /**
     * 互援的另一半：指定出口与默认网络**双双**解析失败时，对外仍必须是 [ProxyError.DnsFailure]，
     * 且**不上报任何出口**（连都没连上，计量表里不该凭空多一档）。
     * 这条走的是 resolveLastResort 分支（与上面「默认网络主路失败」的合并分支是两段不同的代码）；
     * 错了会让「选了出口后域名解析不了」这种最常见的故障在诊断页显示成未知错误。
     * （关掉备用 DNS：单测不打真实 DoH。）
     */
    @Test(timeout = 20000) fun `指定出口与默认网络双双解析失败时判 DnsFailure`() = runBlocking {
        val net = mockk<Network>()
        every { net.networkHandle } returns 104L   // 缓存键按 netId 分桶，mock 必须给
        every { net.getAllByName(any()) } throws UnknownHostException("模拟出口网络 DNS 失败")
        val provider = mockk<UnderlyingNetworkProvider>()
        every { provider.egressNetwork() } returns net
        every { provider.current() } returns null   // 降级重试时的互援也拿不到底层网络

        var reported = 0
        val c = OutboundConnector(
            allowAll,
            underlyingNetworkProvider = provider,
            egressClassifier = { EgressKind.OTHER },
        )
        c.backupDnsEnabled = false
        val e = runCatching {
            c.connect("hxmy-no-such-host.invalid", 80, onEgress = { reported++ })
        }.exceptionOrNull()
        assertTrue("应抛 ProxyException，实际：$e", e is ProxyException)
        assertSame(ProxyError.DnsFailure, (e as ProxyException).error)
        assertEquals("连都没连上却上报了出口", 0, reported)
        verify { net.getAllByName(any()) }   // 确实先在指定出口上试过解析
    }

    /**
     * **PROXY 出口分流建连失败 → 降级默认重试**，且上报的出口必须是**降级后**真正走的那张网。
     * 这一层是唯一知道「最后到底走了哪条路」的地方；若上报仍按最初选的出口算，
     * 历史流量表会把降级后跑在 VPN 上的字节记成 Wi-Fi 直连，用户拿它对账蜂窝用量就会被误导。
     * 构造：解析在出口网络上成功，但建 socket 时炸（模拟绑定失败）→ 必须换默认路由重来。
     */
    @Test(timeout = 15000) fun `出口分流建连失败后降级默认且上报降级后的出口`() = runBlocking {
        val echo = CountingEcho()
        try {
            val net = mockk<Network>()
            every { net.networkHandle } returns 105L   // 缓存键按 netId 分桶，mock 必须给
            every { net.getAllByName(any()) } returns arrayOf(InetAddress.getByName("127.0.0.1"))
            every { net.socketFactory } throws RuntimeException("模拟绑定出口网络失败")
            val provider = mockk<UnderlyingNetworkProvider>()
            every { provider.egressNetwork() } returns net

            val kinds = mutableListOf<EgressKind>()
            val c = OutboundConnector(
                allowAll,
                underlyingNetworkProvider = provider,
                egressClassifier = { n -> if (n == null) EgressKind.VPN else EgressKind.WIFI },
            )
            val s = c.connect("localhost", echo.port, onEgress = { kinds.add(it) })
            assertEquals("出口分流失败后没有降级重试", "hi", echoRoundTrip(s))
            s.close()
            assertEquals("上报的仍是降级前的出口 —— 流量会被记到错误的那一档", listOf(EgressKind.VPN), kinds)
            // 出口槽确实被读到了（返回的是非空 net），而最终上报的却是 null 对应的档位 ——
            // 二者合起来才说明「先试出口、失败后降级默认」这条路真的走完了，而不是一开始就没绑出口。
            verify(exactly = 1) { provider.egressNetwork() }
        } finally { echo.close() }
    }

    /**
     * [OutboundConnector.connectChannel] 交出的通道必须**已连接且仍是阻塞模式**——
     * 契约是「调用方进 relay 前自己切非阻塞」。若这里提前切成非阻塞，调用方紧接着的
     * 首次读写会立刻返回 0 字节，表现成「连上了但页面一直转圈」，且没有任何异常可查。
     */
    @Test(timeout = 15000) fun `connectChannel 交出的是已连接且仍处于阻塞模式的通道`() = runBlocking {
        val echo = CountingEcho()
        try {
            val ch = OutboundConnector(allowAll).connectChannel("localhost", echo.port)
            assertTrue("通道未连接", ch.isConnected)
            assertTrue("通道被提前切成了非阻塞，调用方的首次读写会读到 0 字节", ch.isBlocking)
            ch.write(ByteBuffer.wrap("hi".toByteArray()))
            val buf = ByteBuffer.allocate(2)
            while (buf.hasRemaining()) { if (ch.read(buf) < 0) break }
            assertEquals("hi", String(buf.array()))
            ch.close()
        } finally { echo.close() }
    }

    /** 往已连接的 socket 写 "hi" 并读回 2 字节，确认链路真的通（而不是只拿到一个对象）。 */
    private fun echoRoundTrip(s: java.net.Socket): String {
        s.soTimeout = 3000
        s.getOutputStream().write("hi".toByteArray()); s.getOutputStream().flush()
        val b = ByteArray(2)
        var read = 0
        while (read < 2) {
            val n = s.getInputStream().read(b, read, 2 - read)
            if (n < 0) break
            read += n
        }
        return String(b, 0, read)
    }

    /**
     * 带受理计数的 echo 服务：用于断言「某些地址一个连接都不该到达」（扇出上限、fail-closed）。
     * 与 [startEcho] 一样只绑 127.0.0.1（不绑通配，免得触发 macOS 防火墙的入站询问）——
     * 以域名 "localhost" 发起的用例靠 orderAddresses 的 IPv4 优先落到这里。
     */

    private class CountingEcho(bindAddr: String = "127.0.0.1") {
        val server: ServerSocket = ServerSocket(0, 50, InetAddress.getByName(bindAddr))
        val accepted = AtomicInteger(0)
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val c = try { server.accept() } catch (e: Exception) { break }
                    accepted.incrementAndGet()
                    thread(isDaemon = true) {
                        try {
                            val i = c.getInputStream(); val o = c.getOutputStream(); val buf = ByteArray(1024)
                            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
                        } catch (e: Exception) {
                        } finally { runCatching { c.close() } }
                    }
                }
            }
        }

        fun close() = server.close()
    }

    /**
     * 统计「解析块被派发了几次」的调度器包装——DNS 缓存的观测探针。
     * resolve() 里每次真去查 DNS 都会 `withContext(dnsDispatcher)`，命中缓存则连派发都不会发生；
     * 于是「有没有重新解析」这件本来只在系统调用层可见的事，在单测里变得可断言。
     * 只比较相对增量，不假设一次解析恰好派发几次。
     */
    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher =
            Executors.newCachedThreadPool { r -> Thread(r, "test-dns").apply { isDaemon = true } }
                .asCoroutineDispatcher(),
    ) : CoroutineDispatcher() {
        val dispatches = AtomicInteger(0)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
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
