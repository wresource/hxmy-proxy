package com.mzstd.hxmyproxy

import android.net.Network
import com.mzstd.hxmyproxy.core.log.FileLog
import com.mzstd.hxmyproxy.core.network.*
import com.mzstd.hxmyproxy.core.proxy.*
import com.mzstd.hxmyproxy.core.security.EgressGuard
import io.mockk.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class ReviewDnsRegressionTest {
    private val allowed = object : EgressGuard { override fun isAllowed(addr: InetAddress) = true }
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun net(id: Long) = mockk<Network> { every { networkHandle } returns id }
    @Suppress("UNCHECKED_CAST")
    private suspend fun resolve(c: OutboundConnector, host: String, n: Network? = null, trace: RequestTrace? = null) =
        invokeSuspend(c, "resolve", host, n, trace) as List<InetAddress>

    @Test(timeout=10000) fun slowCorrectPrimaryBeatsFastDifferentPhysicalAnswer() = runBlocking {
        val vpn = net(101); val physical = net(102)
        val provider = mockk<UnderlyingNetworkProvider> { every { current() } returns physical }
        val calls = AtomicInteger(); val cancelled = mutableListOf<String>()
        val c = testConnector(allowed, underlyingNetworkProvider=provider, systemDns=SystemDnsLookup { n, _ ->
            calls.incrementAndGet()
            try {
                when(n) {
                    vpn -> { delay(1100); listOf(ip("192.0.2.100")) }
                    physical -> listOf(ip("192.0.2.9"))
                    else -> { delay(900); listOf(ip("192.0.2.100")) }
                }
            } catch(e: CancellationException) { cancelled.add(if(n == vpn) "vpn" else "default"); throw e }
        }).apply { backupDnsEnabled=false }
        val start=System.nanoTime()
        val first=resolve(c,"split.example",vpn)
        val ms=(System.nanoTime()-start)/1000000
        val before=calls.get(); val second=resolve(c,"split.example",vpn)
        assertEquals(listOf(ip("192.0.2.100")), first); assertEquals(first, second)
        assertEquals(before,calls.get()); assertFalse("Good VPN response must not be cancelled",cancelled.contains("vpn"))
    }

    @Test(timeout=10000) fun issue1_controlFastVpnWinsAndHealthyProbePreventsSideline() = runBlocking {
        val vpn=net(201); val provider=mockk<UnderlyingNetworkProvider>(relaxed=true)
        val c=testConnector(allowed, underlyingNetworkProvider=provider, systemDns=SystemDnsLookup { _, _ -> listOf(ip("192.0.2.100")) })
        assertEquals(listOf(ip("192.0.2.100")),resolve(c,"fast.example",vpn))
        var probes=0; val triggeredAt=mutableListOf<Int>()
        val health=EgressHealth(probe={ probes++; true },nowMs={100000L})
        for(i in 1..6) if(health.recordFailure(vpn,"host$i.example")) { triggeredAt.add(i); health.confirmOrSideline(vpn) }
        assertEquals(1,probes); assertFalse(health.isSidelined(vpn))
        assertEquals(listOf(4),triggeredAt)
    }

    @Test(timeout=10000) fun sessionCancellationReleasesQueriesAndNextSessionCanResolve() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        var slow=true
        val c=testConnector(allowed,systemDns=SystemDnsLookup { _,_ ->
            entered.complete(Unit)
            if(slow) awaitCancellation() else listOf(ip("192.0.2.100"))
        }).apply { backupDnsEnabled=false }
        val old=async { resolve(c,"session.example") }
        entered.await()
        c.cancelPendingDns()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0,c.dnsStats().inflight)
        slow=false
        assertEquals(listOf(ip("192.0.2.100")),resolve(c,"session.example"))
    }

    private fun dohProvider(physical: Network?, answer: String?, calls: MutableList<String>): UnderlyingNetworkProvider {
        val doh=net(303)
        val provider=mockk<UnderlyingNetworkProvider> {
            every { current() } returns physical
            every { dohNetwork() } returns doh
        }
        every { doh.openConnection(any()) } answers {
            calls.add(firstArg<URL>().toString())
            mockk<HttpsURLConnection>(relaxed=true) {
                every { inputStream } answers { ByteArrayInputStream("{}".toByteArray()) }
            }
        }
        // Android JSON APIs are stubbed on the JVM. Replace this environment boundary
        // explicitly; DNS routing/merge/candidate selection remains production code.
        mockkConstructor(JSONObject::class)
        val arr=if(answer==null) null else mockk<JSONArray> {
            every { length() } returns 1
            every { getJSONObject(0) } returns mockk<JSONObject> {
                every { optInt("type") } returns 1
                every { getString("data") } returns answer
            }
        }
        every { anyConstructed<JSONObject>().optJSONArray("Answer") } returns arr
        return provider
    }

    @Test(timeout=10000) fun dohCandidateSurvivesSixPhysicalAnswers() = runBlocking {
        val physical=net(302); val http=mutableListOf<String>()
        val provider=dohProvider(physical,"192.0.2.200",http)
        try {
            val bad=(1..6).map { ip("192.0.2.$it") }; val good=ip("192.0.2.200")
            val c=testConnector(allowed,underlyingNetworkProvider=provider,systemDns=SystemDnsLookup { n,_ ->
                if(n==physical) bad else throw UnknownHostException("controlled default DNS failure")
            })
            val dir=File(System.getProperty("java.io.tmpdir"),"hxmy-review-mixed-dns").apply { deleteRecursively(); mkdirs() }
            FileLog.enabled=true; FileLog.init(dir)
            val answers=resolve(c,"merge.example",trace=RequestTrace.open("HTTP",12345))
            assertEquals(listOf(good)+bad,answers)
            val capped=c.capByFamily(answers)
            assertTrue(capped.contains(good)); assertEquals(6,capped.size)
            FileLog.flush()
            assertTrue(File(dir,"app.log").readText().contains("src=rescue-mixed"))
            assertEquals(1L,c.dnsStats().rescueUses)
        } finally { unmockkConstructor(JSONObject::class) }
    }

    @Test(timeout=10000) fun emptyAnswerIsNotLoggedAsDohSuccess() = runBlocking {
        val dir=File(System.getProperty("review.evidence", System.getProperty("java.io.tmpdir")),"empty-dns-log").apply { deleteRecursively(); mkdirs() }
        FileLog.enabled=true; FileLog.init(dir)
        val provider=mockk<UnderlyingNetworkProvider> { every { current() } returns null }
        val c=testConnector(allowed,underlyingNetworkProvider=provider,systemDns=SystemDnsLookup { _,_ -> throw UnknownHostException("controlled") }).apply { backupDnsEnabled=false }
        val err=runCatching { resolve(c,"none.example",trace=RequestTrace.open("HTTP",12345)) }.exceptionOrNull()
        FileLog.flush()
        val log=dir.listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(err is ProxyException)
        assertFalse(log.contains("src=doh"))
        assertEquals(0L,c.dnsStats().dohCalls)
    }

    @Test(timeout=10000) fun oneCancelledWaiterDoesNotKillHealthySharedLookup() = runBlocking {
        val entered=CompletableDeferred<Unit>(); var queries=0
        val c=testConnector(allowed,systemDns=SystemDnsLookup { _,_ -> queries++; entered.complete(Unit); delay(350); listOf(ip("192.0.2.100")) }).apply { backupDnsEnabled=false }
        val owner=async { withTimeoutOrNull(100) { resolve(c,"shared.example") } }
        entered.await()
        val waiter=async { runCatching { resolve(c,"shared.example") } }
        assertNull(owner.await())
        val answer=waiter.await().getOrThrow()
        assertEquals(listOf(ip("192.0.2.100")),answer); assertEquals(1,queries)
        val fresh=resolve(c,"shared.example")
        assertEquals(answer,fresh); assertEquals(1,queries)
    }

    @Test(timeout=10000) fun issue9_saturationRejectsImmediatelyInsteadOfQueueing() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val count=AtomicInteger()
        val c=testConnector(allowed,systemDns=SystemDnsLookup { _,_ -> if(count.incrementAndGet()==12) entered.complete(Unit); awaitCancellation() }).apply { backupDnsEnabled=false }
        val jobs=List(12) { i -> launch { resolve(c,"busy$i.example") } }
        try {
            entered.await(); val start=System.nanoTime()
            val failure=runCatching { resolve(c,"overflow.example") }.exceptionOrNull()
            val ms=(System.nanoTime()-start)/1000000
            assertTrue(failure is ProxyException); assertEquals(12,count.get()); assertTrue(ms<500)
        } finally { jobs.forEach { it.cancelAndJoin() } }
    }

    @Test(timeout=10000) fun invalidSkipsDohButOrdinaryFailureStillUsesAllEndpoints() = runBlocking {
        val calls=mutableListOf<String>(); val provider=dohProvider(null,null,calls)
        try {
            val c=testConnector(allowed,underlyingNetworkProvider=provider,systemDns=SystemDnsLookup { _,_ -> throw UnknownHostException("NXDOMAIN") })
            val e=runCatching { resolve(c,"definitely-does-not-exist.invalid") }.exceptionOrNull()
            assertTrue(e is ProxyException); assertEquals(0,calls.size)
            val control=runCatching { resolve(c,"missing.example.net") }.exceptionOrNull()
            assertTrue(control is ProxyException); assertEquals(8,calls.size)
            assertEquals(4,calls.map { URL(it).host }.distinct().size)
        } finally { unmockkConstructor(JSONObject::class) }
    }
}
