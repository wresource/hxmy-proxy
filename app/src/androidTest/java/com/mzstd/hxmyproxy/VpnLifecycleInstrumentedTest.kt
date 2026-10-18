package com.mzstd.hxmyproxy

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.log.ProcessDiagnostics
import com.mzstd.hxmyproxy.core.model.*
import com.mzstd.hxmyproxy.core.network.UnderlyingNetworkProvider
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import com.mzstd.hxmyproxy.core.stats.EgressKind
import com.mzstd.hxmyproxy.core.network.InterfaceScanner
import com.mzstd.hxmyproxy.data.repository.ProxyServerRepository
import com.mzstd.hxmyproxy.service.ProxyForegroundService
import com.mzstd.hxmyproxy.testsupport.ControlledVpnService
import com.mzstd.hxmyproxy.testsupport.VerificationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** 真实VpnService/TUN/Network及HTTP/NIO，本地受控TCP对端；不代表外部VPN或无线射频。 */
@RunWith(AndroidJUnit4::class)
class VpnLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
    private fun await(label: String, timeoutMs: Long = 15000, check: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!check() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue("$label; fixtureFailure=${ControlledVpnService.lastFailure}", check())
    }

    private inner class Gateway : AutoCloseable {
        val provider = UnderlyingNetworkProvider(context)
        val registry = ConnectionRegistry(64, 64)
        val bound = AtomicLong()
        val blocking = AtomicBoolean()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val reactor = NioRelayReactor(workerCount = 2).also { it.start() }
        val connector = OutboundConnector(DefaultEgressGuard(), underlyingNetworkProvider = provider,
            egressClassifier = { bound.set(it?.networkHandle ?: 0); EgressKind.VPN })
        private val relayDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                blocking.set(true); Dispatchers.IO.dispatch(context, block)
            }
        }
        private fun makeServer() = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry,
            connector, RelayEngine(), { NoAuthAuthenticator }, { ConnectionLimits() }, relayDispatcher,
            accounting = TrafficAccounting(), nioReactor = reactor, useNioRelay = true)
        private var server = makeServer()
        init {
            provider.start(); provider.setEgressChoice(EgressNetworkChoice.VPN)
            server.start(scope, 0)
            await("proxy listener") { server.boundPort.value != null }
        }
        fun client(): Socket = Socket("127.0.0.1", server.boundPort.value!!).apply { soTimeout = 5000; tcpNoDelay = true }
        fun connect(): Socket {
            val socket = client()
            try {
                socket.getOutputStream().write("CONNECT 203.0.113.7:443 HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                assertTrue("CONNECT expected 200", line(input).contains(" 200 "))
                while (line(input).isNotEmpty()) { }
                assertEquals(provider.egressNetwork()!!.networkHandle, bound.get())
                return socket
            } catch (error: Throwable) { socket.close(); throw error }
        }
        fun echo(socket: Socket, seed: Int) {
            val payload = ByteArray(128) { ((it + seed) and 127).toByte() }
            socket.getOutputStream().write(payload); socket.getOutputStream().flush()
            val received = ByteArray(payload.size)
            var at = 0
            while (at < received.size) {
                val count = socket.getInputStream().read(received, at, received.size - at)
                check(count > 0) { "VPN echo ended early" }; at += count
            }
            assertArrayEquals(payload, received)
        }
        fun roundTrip(seed: Int = 0) { connect().use { echo(it, seed) } }
        fun restartListener() {
            server.stop(); registry.reset()
            server = makeServer().also { it.start(scope, 0) }
            await("new listener") { server.boundPort.value != null }
        }
        override fun close() {
            server.stop(); reactor.stop(); scope.cancel(); provider.stop()
            runBlocking { withTimeout(5000) { scope.coroutineContext[Job]!!.join() } }
        }
    }

    private fun line(input: InputStream): String {
        val out = StringBuilder()
        while (true) {
            val next = input.read()
            check(next >= 0) { "unexpected EOF in HTTP header" }
            if (next == 10) return out.toString()
            if (next != 13) out.append(next.toChar())
            check(out.length < 16384)
        }
    }
    private fun startVpn() {
        context.startForegroundService(Intent(context, ControlledVpnService::class.java))
        await("VPN established") { ControlledVpnService.active }
    }
    private fun stopVpn() {
        context.startService(Intent(context, ControlledVpnService::class.java).setAction(ControlledVpnService.ACTION_STOP))
        await("VPN stopped") { !ControlledVpnService.active }
    }
    private fun <T> withVpn(block: (Gateway) -> T): T {
        check(Build.HARDWARE == "ranchu") { "This test must only run on an owned emulator" }
        shell("input keyevent 224")
        shell("svc wifi enable")
        shell("cmd wifi connect-network AndroidWifi open")
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        ActivityScenario.launch(MainActivity::class.java).use {
            var failure: Throwable? = null
            try {
                startVpn()
                Gateway().use { gateway ->
                    await("VPN callback") { gateway.provider.status().vpn }
                    return block(gateway)
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    stopVpn()
                    shell("input keyevent 224")
                    shell("svc wifi enable")
                    shell("appops set ${context.packageName} ACTIVATE_VPN default")
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    /** 使用实际前台服务/Repository/准入/规则/转发，恢复隔离模拟器原设置。 */
    private fun <T> productionProxy(block: (ProxyServerRepository, () -> Socket) -> T): T {
        val services = EntryPointAccessors.fromApplication(context.applicationContext, VerificationEntryPoint::class.java)
        val repository = services.repository()
        val settings = services.settings()
        val original = runBlocking { settings.settings.first() }
        fun stopped() {
            ProxyForegroundService.stop(context)
            await("production service stopped") {
                !repository.isRunning() &&
                    context.getSystemService(android.app.ActivityManager::class.java)
                        .getRunningServices(100).none { it.service.className == ProxyForegroundService::class.java.name }
            }
        }
        stopped()
        val iface = InterfaceScanner().scan(emptySet()).first { it.address is java.net.Inet4Address }
        val port = ServerSocket(0).use { it.localPort }
        try {
            runBlocking {
                settings.update {
                    ProxySettings(httpEnabled = true, httpPort = port, socksEnabled = false, pacEnabled = false,
                        selectedInterfaceIds = setOf(iface.id), egressChoice = EgressNetworkChoice.VPN,
                        egressFallback = EgressFallback.STRICT, ruleEngineEnabled = true)
                }
            }
            ProxyForegroundService.start(context)
            await("production proxy ready", 30000) {
                repository.state.value.let { it.running && it.diagnostics.httpPortUp && !it.admissionEmpty }
            }
            return block(repository) {
                val socket = Socket(iface.address, port).apply { soTimeout = 5000; tcpNoDelay = true }
                try {
                    socket.getOutputStream().write("CONNECT 203.0.113.7:443 HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                    val input = socket.getInputStream()
                    val status = line(input)
                    assertTrue("production CONNECT: $status", status.contains(" 200 "))
                    while (line(input).isNotEmpty()) { }
                    socket
                } catch (error: Throwable) { socket.close(); throw error }
            }
        } finally {
            stopped()
            runBlocking { settings.update { original } }
        }
    }

    @Test fun vpnDisconnectRecoveryWifiLossAndListenerRestartPreserveRouting() = withVpn { gateway ->
        gateway.roundTrip()
        assertTrue(ControlledVpnService.echoedBytes.get() >= 128)
        val initialNet = gateway.provider.egressNetwork()!!.networkHandle
        stopVpn()
        await("VPN disappearance") { !gateway.provider.status().vpn }
        gateway.client().use { socket ->
            socket.getOutputStream().write("CONNECT 203.0.113.7:443 HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
            val response = line(socket.getInputStream())
            // 所选PROXY出口缺失的既有契约是RemoteUnreachable/502；DIRECT缺网才是503。
            assertTrue("STRICT must reject missing selected VPN, got $response", response.contains(" 502 "))
            assertEquals("failed request must not bind a physical fallback", initialNet, gateway.bound.get())
        }
        startVpn()
        await("replacement VPN") {
            gateway.provider.egressNetwork()?.networkHandle?.let { it != initialNet } == true
        }
        gateway.roundTrip(1)
        await("initial Wi-Fi") { gateway.provider.status().wifi }
        shell("svc wifi disable")
        await("Wi-Fi lost") { !gateway.provider.status().wifi }
        gateway.provider.setDirectEgressChoice(DirectEgressChoice.WIFI)
        val failure = runBlocking { runCatching {
            gateway.connector.connect("203.0.113.7", 443, bypassVpn = true).close()
        }.exceptionOrNull() }
        assertTrue(failure is ProxyException)
        assertEquals(ProxyError.DirectEgressUnavailable, (failure as ProxyException).error)
        gateway.roundTrip(2)
        shell("svc wifi enable")
        shell("cmd wifi connect-network AndroidWifi open")
        await("Wi-Fi recovered", 30000) { gateway.provider.status().wifi }
        repeat(8) { n ->
            val old = gateway.connect()
            gateway.echo(old, n)
            gateway.restartListener()
            gateway.roundTrip(n + 10)
            old.close()
        }
        await("all leases released") { gateway.registry.activeGlobal == 0 }
        assertFalse("Must exercise actual NIO path", gateway.blocking.get())
        Log.i("RobustVpn", "lifecycle PASS oldNet=$initialNet newNet=${gateway.provider.egressNetwork()?.networkHandle} packets=${ControlledVpnService.packets.get()}")
        Unit
    }

    @Test fun screenOffEnduranceKeepsTunnelAndProcessAlive() = withVpn { gateway ->
        val seconds = InstrumentationRegistry.getArguments().getString("enduranceSeconds", "30").toLong()
        require(seconds in 10..1800)
        val pid = android.os.Process.myPid()
        val sid = ProcessDiagnostics.current?.id
        productionProxy { repository, connect ->
            connect().use { gateway.echo(it, 0) }
            val baseFds = File("/proc/self/fd").list()!!.size
            val start = android.os.SystemClock.elapsedRealtime()
            var rounds = 0
            var nextReport = start
            connect().use { persistent ->
                shell("input keyevent 223")
                await("screen off") { !context.getSystemService(PowerManager::class.java).isInteractive }
                while (android.os.SystemClock.elapsedRealtime() - start < seconds * 1000) {
                    gateway.echo(persistent, rounds)
                    if (rounds % 20 == 0) connect().use { gateway.echo(it, rounds) }
                    assertEquals(pid, android.os.Process.myPid())
                    assertEquals(sid, ProcessDiagnostics.current?.id)
                    rounds++
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now >= nextReport) {
                        Log.i("RobustVpn", "endurance elapsedMs=${now-start} rounds=$rounds fd=${File("/proc/self/fd").list()!!.size} active=${repository.state.value.activeConnections} running=${repository.isRunning()}")
                        nextReport = now + 60000
                    }
                    Thread.sleep(250)
                }
            }
            await("endurance connections released") { repository.state.value.activeConnections == 0 }
            assertTrue(repository.isRunning())
            assertTrue(repository.state.value.totalBytes >= rounds * 256L)
            val finalFds = File("/proc/self/fd").list()!!.size
            assertTrue("FDs grew beyond fixed runtime allowance: $baseFds -> $finalFds", finalFds <= baseFds + 24)
            Log.i("RobustVpn", "endurance PASS seconds=$seconds rounds=$rounds pid=$pid sid=$sid fd=$baseFds->$finalFds")
        }
        Unit
    }
}
