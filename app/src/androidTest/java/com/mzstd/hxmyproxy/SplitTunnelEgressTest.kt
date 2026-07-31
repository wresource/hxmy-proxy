package com.mzstd.hxmyproxy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import org.junit.Assume
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 端到端验证出口分流:对比「经默认网络(VPN)」与「经底层非 VPN 网络」两条路的出口 IP。
 * VPN 开启且非 lockdown 时,两者应不同 —— 证明绕过 VPN 生效。
 * 需:设备 WiFi + VPN 开着。logcat tag = hxmyproxy-test 打印两个 IP。
 */
@RunWith(AndroidJUnit4::class)
class SplitTunnelEgressTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bypassVpnEgressDiffersFromVpn() {
        // 没有 VPN 时两条路本来就是同一条，断言必然失败——那是**环境不满足**，不是代码坏了。
        // 用 assume 让它记为 skipped 而非 failed：一个恒红的测试等于没有测试，
        // 时间久了只会训练出「红了也不用看」的习惯，真出问题时反而漏掉。
        Assume.assumeTrue("需要设备正在连 VPN（模拟器/无 VPN 时跳过）", vpnActive())
        val provider = UnderlyingNetworkProvider(context).apply { start() }
        var net: Network? = null
        for (i in 0 until 24) { net = provider.current(); if (net != null) break; Thread.sleep(250) }
        assertNotNull("need a non-VPN WiFi network", net)

        val viaVpn = fetchEgressIp(null)
        val viaReal = fetchEgressIp(net)
        provider.stop()

        Log.i("hxmyproxy-test", "egress via default(VPN)=[$viaVpn]  via underlying(real)=[$viaReal]")
        assertNotEquals("bypass egress IP ($viaReal) should differ from VPN egress IP ($viaVpn)", viaVpn, viaReal)
    }

    /** 当前是否有活跃 VPN（任一 Network 带 TRANSPORT_VPN）。 */
    private fun vpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** 经 [network](null=默认网络/VPN)请求一个回显出口 IP 的服务,返回纯 IP 文本。 */
    private fun fetchEgressIp(network: Network?): String {
        val host = "whatismyip.akamai.com"
        val addr = (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).first()
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        socket.use { s ->
            s.connect(InetSocketAddress(addr, 80), 10_000)
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write("GET /?format=text HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: hxmy-test\r\n\r\n".toByteArray())
                flush()
            }
            val resp = s.getInputStream().bufferedReader().readText()
            return resp.substringAfter("\r\n\r\n").trim()
        }
    }
}
