package com.mzstd.hxmyproxy

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.model.ConnectionLimits
import com.mzstd.hxmyproxy.core.network.SystemDnsLookup
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.rules.*
import com.mzstd.hxmyproxy.core.security.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real Android proxy/client sockets. DNS is a controlled lookup, never an Internet request. */
class DnsCacheRuleDeviceTest {
    private val loop = InetAddress.getByName("127.0.0.1")
    private fun owned() = check(Build.HARDWARE == "ranchu" &&
        InstrumentationRegistry.getArguments().getString("ownedDnsCache") == "true")

    private inner class Fixture(delayDns: Long = 0) {
        val queries = ConcurrentHashMap<String, AtomicInteger>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rules = RuleEngine()
        val c = OutboundConnector(object : EgressGuard { override fun isAllowed(addr: InetAddress) = true },
            systemDns = SystemDnsLookup { _, host ->
                queries.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet()
                if (delayDns > 0) delay(delayDns)
                listOf(loop)
            }).apply { backupDnsEnabled = false }
        val registry = ConnectionRegistry(32, 32)
        val proxy = HttpProxyServer(Dispatchers.IO, AllowAllAccessController, registry, c, RelayEngine(),
            { NoAuthAuthenticator }, { ConnectionLimits() }, Dispatchers.IO, ruleEngine = rules)
        val origin = ServerSocket(0, 32, loop)
        private val peers = ConcurrentHashMap.newKeySet<Socket>()
        init {
            proxy.start(scope, 0, loop)
            scope.launch {
                while (isActive) {
                    val peer = try { origin.accept() } catch (_: Exception) { break }
                    peers.add(peer)
                    launch {
                        runCatching {
                            val buf = ByteArray(4096)
                            while (true) {
                                val n = peer.getInputStream().read(buf)
                                if (n < 0) break
                                peer.getOutputStream().write(buf, 0, n)
                            }
                        }
                    }.invokeOnCompletion { peers.remove(peer); runCatching { peer.close() } }
                }
            }
        }
        suspend fun connect(host: String): Pair<Socket, Int> = withContext(Dispatchers.IO) {
            val port = withTimeout(4000) { while (proxy.boundPort.value == null) delay(5); proxy.boundPort.value!! }
            val client = Socket(loop, port)
            try {
                client.soTimeout = 5000
                client.getOutputStream().write("CONNECT $host:${origin.localPort} HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
                val input = DataInputStream(client.getInputStream())
                val head = buildString {
                    while (!endsWith("\r\n\r\n")) { append(input.readUnsignedByte().toChar()); check(length < 32768) }
                }
                client to head.substringAfter(' ').substringBefore(' ').toInt()
            } catch (e: Throwable) { client.close(); throw e }
        }
        @Suppress("UNCHECKED_CAST")
        suspend fun lookup(host: String): List<InetAddress> = suspendCoroutineUninterceptedOrReturn { cont ->
            c.javaClass.declaredMethods.single { it.name == "resolve" }.apply { isAccessible = true }
                .invoke(c, host, null, null, cont)
        }
        suspend fun shutdown() {
            proxy.stop(); origin.close(); scope.cancel(); peers.toList().forEach { runCatching { it.close() } }; c.cancelPendingDns()
            withContext(NonCancellable) { withTimeout(4000) { scope.coroutineContext[Job]!!.join(); while (registry.activeGlobal != 0) delay(5) } }
        }
        fun count(host: String) = queries[host]?.get() ?: 0
    }

    private suspend fun echo(client: Socket, text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(); client.getOutputStream().write(bytes)
        val got = ByteArray(bytes.size); DataInputStream(client.getInputStream()).readFully(got); assertArrayEquals(bytes, got)
    }

    @Test fun cachedAddressNeverOverridesNewRulesAndEvictionKeepsExistingTunnelAlive() = runBlocking {
        owned(); val f = Fixture(); var live: Socket? = null
        try {
            val first = f.connect("warm.example"); assertEquals(200, first.second); live = first.first
            echo(first.first, "before")
            f.connect("warm.example").also { assertEquals(200, it.second); it.first.close() }
            assertEquals(1, f.count("warm.example"))
            f.rules.update(RuleEngine.Snapshot(userReject = RuleMatcher().apply { add("warm.example") }))
            f.connect("warm.example").also { assertEquals(403, it.second); it.first.close() }
            assertEquals("REJECT precedes DNS even with a cached answer", 1, f.count("warm.example"))
            // A shared IP cannot be reverse-mapped to the blocked domain.
            f.connect("other.example").also { assertEquals(200, it.second); it.first.close() }
            f.connect("127.0.0.1").also { assertEquals(200, it.second); it.first.close() }
            echo(first.first, "existing-tunnel")
            f.rules.update(RuleEngine.Snapshot())
            repeat(512) { f.lookup("fill-$it.example"); assertTrue(f.c.dnsStats().cached <= 512) }
            assertEquals(512, f.c.dnsStats().cached)
            echo(first.first, "after-eviction")
            f.connect("warm.example").also { assertEquals(200, it.second); it.first.close() }
            assertEquals(2, f.count("warm.example"))
            f.c.clearDnsCache()
            f.connect("warm.example").also { assertEquals(200, it.second); it.first.close() }
            assertEquals(3, f.count("warm.example"))
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "dns-cache-rule-native.json")
                .writeText(JSONObject().put("passed", true).put("cacheCeiling", 512).put("blockedNewRequest", 403)
                    .put("sameIpOtherDomainAllowed", true).put("liveTunnelSurvived", true).put("warmQueriesAfterNetworkReset", 3).toString(2))
        } finally { live?.close(); f.shutdown() }
    }

    @Test fun concurrentProxyClientsShareLookupAndWarmCache() = runBlocking {
        owned(); val f = Fixture(delayDns = 100)
        try {
            withTimeout(12000) {
                List(8) { async { f.connect("shared.example").also { assertEquals(200, it.second); echo(it.first, "client"); it.first.close() } } }.awaitAll()
            }
            assertEquals(1, f.count("shared.example")); assertEquals(1, f.c.dnsStats().cached)
            assertEquals(0, f.c.dnsStats().inflight)
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "dns-cache-clients-native.json")
                .writeText(JSONObject().put("passed", true).put("clients", 8).put("systemLookupCalls", 1).put("cacheEntries", 1).toString(2))
        } finally { f.shutdown() }
    }
}
