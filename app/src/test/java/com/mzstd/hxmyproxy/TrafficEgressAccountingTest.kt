package com.mzstd.hxmyproxy

import com.mzstd.hxmyproxy.core.model.ProxyProtocol
import com.mzstd.hxmyproxy.core.proxy.TrafficAccounting
import com.mzstd.hxmyproxy.core.stats.EgressKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/**
 * 采集链路：`ConnTracker.add`（唯一的搬字节热路径）把增量按**本连接的出口**喂给历史统计。
 *
 * 这条链路最容易出的错是「某条建连路径忘了回填出口」——那时字节不该凭空消失，而应显式落进
 * OTHER，好让统计页上「其他」异常大成为可见的线索（见 absence-is-not-evidence）。
 */
class TrafficEgressAccountingTest {

    private val ip: InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 7))

    private class Recorder {
        val byKind = HashMap<EgressKind, LongArray>()
        val sink: (EgressKind, Long, Long) -> Unit = { k, up, down ->
            val v = byKind.getOrPut(k) { LongArray(2) }
            v[0] += up
            v[1] += down
        }
    }

    @Test
    fun `按连接的出口分类累加`() {
        val r = Recorder()
        val acc = TrafficAccounting(historySink = r.sink)

        val viaVpn = acc.openConnection(ip, ProxyProtocol.HTTP)
        viaVpn.bindHost("example.com")
        viaVpn.bindEgress(EgressKind.VPN)
        viaVpn.add(100, 900)

        val viaCell = acc.openConnection(ip, ProxyProtocol.SOCKS5)
        viaCell.bindHost("example.org", direct = true)
        viaCell.bindEgress(EgressKind.CELLULAR)
        viaCell.add(1, 2)
        viaCell.add(3, 4)

        assertEquals(listOf(100L, 900L), r.byKind[EgressKind.VPN]!!.toList())
        assertEquals(listOf(4L, 6L), r.byKind[EgressKind.CELLULAR]!!.toList())
        assertEquals(2, r.byKind.size)
    }

    @Test
    fun `未回填出口的字节落进 OTHER 而不是消失`() {
        val r = Recorder()
        val acc = TrafficAccounting(historySink = r.sink)
        val t = acc.openConnection(ip, ProxyProtocol.HTTP)
        t.bindHost("example.com")
        // 故意不调 bindEgress
        t.add(7, 8)

        assertEquals(listOf(7L, 8L), r.byKind[EgressKind.OTHER]!!.toList())
    }

    @Test
    fun `降级重连改写出口后按新出口计`() {
        val r = Recorder()
        val acc = TrafficAccounting(historySink = r.sink)
        val t = acc.openConnection(ip, ProxyProtocol.HTTP)
        t.bindHost("example.com")
        t.bindEgress(EgressKind.WIFI)
        t.add(0, 10)
        // 出口分流失败 → 降级默认（VPN），OutboundConnector 会重新回填
        t.bindEgress(EgressKind.VPN)
        t.add(0, 90)

        assertEquals(10L, r.byKind[EgressKind.WIFI]!![1])
        assertEquals(90L, r.byKind[EgressKind.VPN]!![1])
    }

    @Test
    fun `会话计量归零不影响历史统计`() {
        val r = Recorder()
        val acc = TrafficAccounting(historySink = r.sink)
        val t = acc.openConnection(ip, ProxyProtocol.HTTP)
        t.bindHost("example.com")
        t.bindEgress(EgressKind.VPN)
        t.add(0, 500)
        acc.reset()   // 一次共享结束：会话计量清零

        // 历史侧已经收到的字节不会被 reset 撤回（它按天落盘、跨会话累计）
        assertEquals(500L, r.byKind[EgressKind.VPN]!![1])
        assertEquals(0, acc.snapshot(10).topDomains.size)
    }
}
