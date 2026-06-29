package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.concurrent.thread

/** 验证流量记账：并发无损、全局 sink、Top-N 封顶、老化、reset、隐私。 */
class TrafficAccountingTest {

    private val ip1 = InetAddress.getByName("192.168.1.10")

    @Test fun accumulatesPerClientAndDomain() {
        val acc = TrafficAccounting()
        acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost("example.com"); it.add(100, 0); it.add(0, 200) }
        val snap = acc.snapshot(10)
        val c = snap.clients.first { it.clientIp == ip1 }
        assertEquals(100, c.uploadBytes); assertEquals(200, c.downloadBytes)
        val d = snap.topDomains.first { it.host == "example.com" }
        assertEquals(100, d.uploadBytes); assertEquals(200, d.downloadBytes)
    }

    @Test fun globalSinkReceivesEveryIncrement() {
        var gUp = 0L; var gDown = 0L
        val acc = TrafficAccounting(globalSink = { u, d -> gUp += u; gDown += d })
        acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost("a.com"); it.add(50, 0); it.add(0, 70) }
        assertEquals(50, gUp); assertEquals(70, gDown)
    }

    @Test fun concurrentAddIsLossless() {
        val acc = TrafficAccounting()
        val t = acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost("x.com") }
        (1..8).map { thread { repeat(1000) { t.add(1, 1) } } }.forEach { it.join() }
        val snap = acc.snapshot(10)
        assertEquals(8000, snap.clients.first().uploadBytes)
        assertEquals(8000, snap.clients.first().downloadBytes)
    }

    @Test fun topNCapsDomainsWithOthersBucketAndConservesBytes() {
        val acc = TrafficAccounting(maxDomains = 2)
        listOf("a.com" to 10L, "b.com" to 20L, "c.com" to 30L).forEach { (h, b) ->
            acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost(h); it.add(b, 0) }
        }
        val snap = acc.snapshot(10)
        assertTrue("域名条目应被封顶（含兜底桶）", snap.topDomains.size <= 3)
        assertEquals("总字节应守恒、不丢失", 60, snap.topDomains.sumOf { it.uploadBytes })
    }

    @Test fun ageOutRemovesIdleClosedEntries() {
        var now = 1000L
        val acc = TrafficAccounting(clock = { now })
        acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost("a.com"); it.add(10, 0); it.close() }
        now = 1000L + 10_000
        acc.ageOut(5_000)
        assertTrue("空闲已关闭的客户端应被老化", acc.snapshot(10).clients.none { it.clientIp == ip1 })
    }

    @Test fun domainCarriesProtocolAndSplitsByProtocol() {
        val acc = TrafficAccounting()
        // 同一域名分别经 HTTP 与 SOCKS5 访问 → 应拆成两行，各带自己的协议与字节。
        acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost("example.com"); it.add(100, 200) }
        acc.openConnection(ip1, ProxyProtocol.SOCKS5).also { it.bindHost("example.com"); it.add(0, 50) }
        val snap = acc.snapshot(10)
        val http = snap.topDomains.first { it.host == "example.com" && it.protocol == ProxyProtocol.HTTP }
        assertEquals(100, http.uploadBytes); assertEquals(200, http.downloadBytes)
        val socks = snap.topDomains.first { it.host == "example.com" && it.protocol == ProxyProtocol.SOCKS5 }
        assertEquals(50, socks.downloadBytes)
        assertEquals("同域名两协议应分两行", 2, snap.topDomains.count { it.host == "example.com" })
    }

    @Test fun resetClears() {
        val acc = TrafficAccounting()
        acc.openConnection(ip1, ProxyProtocol.HTTP).also { it.bindHost("a.com"); it.add(10, 10) }
        acc.reset()
        val snap = acc.snapshot(10)
        assertTrue(snap.clients.isEmpty()); assertTrue(snap.topDomains.isEmpty())
    }

    @Test fun keepAliveMultiHostDoesNotLeakDomainConns() {
        var now = 1000L
        val acc = TrafficAccounting(clock = { now })
        // 同一连接 keep-alive 切换多个 host，最后关闭：旧 host 的连接计数应在切换时释放。
        acc.openConnection(ip1, ProxyProtocol.HTTP).also {
            it.bindHost("a.com"); it.add(10, 0)
            it.bindHost("b.com"); it.add(20, 0)
            it.close()
        }
        // 两域名都应无活跃连接 → 老化清空（修复前 a.com 的 conns 虚高、永不老化）。
        now += 10_000
        acc.ageOut(5_000)
        assertTrue("多 host 切换后所有域名应能老化（不泄漏 conns）", acc.snapshot(10).topDomains.isEmpty())
    }
}
