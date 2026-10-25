package com.mzstd.hxmyproxy

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import com.mzstd.hxmyproxy.testsupport.ControlledVpnService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real Android Network binding/TUN + proxy protocol + byte relay. DNS responses and the
 * unreachable TCP destination are deliberately controlled; never contacts an Internet host. */
@RunWith(AndroidJUnit4::class)
class NetworkRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() }
    }
    private suspend fun await(label: String, check: () -> Boolean) {
        withTimeout(15000) { while (!check()) delay(25) }
        assertTrue(label, check())
    }

    @Test fun rescueRecoveryPreservesConnectSocksAndAnUnrelatedLiveTunnel() = runBlocking {
        check(Build.HARDWARE == "ranchu") { "Owned emulator only" }
        shell("input keyevent 224")
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        ActivityScenario.launch(MainActivity::class.java).use {
            val logging = FileLog.enabled
            FileLog.init(java.io.File(context.cacheDir, "network-recovery")); FileLog.enabled = true
            val provider = UnderlyingNetworkProvider(context)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reactor = NioRelayReactor(2).apply { start() }
            val servers = mutableListOf<TcpProxyServerBase>()
            val connectors = mutableListOf<OutboundConnector>()
            try {
                context.startForegroundService(Intent(context, ControlledVpnService::class.java)
                    .putExtra(ControlledVpnService.EXTRA_BLACKHOLE_LAST_OCTET, 8))
                await("controlled VPN") { ControlledVpnService.active }
                provider.start(); provider.setEgressChoice(EgressNetworkChoice.VPN)
                await("selected VPN and physical rescue network") { provider.egressNetwork() != null && provider.current() != null }
                val vpn = provider.egressNetwork()!!
                val calls = ConcurrentHashMap<String, AtomicInteger>()
                val good = InetAddress.getByName("203.0.113.7")
                val bad = InetAddress.getByName("203.0.113.8")
                val connector = OutboundConnector(DefaultEgressGuard(), underlyingNetworkProvider = provider,
                    systemDns = SystemDnsLookup { net, host ->
                        if (net == vpn) {
                            if (calls.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet() == 1) emptyList()
                            else listOf(good)
                        } else listOf(bad)
                    }).apply { backupDnsEnabled = false }
                connectors.add(connector)
                fun server(socks: Boolean, nio: Boolean): TcpProxyServerBase {
                    val registry = ConnectionRegistry(64, 64)
                    val limits = { ConnectionLimits() }
                    val instance = if (socks) Socks5ProxyServer(Dispatchers.IO, AllowAllAccessController, registry,
                        connector, RelayEngine(), { NoAuthAuthenticator }, limits, Dispatchers.IO,
                        nioReactor = reactor, useNioRelay = nio)
                    else HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry,
                        connector, RelayEngine(), { NoAuthAuthenticator }, limits, Dispatchers.IO,
                        nioReactor = reactor, useNioRelay = nio)
                    instance.start(scope, 0); servers.add(instance); return instance
                }
                val modes = listOf(false to true, true to true, false to false, true to false)
                val listeners = modes.map { server(it.first, it.second) }
                listeners.forEach { listener -> await("proxy listener") { listener.boundPort.value != null } }
                // Literal healthy tunnel has no DNS lease and remains live during all four failures/recoveries.
                open(listeners[0], false, "203.0.113.7").use { healthy ->
                    val recovering = modes.mapIndexed { index, mode -> async(Dispatchers.IO) {
                        val host = "recovery$index.example"
                        val started = System.nanoTime()
                        android.util.Log.i("NetworkRecoveryTest", "start $host socks=${mode.first} nio=${mode.second}")
                        try {
                            open(listeners[index], mode.first, host).use { socket -> echo(socket, index + 1) }
                        } catch (e: Throwable) {
                            throw AssertionError("$host socks=${mode.first} nio=${mode.second} dns=${calls[host]} echoed=${ControlledVpnService.echoedBytes.get()}", e)
                        }
                        android.util.Log.i("NetworkRecoveryTest", "passed recovery $host")
                        assertTrue("same original 10s budget", (System.nanoTime() - started) / 1_000_000 < 10000)
                        assertEquals("initial lookup + shared primary recovery", 2, calls[host]!!.get())
                        open(listeners[index], mode.first, host).use { socket -> echo(socket, index + 20) }
                        assertEquals("successful primary cached", 2, calls[host]!!.get())
                    } }
                    var exchanges = 0
                    while (recovering.any { !it.isCompleted }) {
                        withContext(Dispatchers.IO) { echo(healthy, 100 + exchanges++) }
                        delay(50)
                    }
                    recovering.awaitAll()
                    assertTrue("healthy tunnel transferred throughout real timeout", exchanges >= 10)
                    echo(healthy, 250)
                }
                assertEquals("DNS resolver must never replace selected VPN socket egress", vpn, provider.egressNetwork())
                assertNull(ControlledVpnService.lastFailure)
            } finally {
                servers.forEach { it.stop() }; connectors.forEach { it.cancelPendingDns() }
                scope.cancel(); reactor.stop(); provider.stop()
                context.startService(Intent(context, ControlledVpnService::class.java).setAction(ControlledVpnService.ACTION_STOP))
                await("VPN fixture stopped") { !ControlledVpnService.active }
                FileLog.flush(); FileLog.enabled = logging
            }
        }
    }

    private fun open(server: TcpProxyServerBase, socks: Boolean, host: String): Socket {
        val socket = Socket("127.0.0.1", server.boundPort.value!!).apply { soTimeout = 12000; tcpNoDelay = true }
        try {
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            if (socks) {
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                assertEquals(5, input.read()); assertEquals(0, input.read())
                val name = host.toByteArray()
                output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(1, 0xbb.toByte()))
                output.flush()
                val reply = ByteArray(10)
                java.io.DataInputStream(input).readFully(reply)
                assertEquals("SOCKS success only after real TCP connect", 0, reply[1].toInt())
            } else {
                output.write("CONNECT $host:443 HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray()); output.flush()
                val line = StringBuilder()
                while (!line.endsWith("\r\n\r\n")) {
                    val byte = input.read(); check(byte >= 0); line.append(byte.toChar()); check(line.length < 16384)
                }
                assertTrue("CONNECT success: $line", line.startsWith("HTTP/1.1 200 "))
            }
            return socket
        } catch (e: Throwable) { socket.close(); throw e }
    }
    private fun echo(socket: Socket, seed: Int) {
        val payload = ByteArray(1024) { ((it + seed) % 251).toByte() }
        socket.getOutputStream().write(payload); socket.getOutputStream().flush()
        val answer = ByteArray(payload.size)
        java.io.DataInputStream(socket.getInputStream()).readFully(answer)
        assertArrayEquals(payload, answer)
    }
}
