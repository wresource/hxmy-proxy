package com.mzstd.hxmyproxy

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

class RuntimeIssueDeviceTest {
    private fun owned() = check(Build.HARDWARE == "ranchu" &&
        InstrumentationRegistry.getArguments().getString("ownedRuntimeIssues") == "true")
    private val loop = InetAddress.getByName("127.0.0.1")
    private suspend fun request(server: HttpProxyServer, host: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val port = withTimeout(4000) { while (server.boundPort.value == null) delay(10); server.boundPort.value!! }
        Socket(loop, port).use { s ->
            s.soTimeout = 4000
            s.getOutputStream().write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray())
            val input = DataInputStream(s.getInputStream())
            val header = buildString { while (!endsWith("\r\n\r\n")) { append(input.readUnsignedByte().toChar()); check(length < 32768) } }
            val size = Regex("(?i)content-length: (\\d+)").find(header)!!.groupValues[1].toInt()
            val body = ByteArray(size); input.readFully(body)
            header to String(body)
        }
    }
    private fun server(c: OutboundConnector, registry: ConnectionRegistry) = HttpProxyServer(
        Dispatchers.IO, AllowAllAccessController, registry, c, RelayEngine(), { NoAuthAuthenticator },
        { ConnectionLimits() }, Dispatchers.IO)

    @Test fun privateDnsResponseRetains403AndGivesTheCorrectExplanation() = runBlocking {
        owned()
        val fresh = AtomicInteger()
        val c = OutboundConnector(DefaultEgressGuard(true), systemDns = object : SystemDnsLookup {
            override suspend fun resolve(network: android.net.Network?, host: String) = listOf(InetAddress.getByName("10.0.0.1"))
            override suspend fun resolveFresh(network: android.net.Network?, host: String): List<InetAddress> {
                assertNull(network); fresh.incrementAndGet(); return listOf(InetAddress.getByName("10.0.0.1"))
            }
        }).apply { backupDnsEnabled = false }
        val registry = ConnectionRegistry(16, 16); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server(c, registry); server.start(scope, 0)
        try {
            val (header, body) = request(server, "public.example")
            assertTrue(header.startsWith("HTTP/1.1 403")); assertTrue(body.contains("dns-local-address"))
            assertTrue(header.contains("X-Hxmyproxy-Error: dns-local-address\r\n"))
            assertFalse(body.contains("enable Allow local network targets")); assertEquals(1, fresh.get())
            val (_, local) = request(server, "10.0.0.1")
            assertTrue(local.contains("local-network-disabled")); assertEquals(1, fresh.get())
        } finally { server.stop(); scope.cancel(); c.cancelPendingDns() }
    }

    @Test fun realHttpEntryReports503ForDnsCapacityAndReleasesAllSlots() = runBlocking {
        owned()
        val entered = AtomicInteger(); val ready = CompletableDeferred<Unit>()
        val c = OutboundConnector(DefaultEgressGuard(), systemDns = SystemDnsLookup { _, _ ->
            if (entered.incrementAndGet() == 12) ready.complete(Unit)
            awaitCancellation()
        }).apply { backupDnsEnabled = false }
        val registry = ConnectionRegistry(16, 16); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = server(c, registry); server.start(scope, 0)
        val jobs = List(12) { i -> async { runCatching { c.connect("held-$i.example", 443) } } }
        try {
            withTimeout(3000) { ready.await() }
            val (header, body) = request(server, "overflow.example")
            assertTrue(header, header.startsWith("HTTP/1.1 503")); assertTrue(body, body.contains("dns-busy"))
            assertTrue(header.contains("X-Hxmyproxy-Error: dns-busy\r\n"))
            assertEquals(12, entered.get())
        } finally {
            jobs.forEach { it.cancel() }; jobs.joinAll(); server.stop(); scope.cancel(); c.cancelPendingDns()
        }
        assertEquals(0, c.dnsStats().inflight)
    }

    private fun pair(): Pair<SocketChannel, SocketChannel> = ServerSocketChannel.open().use { ss ->
        ss.bind(InetSocketAddress(loop, 0)); SocketChannel.open(ss.localAddress) to ss.accept()
    }
    private suspend fun flow(nio: Boolean): Long = coroutineScope {
        val (client, c) = pair(); val (server, u) = pair()
        val reactor = NioRelayReactor(1, 100).apply { start() }
        val host = if (nio) "nio-progress.example" else "blocking-progress.example"
        val trace = RequestTrace.open("HTTP", client.socket().localPort)
        if (nio) { c.configureBlocking(false); u.configureBlocking(false) }
        val relay = launch(Dispatchers.IO) {
            if (nio) reactor.relay(c, u, 8192, 1500, host, trace) { _, _ -> }
            else RelayEngine().relay(c.socket(), u.socket(), 8192, 1500, Dispatchers.IO, host = host, trace = trace)
        }
        val writer = launch(Dispatchers.IO) {
            val initial = ByteBuffer.wrap(ByteArray(512 * 1024) { (it % 251).toByte() })
            while (initial.hasRemaining()) server.write(initial)
            repeat(128) { delay(250); server.write(ByteBuffer.wrap(byteArrayOf(7))) }
            server.shutdownOutput()
        }
        try {
            val received = withContext(Dispatchers.IO) {
                client.socket().soTimeout = 5000
                val expected = ByteArray(512 * 1024) { (it % 251).toByte() } + ByteArray(128) { 7 }
                val actual = ByteArray(expected.size); DataInputStream(client.socket().getInputStream()).readFully(actual)
                assertArrayEquals(expected, actual); expected.size.toLong()
            }
            writer.join(); client.shutdownOutput(); withTimeout(4000) { relay.join() }
            received
        } finally {
            listOf(client, c, server, u).forEach { runCatching { it.close() } }
            writer.cancelAndJoin(); relay.cancelAndJoin(); reactor.stop()
        }
    }

    @Test fun activeProgressIsRecordedWithoutBreakingOneWayTrafficOrHalfClose() = runBlocking {
        owned(); val previous = FileLog.enabled; FileLog.enabled = true
        try {
            val results = withTimeout(45000) { listOf(async { flow(true) }, async { flow(false) }).awaitAll() }
            assertEquals(listOf(524416L, 524416L), results)
            val log = FileLog.snapshot()
            for (host in listOf("nio-progress.example", "blocking-progress.example")) {
                val rows = log.lineSequence().filter { "evt=req.progress " in it && "host=$host " in it }.toList()
                assertEquals("one bounded sample in a 32-second flow: $host", 1, rows.size)
                assertTrue(rows.single().contains("windowMs=")); assertTrue(rows.single().contains("upstreamReadAgoMs="))
            }
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "runtime-313-native-progress.txt")
                .writeText(log.lineSequence().filter { "-progress.example" in it }.joinToString("\n"))
        } finally { FileLog.enabled = previous }
    }
}
