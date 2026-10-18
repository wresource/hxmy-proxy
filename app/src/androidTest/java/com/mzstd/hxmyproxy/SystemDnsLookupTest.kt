package com.mzstd.hxmyproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mzstd.hxmyproxy.core.network.AndroidSystemDnsLookup
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.mzstd.hxmyproxy.core.proxy.OutboundConnector
import com.mzstd.hxmyproxy.core.proxy.ProxyException
import com.mzstd.hxmyproxy.core.proxy.ProxyError
import com.mzstd.hxmyproxy.core.security.EgressGuard
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/** Real Android DNS API coverage; JVM tests explicitly do not stand in for this adapter. */
@RunWith(AndroidJUnit4::class)
class SystemDnsLookupTest {
    @Test fun systemQueryReturnsAddressesOnTheDefaultNetwork() = runBlocking {
        val answers = withTimeout(5000) { AndroidSystemDnsLookup.resolve(null, "example.com") }
        assertTrue(answers.isNotEmpty())
        assertTrue(answers.none { it.isLoopbackAddress || it.isAnyLocalAddress })
    }

    @Test fun localhostIsResolvedLocally() = runBlocking {
        val answers = withTimeout(1000) { AndroidSystemDnsLookup.resolve(null, "LOCALHOST.") }
        assertTrue(answers.all(InetAddress::isLoopbackAddress))
    }

    @Test fun failedSystemAndDohResolutionFinishWithinTheCombinedBudget() = runBlocking {
        val connector = OutboundConnector(object : EgressGuard {
            override fun isAllowed(addr: InetAddress) = true
        })
        val started = android.os.SystemClock.elapsedRealtime()
        val failure = runCatching {
            // .invalid is now rejected locally by design. An ordinary unresolvable name
            // must still exercise the actual Android resolver and HTTPS backup worker.
            withTimeout(6500) { connector.connect("${java.util.UUID.randomUUID()}.example.com", 443) }
        }.exceptionOrNull()
        assertTrue("应产生协议可见的DNS失败：$failure", failure is ProxyException)
        assertSame(ProxyError.DnsFailure, (failure as ProxyException).error)
        assertTrue(android.os.SystemClock.elapsedRealtime() - started < 6000)
        assertTrue("真实DoH分支必须被覆盖", connector.dnsStats().dohCalls > 0)
    }

    @Test fun invalidNameSkipsPublicDohOnDevice() = runBlocking {
        val connector = OutboundConnector(object : EgressGuard {
            override fun isAllowed(addr: InetAddress) = true
        })
        val failure = runCatching { connector.connect("${java.util.UUID.randomUUID()}.invalid",443) }.exceptionOrNull()
        assertTrue(failure is ProxyException)
        assertSame(ProxyError.DnsFailure,(failure as ProxyException).error)
        org.junit.Assert.assertEquals(0L,connector.dnsStats().dohCalls)
    }
}
