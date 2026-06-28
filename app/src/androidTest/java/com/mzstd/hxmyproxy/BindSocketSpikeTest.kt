package com.mzstd.hxmyproxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel

/**
 * Phase 0 GATING spike（relay 非阻塞化 C 的命门验证，见 version-md/proxy-relay-nonblocking-plan.md）。
 *
 * 验证：反射取 SocketChannel 的 FileDescriptor + Network.bindSocket(fd)（connect 之前）能否把 NIO
 * SocketChannel 绑定到非 VPN 真实网络（= 出口分流绕过系统 VPN）。这是 NIO relay 唯一不可替代的前提；
 * 不通过则整套 NIO 方案 NO-GO、维持现状阻塞 relay。
 *
 * 判定方式：connect 到 8.8.8.8:53（几乎必达），比对连接后的**本地源 IP**——
 *  - bindSocket(fd) 路径源 IP == socketFactory(现有可靠分流)路径源 IP  → bindSocket(fd) 真绑生效、二者等效
 *  - 若系统 VPN 在线，二者应 != default(不绑,走默认网络含 VPN)路径源 IP   → 确实绕过了 VPN（走真实网卡而非 tun）
 *
 * 用本地源 IP 而非外网回显 IP：避免依赖测试 WiFi 的 :80 外网可达性（实测该网络对 Cloudflare:80 回 RST）。
 * 必须在默认 hidden-API 限制下跑（代表生产），不要放开 hidden_api_policy。
 */
class BindSocketSpikeTest {

    private companion object {
        const val TAG = "BindSocketSpike"
        const val HOST = "8.8.8.8"     // IP literal，无需 DNS；TCP:53 普遍可达
        const val PORT = 53
        const val TIMEOUT_MS = 8000
    }

    private fun cm(): ConnectivityManager {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private fun underlyingNonVpn(): Network? = cm().let { c ->
        c.allNetworks.firstOrNull { n ->
            val caps = c.getNetworkCapabilities(n)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun systemHasVpn(): Boolean = cm().let { c ->
        c.allNetworks.any { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** 反射取 SocketChannel 底层 fd：先 sun.nio.ch.SocketChannelImpl.fd，回退 socket().getFileDescriptor$()。 */
    private fun fdOf(channel: SocketChannel): FileDescriptor? {
        runCatching {
            val f = Class.forName("sun.nio.ch.SocketChannelImpl").getDeclaredField("fd")
            f.isAccessible = true
            (f.get(channel) as? FileDescriptor)?.let { Log.i(TAG, "fd via SocketChannelImpl.fd OK"); return it }
        }.onFailure { Log.w(TAG, "fd via SocketChannelImpl.fd 失败: $it") }
        runCatching {
            val sock = channel.socket()
            val m = sock.javaClass.getMethod("getFileDescriptor\$")
            (m.invoke(sock) as? FileDescriptor)?.let { Log.i(TAG, "fd via socket().getFileDescriptor\$ OK"); return it }
        }.onFailure { Log.w(TAG, "fd via socket().getFileDescriptor\$ 失败: $it") }
        return null
    }

    /** bindSocket(fd) 路径：SocketChannel + 反射 fd + connect 前 bindSocket。返回 (本地源IP, 反射是否成功)。 */
    private fun localIpViaBoundChannel(network: Network): Pair<String, Boolean> {
        val ch = SocketChannel.open()
        try {
            ch.configureBlocking(true)
            val fd = fdOf(ch)
            assertNotNull("反射取 SocketChannel fd 失败 → 命门 NO-GO（生产同样无法 bindSocket(fd)）", fd)
            network.bindSocket(fd)                       // 必须在 connect 之前
            ch.connect(InetSocketAddress(InetAddress.getByName(HOST), PORT))
            val local = (ch.localAddress as InetSocketAddress).address.hostAddress ?: "?"
            return local to true
        } finally { runCatching { ch.close() } }
    }

    private fun localIpViaSocket(network: Network?): String {
        val s: Socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            s.connect(InetSocketAddress(InetAddress.getByName(HOST), PORT), TIMEOUT_MS)
            return s.localAddress.hostAddress ?: "?"
        } finally { runCatching { s.close() } }
    }

    @Test
    fun bindSocket_fd_routes_egress_around_vpn() {
        val hasVpn = systemHasVpn()
        val network = underlyingNonVpn()
        Log.i(TAG, "system VPN 在线 = $hasVpn ; 非 VPN 底层网络 = $network")
        assertNotNull("找不到非 VPN 底层网络（确认 WiFi/数据已连）", network)
        network!!

        val bound = runCatching { localIpViaBoundChannel(network) }
        val factory = runCatching { localIpViaSocket(network) }
        val default = runCatching { localIpViaSocket(null) }

        val boundIp = bound.getOrNull()?.first
        val reflOk = bound.getOrNull()?.second
        val factoryIp = factory.getOrNull()
        val defaultIp = default.getOrNull()

        Log.i(TAG, "========= Phase 0 spike 结果（本地源 IP @ connect 8.8.8.8:53）=========")
        Log.i(TAG, "反射取 fd 成功              = $reflOk")
        Log.i(TAG, "bindSocket(fd) 本地源 IP   = ${boundIp ?: "FAIL: ${bound.exceptionOrNull()}"}")
        Log.i(TAG, "socketFactory 本地源 IP    = ${factoryIp ?: "FAIL: ${factory.exceptionOrNull()}"}")
        Log.i(TAG, "default(不绑) 本地源 IP    = ${defaultIp ?: "FAIL: ${default.exceptionOrNull()}"}")
        Log.i(TAG, "system VPN 在线            = $hasVpn")
        Log.i(TAG, "绕过 VPN (bound != default) = ${boundIp != null && boundIp != defaultIp}")
        Log.i(TAG, "===============================================================")

        assertNotNull("bindSocket(fd) 路径失败: ${bound.exceptionOrNull()}", boundIp)
        assertTrue("bindSocket(fd) 本地源 IP 为空", !boundIp.isNullOrBlank())
        assertNotNull("socketFactory 对照路径失败: ${factory.exceptionOrNull()}", factoryIp)
        assertTrue(
            "bindSocket(fd) 源 IP != socketFactory 源 IP → 绑定未生效（可能仍走 VPN）。bound=$boundIp factory=$factoryIp",
            boundIp == factoryIp,
        )
        if (hasVpn && defaultIp != null) {
            assertTrue(
                "VPN 在线但 bound 源 IP == default 源 IP → 未绕过 VPN。bound=$boundIp default=$defaultIp",
                boundIp != defaultIp,
            )
        } else {
            Log.w(TAG, "VPN 未在线或 default 不可用：仅验证 bindSocket(fd) 技术可行 + 与 socketFactory 等效")
        }
    }
}
