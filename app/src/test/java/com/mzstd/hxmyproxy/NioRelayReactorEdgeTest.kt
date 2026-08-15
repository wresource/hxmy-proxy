package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.proxy.NioRelayReactor
import com.mzstd.hxmyproxy.core.proxy.RequestTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * NioRelayReactor 的**边界与收场**（已有 NioRelayReactorTest 覆盖往返/idle/stop 的正常路径）。
 *
 * 这里守的是同一条铁律：隧道协程**永远不能永久挂起**。挂起意味着 registry 名额与两个 FD 一起泄漏，
 * 且全程零日志——正是「服务还在跑但新连接全部转圈」这种最难查的形态。
 * 所以每个异常入口都断言：协程按时返回 + 两端 channel 都被关掉。
 */
class NioRelayReactorEdgeTest {

    private val res = Resources()

    @After fun tearDown() = res.close()

    /**
     * 半关（客户端 shutdownOutput）必须**只**传播这一个方向：上游读到 EOF，但反方向仍要能继续送数据。
     * 传成整条关闭的话，表现为「上传完就断」——POST 大文件后拿不到响应。
     */
    @Test(timeout = 30000) fun `半关应传播且反方向仍可续传`() = runBlocking {
        val (clientPeer, clientCh) = pair()
        val (upstreamCh, upstreamPeer) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = reactor()
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 0) { _, _ -> } }
        withTimeout(20000) {
            withContext(Dispatchers.IO) {
                clientPeer.writeStr("req")
                assertEquals("req", upstreamPeer.readStr(3))

                clientPeer.shutdownOutput()
                assertEquals("客户端半关必须传播到上游（否则上游会一直等请求体）", -1, upstreamPeer.read(ByteBuffer.allocate(1)))

                upstreamPeer.writeStr("resp")
                assertEquals("反方向不能被半关误伤", "resp", clientPeer.readStr(4))

                upstreamPeer.shutdownOutput()   // 两向皆 EOF → 整条隧道结束
            }
            job.join()
        }
        Unit
    }

    /**
     * reactor 已 stop 后仍来新隧道（stopServers 与在途握手的竞态）：必须**立即拆掉**并 resume，
     * 而不是把任务排进没人消费的队列里让协程永远挂着。
     */
    @Test(timeout = 30000) fun `stop之后注册的隧道应立即拆掉而非永久挂起`() = runBlocking {
        val reactor = NioRelayReactor(workerCount = 1)
        reactor.start()
        reactor.stop()

        val (_, clientCh) = pair()
        val (upstreamCh, _) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)

        withTimeout(15000) { reactor.relay(clientCh, upstreamCh, 8192, 0) { _, _ -> } }

        assertFalse("拆隧道必须关掉 client 端，否则 FD 泄漏", clientCh.isOpen)
        assertFalse("拆隧道必须关掉 upstream 端", upstreamCh.isOpen)
    }

    /**
     * 传入仍是**阻塞模式**的 channel（调用方漏了 configureBlocking(false)）：register 会抛，
     * 此时同样必须拆隧道 + resume。挂死的话调用方连一条错误日志都拿不到。
     */
    @Test(timeout = 30000) fun `阻塞模式channel应被立即拆掉而非挂死`() = runBlocking {
        val reactor = reactor()
        val (_, clientCh) = pair()
        val (upstreamCh, _) = pair()   // 故意不切非阻塞

        withTimeout(15000) { reactor.relay(clientCh, upstreamCh, 8192, 0) { _, _ -> } }

        assertFalse(clientCh.isOpen)
        assertFalse(upstreamCh.isOpen)
    }

    /** 协程取消（客户端连接被 stop/evict 级联取消）要拆干净两端，不能只 resume 不关 channel。 */
    @Test(timeout = 30000) fun `协程取消应拆掉隧道并关闭两端`() = runBlocking {
        val (_, clientCh) = pair()
        val (upstreamCh, _) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = reactor()
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 0) { _, _ -> } }
        delay(300)   // 等注册落地

        job.cancelAndJoin()

        assertTrue(
            "取消后两端都应被关闭",
            ProxyTestKit.await(5000) { !clientCh.isOpen && !upstreamCh.isOpen },
        )
    }

    /**
     * 1MB 数据过 8KB 缓冲：必然反复进出背压（写不完→暂停读→OP_WRITE→排空→恢复读）。
     * 这段写错的表现是**静默丢字节或错序**——下载的文件损坏、图片花屏，而不是报错。
     */
    @Test(timeout = 60000) fun `大流量单向传输应逐字节无损并如实计量`() = runBlocking {
        val (clientPeer, clientCh) = pair()
        val (upstreamCh, upstreamPeer) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = reactor()
        val up = AtomicLong(0)
        val down = AtomicLong(0)
        val job = launch(Dispatchers.IO) {
            reactor.relay(clientCh, upstreamCh, 8192, 0) { u, d -> up.addAndGet(u); down.addAndGet(d) }
        }

        val payload = ByteArray(1 shl 20) { (it * 31 + 7).toByte() }
        val received = ByteArray(payload.size)
        // 收方必须与发方并发，否则两端发送缓冲会互相堵死（这也正是背压要处理的局面）。
        val reader = thread(isDaemon = true) {
            var off = 0
            val buf = ByteBuffer.allocate(16 * 1024)
            while (off < payload.size) {
                buf.clear()
                val n = upstreamPeer.read(buf)
                if (n < 0) break
                buf.flip()
                buf.get(received, off, n)
                off += n
            }
        }
        withContext(Dispatchers.IO) {
            val out = ByteBuffer.wrap(payload)
            while (out.hasRemaining()) clientPeer.write(out)
        }
        reader.join(30000)

        assertArrayEquals("背压路径不得丢字节或错序", payload, received)
        assertEquals("上行计量应等于实际转发字节", payload.size.toLong(), up.get())
        assertEquals("没往回发过数据，下行计量必须是 0", 0L, down.get())

        clientPeer.close(); upstreamPeer.close()
        withTimeout(20000) { job.join() }
        Unit
    }

    /** idleMillis=0 的语义是**永不超时**（长连接/SSH 场景）；误当成 0ms 超时会让隧道秒断。 */
    @Test(timeout = 30000) fun `idle为0应表示永不超时`() = runBlocking {
        val (clientPeer, clientCh) = pair()
        val (upstreamCh, upstreamPeer) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = reactor(sweepIntervalMs = 50)
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 0) { _, _ -> } }

        delay(600)   // 远超 sweep 周期，全程无数据
        assertTrue("idle=0 时空闲隧道不能被 sweep 拆掉", job.isActive)

        clientPeer.close(); upstreamPeer.close()
        withTimeout(20000) { job.join() }
        Unit
    }

    // ---- 辅助 ----

    private fun reactor(sweepIntervalMs: Long = 200): NioRelayReactor {
        val r = NioRelayReactor(workerCount = 1, sweepIntervalMs = sweepIntervalMs)
        r.start()
        res.onClose { r.stop() }
        return r
    }

    /** 一对已连接的（阻塞模式）loopback SocketChannel，并登记清理。 */
    private fun pair(): Pair<SocketChannel, SocketChannel> {
        val ss = ServerSocketChannel.open()
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val a = SocketChannel.open(ss.localAddress as InetSocketAddress)
        val b = ss.accept()
        ss.close()
        a.configureBlocking(true); b.configureBlocking(true)
        res.onClose { runCatching { a.close() } }
        res.onClose { runCatching { b.close() } }
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

    /**
     * **上游静默只标记、不再拆隧道** —— 这条判据的行为被真机数据推翻过一次，改在这里锁住。
     *
     * 它最初是为 0806 那个现场加的：VPN 链路消失但没有 FIN/RST，客户端一直发、上游一个字节不回，
     * 而 [lastActivity] 对读写一视同仁，**写操作把隧道续了命**，空闲回收永远轮不到它。
     *
     * 但 0815 两台设备各跑一天的实测(n=1121 / 753)显示，真正被它拆掉的几乎全不是那个形态：
     * 判死那一刻 `sinceTxSec` 的 p25/p50/p75 **全是 90**、max 只有 91 ——
     * 客户端发完最后一个字节后整整 90 秒再没发过任何东西，上游也没回，
     * 这是连接池里闲置的连接。「正在传输被腰斩」(sinceTxSec<5s)两台合计只有 4 条(0.3%/0.1%)。
     * 而拆掉它只换来 30 秒：silent 存活 p50=91.8s，idle 存活 p50=121.6s ——
     * 不拆的话 idle 必然接手，代价却是每天约 1900 次无谓的 TLS 重建。
     *
     * 所以现在：**判据保留(仍是链路半死的唯一早期信号)，但只告警不拆**。
     * 这条测试同时守两件事：告警要落、隧道不能被拆。
     */
    @Test(timeout = 30000) fun `上游只收不回时应告警但不再拆隧道`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("silent-noclose").toFile()
        FileLog.enabled = true
        FileLog.init(dir)
        try {
            val (clientPeer, clientCh) = pair()
            val (upstreamCh, upstreamPeer) = pair()
            clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
            val reactor = reactor()
            // idle=0 关掉空闲回收：这样若隧道被拆，唯一可能的凶手就是静默判据本身。
            // **host 必须唯一**：告警的节流 dedup key 是 `usilent:$host`，而 Ev 的节流表是
            // 全局静态的、跨用例共享。两个用例都不传 host 就会共用 `usilent:?`，
            // 后跑的那个被 30 秒窗口整个遮蔽 —— 表现为「告警没落」，很容易误判成功能坏了。
            val job = launch(Dispatchers.IO) {
                reactor.relay(clientCh, upstreamCh, 8192, 0, silenceMs = 600, host = "silent-noclose.test") { _, _ -> }
            }
            withContext(Dispatchers.IO) {
                clientPeer.writeStr("GET / HTTP/1.1\r\n\r\n")   // 客户端发了，开始等回应
                Thread.sleep(2500)                                 // 远超 600ms 阈值，判据必然已触发多轮
            }
            assertTrue("静默判据不得再拆隧道——客户端侧应仍然开着", clientCh.isOpen)
            assertTrue("静默判据不得再拆隧道——上游侧应仍然开着", upstreamCh.isOpen)
            val warn = FileLog.snapshot().lines().firstOrNull { it.contains("evt=nio.upstream.silent") }
                ?: throw AssertionError("检测仍须生效并告警(它是链路半死的唯一早期信号)")
            assertTrue("告警要带 sinceTxSec，那是区分池化闲置与传输腰斩的唯一字段：$warn",
                warn.contains("sinceTxSec="))
            job.cancelAndJoin()
            upstreamPeer.close(); clientPeer.close()
        } finally {
            FileLog.clear()
            dir.deleteRecursively()
        }
    }

    /**
     * **被标记过的隧道，终局必须带上判决(`flag=`)** —— 这是那条判据的最终审判字段。
     *
     * 判据改成「只标记不拆」之后，一件此前**无法观测**的事变得可观测了：
     * 被判为「上游静默死亡」的连接，后来到底有没有再收到上游字节。
     * 此前它一标记就拆，答案被自己的动作抹掉了（观测者效应）。
     *
     * `flag=revived` 意味着上游后来又说话了 —— 那条连接根本没死，判据当初拆的是活连接。
     * **这是零推断的铁证**，比任何按字节或时长做的统计推断都硬:
     * 上一轮用 `down` 累计字节推出的「99.67% 误杀」就因为「一条搬过 142KB 后真死的隧道
     * down 同样大」而被对抗审查打了下来。
     *
     * 这里造的正是 revived 形态：先静默到触发标记，再让上游说话，最后收尾。
     */
    @Test(timeout = 30000) fun `标记后上游又说话必须记成revived`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("relay-revived").toFile()
        FileLog.enabled = true
        FileLog.init(dir)
        try {
            val (clientPeer, clientCh) = pair()
            val (upstreamCh, upstreamPeer) = pair()
            clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
            val reactor = reactor()
            val trace = RequestTrace.open("HTTP", 54321)
            val job = launch(Dispatchers.IO) {
                reactor.relay(
                    clientCh, upstreamCh, 8192, 0, silenceMs = 400,
                    host = "api.anthropic.com", trace = trace,
                ) { _, _ -> }
            }
            withContext(Dispatchers.IO) {
                clientPeer.writeStr("GET / HTTP/1.1\r\n\r\n")
                Thread.sleep(1500)                  // 静默超阈值 ⇒ 被标记
                upstreamPeer.writeStr("HTTP/1.1 200 OK\r\n\r\n")   // 上游其实还活着
                Thread.sleep(400)
                upstreamPeer.close(); clientPeer.close()           // 正常收尾
            }
            job.join()
            val closed = FileLog.snapshot().lines().firstOrNull { it.contains("evt=req.closed") }
                ?: throw AssertionError("应落 req.closed 行")
            assertTrue("req.closed 必须带 host，否则答不出「是谁」：$closed",
                closed.contains("host=api.anthropic.com"))
            assertTrue("标记后上游又说话了，必须记成 flag=revived —— 这是判据误判的铁证：$closed",
                closed.contains("flag=revived"))
        } finally {
            FileLog.clear()
            dir.deleteRecursively()
        }
    }

    /**
     * 对照组：正常收尾的隧道必须记成 `why=eof`，不能混进判死那一类。
     *
     * 没有这条，上一条测试用「任何 req.closed 行都含 upstream-silent」也能变绿——
     * 而真正要守的是**两类拆除能被分开数**：误杀率 = upstream-silent / 总数，
     * 分母分子混在一起，这个比值就永远算不出来。
     */
    @Test(timeout = 30000) fun `正常收尾的隧道不得被记成判死`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("relay-eof").toFile()
        FileLog.enabled = true
        FileLog.init(dir)
        try {
            val (clientPeer, clientCh) = pair()
            val (upstreamCh, upstreamPeer) = pair()
            clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
            val reactor = reactor()
            val trace = RequestTrace.open("HTTP", 54322)
            val job = launch(Dispatchers.IO) {
                reactor.relay(
                    clientCh, upstreamCh, 8192, 0, silenceMs = 60_000,
                    host = "example.com", trace = trace,
                ) { _, _ -> }
            }
            withTimeout(25000) {
                withContext(Dispatchers.IO) {
                    clientPeer.writeStr("hi")
                    Thread.sleep(150)
                    upstreamPeer.writeStr("ok")     // 上游有回应，排除静默判据
                    Thread.sleep(150)
                    clientPeer.close(); upstreamPeer.close()   // 双向 EOF → 正常收尾
                }
                job.join()
            }
            val closed = FileLog.snapshot().lines().firstOrNull { it.contains("evt=req.closed") }
                ?: throw AssertionError("正常收尾也应落 req.closed 行")
            assertTrue("正常收尾必须记 why=eof：$closed", closed.contains("why=eof"))
            assertFalse("正常收尾不得被记成判死：$closed", closed.contains("upstream-silent"))
        } finally {
            FileLog.clear()
            dir.deleteRecursively()
        }
    }

    /**
     * 对照组：上游**有**回应时，同样的阈值下隧道不得被误杀。
     *
     * 缺了这条就无法区分「静默判据生效」和「这条隧道本来就会被拆」——两者都会让上一条变绿。
     * 也顺带守住 SSE / 长轮询这类场景：只要上游还在推数据，就不该被当成死连接。
     */
    @Test(timeout = 30000) fun `上游有回应时不得被静默判据误杀`() = runBlocking {
        val (clientPeer, clientCh) = pair()
        val (upstreamCh, upstreamPeer) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = reactor()
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 0, silenceMs = 600) { _, _ -> } }
        withContext(Dispatchers.IO) {
            clientPeer.writeStr("req")
            repeat(6) {                       // 每 200ms 回一点，跨过 600ms 阈值多次
                Thread.sleep(200)
                upstreamPeer.writeStr("chunk")
            }
        }
        assertTrue("上游一直在回应，隧道不该被拆", clientCh.isOpen && upstreamCh.isOpen)
        job.cancelAndJoin()
        upstreamPeer.close(); clientPeer.close()
    }
}
