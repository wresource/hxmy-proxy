package com.mzstd.hxmyproxy

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.mzstd.hxmyproxy.core.network.AndroidSystemDnsLookup
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.UUID
import org.json.JSONObject

/** Actual DnsResolver/netd over an emulator-only controlled dual-stack DNS Network.
 * Requires the standalone fixture in tasks/runtime-safety-fix-2026-11-07-evidence/native-dns. */
class NativeDnsIntegrityTest {
    private fun network(): Network {
        check(Build.HARDWARE == "ranchu")
        check(InstrumentationRegistry.getArguments().getString("controlledDnsFixture") == "true")
        val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.single { cm.getLinkProperties(it)?.dnsServers?.any { ip -> ip.hostAddress == "198.18.0.53" } == true }
    }
    private val v4 get() = (7..14).map { InetAddress.getByName("192.0.2.$it") }.toSet()
    private val v6 get() = (7..14).map { InetAddress.getByName("2001:db8::${it.toString(16)}") }.toSet()
    private fun fdCount() = File("/proc/self/fd").list()!!.size
    private fun descriptors() = File("/proc/self/fd").list()!!.mapNotNull { fd ->
        runCatching { fd to android.system.Os.readlink("/proc/self/fd/$fd") }.getOrNull()
    }.toMap()

    @Test fun parallelNativeQueriesKeepBothCompleteFamilies() = runBlocking {
        val net = network()
        withTimeout(25000) {
            repeat(25) {
                List(4) { async(Dispatchers.Default) {
                    val answer = AndroidSystemDnsLookup.resolve(net, "both.${UUID.randomUUID()}.qa.test")
                    assertEquals(16, answer.size)
                    assertEquals(v4 + v6, answer.toSet())
                } }.awaitAll()
            }
        }
        assertTrue("callbacks share at most two workers", Thread.getAllStackTraces().keys.count { it.name == "hxmy-dns-callback" } <= 2)
    }

    @Test fun oneEmptyFamilyStillReturnsTheOtherCompleteFamily() = runBlocking {
        val net = network()
        for ((prefix, expected) in listOf("v4only" to v4, "v6only" to v6)) {
            val answer = withTimeout(5000) { AndroidSystemDnsLookup.resolve(net, "$prefix.${UUID.randomUUID()}.qa.test") }
            assertEquals(8, answer.size); assertEquals(expected, answer.toSet())
        }
    }

    @Test fun cancelledQueriesReleaseNativeDescriptorsAndLateAnswersDoNotPoisonLaterQueries() = runBlocking {
        val net = network()
        withTimeout(5000) { AndroidSystemDnsLookup.resolve(net, "warm.qa.test") }
        val beforeDescriptors = descriptors()
        val before = fdCount()
        withTimeout(10000) {
            repeat(4) {
                List(8) { async(Dispatchers.Default) {
                    assertNull(withTimeoutOrNull(80) { AndroidSystemDnsLookup.resolve(net, "slow.${UUID.randomUUID()}.qa.test") })
                } }.awaitAll()
            }
        }
        delay(1700) // Receive the fixture's late A/AAAA replies after cancellation.
        val afterDescriptors = descriptors()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-dns-cancellation-fds.json")
            .writeText(JSONObject().put("before", JSONObject(beforeDescriptors)).put("after", JSONObject(afterDescriptors)).toString(2))
        assertTrue("native descriptors returned: before=$before after=${fdCount()}", fdCount() <= before + 4)
        val answer = withTimeout(5000) { AndroidSystemDnsLookup.resolve(net, "after.${UUID.randomUUID()}.qa.test") }
        assertEquals(16, answer.size); assertEquals(v4 + v6, answer.toSet())
    }
}
