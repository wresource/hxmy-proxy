package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.proxy.NioRelayReactor
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
     * **上游静默死亡必须被拆掉**（0806 现场：老窗口的 CLI 卡住不动、新开窗口却正常）。
     *
     * 现场机理：VPN 链路消失但没有 FIN/RST，内核不知道对端已死，写进发送缓冲区就算「成功」。
     * 客户端于是一直发、一直等，而 relay 侧的 [lastActivity] 对读写一视同仁——
     * **写操作把隧道续了命**，空闲回收永远轮不到它。表现就是那条连接永久挂起。
     *
     * 这里造出完全一样的形态：客户端发数据，上游收下但一个字节都不回。
     * 注入 600ms 的静默阈值（生产默认 90s），断言隧道被拆、协程按时返回、两端都关掉。
     * 改动前这条必然超时——因为客户端的写会不断刷新 lastActivity。
     */
    @Test(timeout = 30000) fun `上游只收不回的隧道必须被判死拆掉`() = runBlocking {
        val (clientPeer, clientCh) = pair()
        val (upstreamCh, upstreamPeer) = pair()
        clientCh.configureBlocking(false); upstreamCh.configureBlocking(false)
        val reactor = reactor()
        // idle=0 关掉普通空闲回收，确保拆掉它的只可能是「上游静默」这条新判据
        val job = launch(Dispatchers.IO) { reactor.relay(clientCh, upstreamCh, 8192, 0, silenceMs = 600) { _, _ -> } }
        withTimeout(25000) {
            withContext(Dispatchers.IO) {
                clientPeer.writeStr("GET / HTTP/1.1\r\n\r\n")   // 客户端发了，开始等回应
                Thread.sleep(200)
                clientPeer.writeStr("more")                        // 继续发——这正是「写操作续命」的形态
            }
            job.join()                                             // 静默超阈值后隧道应被拆、协程返回
        }
        assertFalse("客户端侧 channel 应已关闭", clientCh.isOpen)
        assertFalse("上游侧 channel 应已关闭", upstreamCh.isOpen)
        upstreamPeer.close(); clientPeer.close()
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
